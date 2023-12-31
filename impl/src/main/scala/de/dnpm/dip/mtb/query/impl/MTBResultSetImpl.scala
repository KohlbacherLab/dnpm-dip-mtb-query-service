package de.dnpm.dip.mtb.query.impl



import de.dnpm.dip.coding.{
  Coding,
  CodeSystem
}
import de.dnpm.dip.coding.hgnc.HGNC
import de.dnpm.dip.model.Snapshot
import de.dnpm.dip.service.query.{
  Entry,
  PatientFilter,
  PatientMatch,
  Query,
  ResultSet,
  BaseResultSet,
  Distribution,
  ReportingOps
}
import de.dnpm.dip.mtb.model.MTBPatientRecord
import de.dnpm.dip.mtb.query.api.{
  MTBQueryCriteria,
  MTBResultSet,
}


class MTBResultSetImpl
(
  val id: Query.Id,
  val results: Seq[(Snapshot[MTBPatientRecord],MTBQueryCriteria)]
)(
  implicit hgnc: CodeSystem[HGNC]
)  
extends MTBResultSet
with BaseResultSet[MTBPatientRecord,MTBQueryCriteria]
with MTBReportingOps
{

  import scala.util.chaining._
  import MTBResultSet.{
    Summary,
    TumorDiagnostics,
    Medication
  }

  override def summary(
    f: MTBPatientRecord => Boolean
  ): Summary =
    records
      .filter(f)
      .pipe {
        recs =>

        Summary(
          id,
          recs.size,
          ResultSet.Demographics.on(recs.map(_.patient)),
          TumorDiagnostics(
            DistributionOf(
              recs.flatMap(_.diagnoses.toList)
                .map(_.code)
            ),
            TumorEntitiesByVariant(records),
            DistributionOf(
              recs.flatMap(_.getHistologyReports)
                .flatMap(_.results.tumorMorphology.map(_.value))
            )
          ),
          Medication(
            Medication.Recommendations(
              DistributionBy(
                recs
                  .flatMap(
                    _.getCarePlans.flatMap(_.medicationRecommendations)
                  )
                  .map(_.medication)
              )(
                _.flatMap(_.display)
              ),
              RecommendationsBySupportingVariant(records)
            ),
            Medication.Therapies(
              TherapiesWithMeanDuration(records),
              ResponsesByTherapy(records)  
            )
          )
        )

    }

}
