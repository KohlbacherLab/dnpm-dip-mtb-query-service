package de.dnpm.dip.mtb.query.impl


import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.must.Matchers._
import org.scalatest.OptionValues._
import org.scalatest.EitherValues._
import org.scalatest.Inspectors._
import scala.util.Random
import scala.concurrent.Future
import cats.Monad
import de.dnpm.dip.coding.{
  Code,
  CodeSystem,
  Coding
}
import de.dnpm.dip.coding.hgvs.HGVS
import de.dnpm.dip.mtb.query.api._
import de.dnpm.dip.mtb.model.MTBPatientRecord
import de.dnpm.dip.service.query.{
  BaseQueryCache,
  Data,
  Query,
  Querier,
  PreparedQuery,
  PreparedQueryDB,
  InMemPreparedQueryDB
}
import de.dnpm.dip.connector.FakeConnector
import de.ekut.tbi.generators.Gen
import play.api.libs.json.{
  Json,
  Writes
}


class Tests extends AsyncFlatSpec
{

  import scala.util.chaining._
  import de.dnpm.dip.mtb.gens.Generators._

  System.setProperty("dnpm.dip.connector.type","fake")
  System.setProperty(MTBLocalDB.dataGenProp,"0")


  implicit val rnd: Random =
    new Random

  implicit val querier: Querier =
    Querier("Dummy-Querier-ID")

 
  val serviceTry =
    MTBQueryService.getInstance

  lazy val service = serviceTry.get


  val dataSets =
    LazyList.fill(50)(Gen.of[MTBPatientRecord].next)


  // Generator for non-empty Query Criteria based on features occurring in a given dataset,
  // and thus guaranteed to always match at least this one data set
  val genCriteria: Gen[MTBQueryCriteria] =
    for {
      patRec <- Gen.oneOf(dataSets)

      icd10 =
        patRec.getDiagnoses
          .head
          .code

      snv =
        patRec
          .getNgsReports.head
          .results
          .simpleVariants
          .head

      snvCriteria =
        SNVCriteria(
          snv.gene,
          None,
          snv.proteinChange
            // Change the protein change to just a substring of the occurring one
            // to test that matches are also returned by substring match of the protein (or DNA) change
            .map(
              pch => pch.copy( 
                code = Code[HGVS](pch.code.value.substring(2,pch.code.value.size-1))
              )
            )
        )

      medication =
        patRec
          .getMedicationTherapies.head
          .history.head
          .medication
          .get

    } yield MTBQueryCriteria(
      Some(Set(icd10)),
      None,
      Some(Set(snvCriteria)),
      None,
      None,
      None,
      Some(
        MedicationCriteria(
          None,
          medication,
          Set(Coding(MedicationUsage.Used))
        )
      ),
      None,
    )


  "SPI" must "have worked" in {
    serviceTry.isSuccess mustBe true
  }


  "Importing MTBPatientRecords" must "have worked" in {

    for {
      outcomes <-
        Future.traverse(dataSets)(service ! Data.Save(_))
    } yield all (outcomes.map(_.isRight)) mustBe true 
    
  }


  val queryMode =
    Some(
      Coding(Query.Mode.Local)
    )


  "Query ResultSet" must "contain the total number of data sets for a query without criteria" in {

    for {
      result <-
        service ! Query.Submit(
          queryMode,
          None,
          MTBQueryCriteria(None,None,None,None,None,None,None,None)
        )

      query =
        result.right.value

      resultSet <-
        service.resultSet(query.id).map(_.value)

      summary = resultSet.summary()

      _ = summary.patientCount must equal (dataSets.size) 

      _ = summary.diagnostics.overallDistributions.tumorEntities.elements must not be empty

      _ = summary.medication.recommendations.distributionBySupportingVariant must not be empty

      _ = summary.medication.therapies.responseDistributionByTherapy must not be empty

    } yield succeed

  }


  it must "contain a non-empty list of correctly matching data sets for a query with criteria" in {

    import MTBQueryCriteriaOps._

    for {
      result <-
        service ! Query.Submit(
          queryMode,
          None,
          genCriteria.next
        )

      query =
        result.right.value

      resultSet <-
        service.resultSet(query.id)
          .map(_.value)

      patientMatches = 
        resultSet.patientMatches()

      _ = all (query.criteria.diagnoses.value.map(_.display)) must be (defined)  
      _ = all (query.criteria.diagnoses.value.map(_.version)) must be (defined)  

      _ = patientMatches must not be empty

      _ = patientMatches.size must be < (dataSets.size) 

    } yield forAll(
        patientMatches.map(_.matchingCriteria)
      ){ 
        matches =>
          assert( (query.criteria intersect matches).nonEmpty )
      }

  }


  "PreparedQuery" must "have been successfully created" in {

    for {
      result <-
        service ! PreparedQuery.Create("Dummy Prepared Query",genCriteria.next)

    } yield result.isRight mustBe true 

  }

  it must "have been successfully retrieved" in {

    for {
      result <-
        service ? PreparedQuery.Query(Some(querier))

      _ = result must not be empty 

      query <- 
        service ? result.head.id

    } yield query must be (defined)

  }

}
