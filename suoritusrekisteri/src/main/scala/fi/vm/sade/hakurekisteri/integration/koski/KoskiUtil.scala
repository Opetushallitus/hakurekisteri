package fi.vm.sade.hakurekisteri.integration.koski

import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat

import java.sql.Date
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

object KoskiUtil {

  val koski_integration_source = "koski"
  var deadlineDate: LocalDate = new LocalDate(
    OphUrlProperties.getProperty("suoritusrekisteri.koski.deadline.date")
  )

  lazy val tuvaStartDate: LocalDate = {
    new LocalDate(deadlineDate.year().get() - 1, 1, 1)
  }

  //"2024-12-17T10:55:33" massaluovutsrajapinnan käyttämä formaatti
  lazy val koskiFetchStartTime: String =
    OphUrlProperties.getProperty("suoritusrekisteri.koski.start.timestamp") match {
      case s: String if s.length == 19 => s
      case _ =>
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
          .format(new Date(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)))
    }

  lazy val koskiImporterResourceInUse: Boolean =
    OphUrlProperties.getProperty("suoritusrekisteri.use.koski.importer.resource").toBoolean
  lazy val updateKkHaut: Boolean =
    OphUrlProperties.getProperty("suoritusrekisteri.koski.update.kkHaut").toBoolean
  lazy val updateToisenAsteenHaut: Boolean =
    OphUrlProperties.getProperty("suoritusrekisteri.koski.update.toisenAsteenHaut").toBoolean
  lazy val updateJatkuvatHaut: Boolean =
    OphUrlProperties.getProperty("suoritusrekisteri.koski.update.jatkuvatHaut").toBoolean

  def isAfterArvosanatWithNelosiaDeadlineDate(): Boolean = {
    // Neloset halutaan tallentaa suoritusrekisteriin kaksi viikkoa ennen deadline-päivämäärää
    LocalDate.now().isAfter(deadlineDate.minusDays(14))
  }

  //Suoritukses that have been saved in the last 6 hours will not be removed even if they seem to be missing from Koski.
  val koskiSuoritusRemovalCooldownHours = 6

  def shouldSuoritusBeRemoved(lastmodified: Long): Boolean = {
    lastmodified < System.currentTimeMillis() - (3600000 * koskiSuoritusRemovalCooldownHours)
  }

  def isAfterDeadlineDate(date: LocalDate = LocalDate.now()): Boolean = {
    date.isAfter(deadlineDate)
  }

  def isBeforeTuvaStartDate(date: LocalDate = LocalDate.now()): Boolean = {
    date.isBefore(tuvaStartDate)
  }

  def parseLocalDate(s: String): LocalDate =
    if (s.length() > 10) {
      DateTimeFormat.forPattern("yyyy-MM-ddZ").parseLocalDate(s)
    } else {
      DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate(s)
    }

  val valinnaisetkielet = Set("A1", "B1")
  val a2b2Kielet = Set("A2", "B2")
  val valinnaiset = Set("KO") ++ valinnaisetkielet

  val kielet = Set("A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33")
  val oppiaineet =
    Set("HI", "MU", "BI", "KT", "FI", "KO", "KE", "YH", "TE", "KS", "FY", "GE", "LI", "KU", "MA")
  val eivalinnaiset = kielet ++ oppiaineet ++ Set("AI")
  val peruskoulunaineet = kielet ++ oppiaineet ++ Set("AI")
  val lukioaineet = peruskoulunaineet ++ Set("PS") //lukio has psychology as a mandatory subject
  val lukioaineetRegex = lukioaineet.map(_.r)

  val kieletRegex = kielet.map(str => str.r)
  val oppiaineetRegex = oppiaineet.map(str => s"$str\\d?".r)
  val peruskouluaineetRegex = kieletRegex ++ oppiaineetRegex ++ Set("AI".r)

  val peruskoulunArvosanat = Set[String]("4", "5", "6", "7", "8", "9", "10")
  val aidinkieli = Map(
    "AI1" -> "FI",
    "AI2" -> "SV",
    "AI3" -> "SE",
    "AI4" -> "RI",
    "AI5" -> "VK",
    "AI6" -> "XX",
    "AI7" -> "FI_2",
    "AI8" -> "SV_2",
    "AI9" -> "FI_SE",
    "AI10" -> "XX",
    "AI11" -> "FI_VK",
    "AI12" -> "SV_VK",
    "AIAI" -> "XX"
  )

  val ZERO = BigDecimal("0")

  val AIKUISTENPERUS_LUOKKAASTE = "AIK"

  val eiHalututAlle30opValmaTilat =
    Set("eronnut", "erotettu", "katsotaaneronneeksi", "mitatoity", "peruutettu", "valmistunut")

  val eronneeseenRinnastettavatKoskiTilat =
    Set("eronnut", "erotettu", "katsotaaneronneeksi", "mitatoity", "peruutettu")
}
