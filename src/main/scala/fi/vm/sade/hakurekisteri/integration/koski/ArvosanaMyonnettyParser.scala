package fi.vm.sade.hakurekisteri.integration.koski

import org.joda.time.LocalDate
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

object ArvosanaMyonnettyParser {
  private val logger = LoggerFactory.getLogger(getClass)

  def findArviointipäivä(suoritus: KoskiOsasuoritus,
                         personOid: String,
                         aine: String,
                         suorituksenValmistumispäivä: LocalDate): Option[LocalDate] = {
    def flattenSuoritukset(s: KoskiOsasuoritus): Seq[KoskiOsasuoritus] = {
      val seq = s.osasuoritukset.toSeq.flatten.flatMap(flattenSuoritukset)
      seq.++(List(s))
    }

    val allOsasuoritukset: Seq[KoskiOsasuoritus] = flattenSuoritukset(suoritus)
    val allArviointis: Seq[KoskiArviointi] = allOsasuoritukset.flatMap(_.arviointi)

    val osaSuoritusDateString: Option[String] = allArviointis.map(_.päivä).max
    findMyonnettyToUse(osaSuoritusDateString, personOid, aine, suorituksenValmistumispäivä)
  }

  private def findMyonnettyToUse(koskiArviointiPäivä: Option[String], personOid: String, aine: String, suorituksenValmistumispäivä: LocalDate): Option[LocalDate] = {
    Try (koskiArviointiPäivä.map(new LocalDate(_))) match {
      case Success(dateFromKoski@Some(foundArviointiPvm)) if foundArviointiPvm.isAfter(suorituksenValmistumispäivä) => dateFromKoski
      case Failure(e) =>
        logger.warn(s"Virhe käsitellessä arvioinnin päivää '$koskiArviointiPäivä' hakijan '$personOid' aineelle '$aine' ." +
          s"Tallennetaan arvosanan myöntöpäiväksi tyhjä.", e)
        None
      case _ => None
    }
  }
}
