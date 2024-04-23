package de.dnpm.dip.mtb.query.impl


import java.time.LocalDate
import cats.{
  Applicative,
  Id
}
import de.dnpm.dip.util.{
  Completer,
  DisplayLabel
}
import de.dnpm.dip.coding.{
  Code,
  Coding,
  CodeSystem,
  CodeSystemProvider
}
import de.dnpm.dip.coding.atc.ATC
import de.dnpm.dip.coding.icd.{
  ICD10GM,
  ICDO3
}
import de.dnpm.dip.coding.hgnc.HGNC
import de.dnpm.dip.coding.hgvs.HGVS
import de.dnpm.dip.model.{
  Patient,
  Site,
  Reference,
  Resolver,
}
import de.dnpm.dip.mtb.query.api._



trait Completers
{

  import scala.util.chaining._
  import Completer.syntax._


  implicit val hgnc: CodeSystemProvider[HGNC,Id,Applicative[Id]]

  implicit val atc: CodeSystemProvider[ATC,Id,Applicative[Id]]

  implicit val icd10gm: CodeSystemProvider[ICD10GM,Id,Applicative[Id]]

  implicit val icdo3: ICDO3.Catalogs[Id,Applicative[Id]]


  private implicit def hgvsCompleter[S <: HGVS]: Completer[Coding[S]] =
    Completer.of(
      coding => coding.copy(
        display = coding.display.orElse(Some(coding.code.value))
      )
    )

  private implicit val icdo3mCompleter: Completer[Coding[ICDO3.M]] =
    Completer.of {
      coding =>
        coding.version
          .flatMap(icdo3.morphology(_))
          .getOrElse(icdo3.morphology)
          .concept(coding.code)
          .map(
            concept =>
              coding.copy(
                display = Some(concept.display),
                version = concept.version
              )
          )
          .getOrElse(coding)
    }


  implicit val medicationCriteriaCompleter: Completer[MedicationCriteria] =
    Completer.of(
      med => med.copy(
        drugs = med.drugs.complete,
        usage = med.usage.complete
      )
    )

  implicit protected val criteriaCompleter: Completer[MTBQueryCriteria] = {

    implicit val snvCriteriaCompleter: Completer[SNVCriteria] = {

      val proteinChangeCompleter: Completer[Coding[HGVS.Protein]] =
        Completer.of {
          coding =>
            val threeLetterCode = HGVS.Protein.to3LetterCode(coding.code.value)
            coding.copy(
              code = Code[HGVS.Protein](threeLetterCode),
              display = coding.display.orElse(Some(threeLetterCode))
            )
        }

      Completer.of(
        snv => snv.copy(
          gene          = snv.gene.complete,
          dnaChange     = snv.dnaChange.complete,
          proteinChange = snv.proteinChange.map(proteinChangeCompleter)
        )
      )
    }

    implicit val cnvCriteriaCompleter: Completer[CNVCriteria] =
      Completer.of(
        cnv => cnv.copy(
          affectedGenes = cnv.affectedGenes.complete,
          `type`        = cnv.`type`.complete
        )
      )

    implicit val fusionCriteriaCompleter: Completer[FusionCriteria] =
      Completer.of(
        fusion => fusion.copy(
          fusionPartner5pr = fusion.fusionPartner5pr.complete,
          fusionPartner3pr = fusion.fusionPartner3pr.complete,
        )
      )

    Completer.of(
      criteria => criteria.copy(
        diagnoses         = criteria.diagnoses.complete,
        tumorMorphologies = criteria.tumorMorphologies.complete,
        simpleVariants     = criteria.simpleVariants.complete,
        copyNumberVariants = criteria.copyNumberVariants.complete,
        dnaFusions         = criteria.dnaFusions.complete,
        rnaFusions         = criteria.rnaFusions.complete,
        medication         = criteria.medication.complete,
        responses          = criteria.responses.complete,
      )
    )

  }



  // By-name csp value (i.e. "lazy" as only evaluated upon being referenced) 
  // is required because the trait value is not yet initialized at this point,
  // resulting in weird null pointer exception
  private def descendantExpander[T: Coding.System](
    implicit csp: => CodeSystemProvider[T,Id,Applicative[Id]]
  ): Completer[Set[Coding[T]]] =
    Completer.of(
      _.flatMap {
        coding =>
          val cs =
            coding.version
              .flatMap(csp.get)
              .getOrElse(csp.latest)

          (cs.concept(coding.code).toSet ++ cs.descendantsOf(coding.code))
            .map(_.toCoding)
      }
    )


  val CriteriaExpander: Completer[MTBQueryCriteria] = {

    implicit val icd10Expander: Completer[Set[Coding[ICD10GM]]] =
      descendantExpander[ICD10GM]

    implicit val atcExpander: Completer[Set[Coding[ATC]]] =
      descendantExpander[ATC]

    implicit val icdO3MExpander: Completer[Set[Coding[ICDO3.M]]] =
      Completer.of(
        _.flatMap(
          coding =>
            Set(coding.complete) ++
            coding.version
              .flatMap(icdo3.morphology(_))
              .getOrElse(icdo3.morphology)
              .descendantsOf(coding.code)
              .map(_.toCoding)
       )
     )


    Completer.of(
      criteria => criteria.copy(
        diagnoses         = criteria.diagnoses.complete,
        tumorMorphologies = criteria.tumorMorphologies.complete,
        medication        = criteria.medication.complete,
      )
    )

  }

}
