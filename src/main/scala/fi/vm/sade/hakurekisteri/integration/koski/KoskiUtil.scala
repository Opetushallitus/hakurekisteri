package fi.vm.sade.hakurekisteri.integration.koski

import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat

import scala.math.BigDecimal

object KoskiUtil {

  val koski_integration_source = "koski"
  var deadlineDate: LocalDate = new LocalDate(OphUrlProperties.getProperty("suoritusrekisteri.koski.deadline.date"))

  def isAfterArvosanatWithNelosiaDeadlineDate(): Boolean = {
    // Neloset halutaan tallentaa suoritusrekisteriin kaksi viikkoa ennen deadline-päivämäärää
    LocalDate.now().isAfter(deadlineDate.minusDays(14))
  }

  def isAfterDeadlineDate(date: LocalDate = LocalDate.now()): Boolean = {
    date.isAfter(deadlineDate)
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
  val oppiaineet = Set( "HI", "MU", "BI", "KT", "FI", "KO", "KE", "YH", "TE", "KS", "FY", "GE", "LI", "KU", "MA")
  val eivalinnaiset = kielet ++ oppiaineet ++ Set("AI")
  val peruskoulunaineet = kielet ++ oppiaineet ++ Set("AI")
  val lukioaineet = peruskoulunaineet ++ Set("PS") //lukio has psychology as a mandatory subject
  val lukioaineetRegex = lukioaineet.map(_.r)

  val kieletRegex = kielet.map(str => str.r)
  val oppiaineetRegex = oppiaineet.map(str => s"$str\\d?".r)
  val peruskouluaineetRegex = kieletRegex ++ oppiaineetRegex ++ Set("AI".r)

  val peruskoulunArvosanat = Set[String]("4", "5", "6", "7", "8", "9", "10")
  val aidinkieli = Map("AI1" -> "FI", "AI2" -> "SV", "AI3" -> "SE", "AI4" -> "RI", "AI5" -> "VK", "AI6" -> "XX", "AI7" -> "FI_2", "AI8" -> "SV_2", "AI9" -> "FI_SE", "AI10" -> "XX", "AI11" -> "FI_VK", "AI12" -> "SV_VK", "AIAI" -> "XX")

  val ZERO = BigDecimal("0")

  val AIKUISTENPERUS_LUOKKAASTE = "AIK"

  val eiHalututAlle30opValmaTilat: Seq[String] = Seq("eronnut", "erotettu", "katsotaaneronneeksi" ,"mitatoity", "peruutettu", "valmistunut")
}
