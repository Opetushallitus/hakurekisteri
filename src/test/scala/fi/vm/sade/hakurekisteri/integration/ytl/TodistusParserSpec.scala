package fi.vm.sade.hakurekisteri.integration.ytl

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import YtlData._
import fi.vm.sade.hakurekisteri.integration.ytl.YTLXml.YoTutkinto
import org.joda.time.LocalDate

class TodistusParserSpec extends FlatSpec with ShouldMatchers {


  behavior of "YTL YO Todistus parser"

  import YTLXml.extractTodistus

  it should "produce one arvosana for each test" in {
    extractTodistus(tutkinto, ylioppilas).size should equal((ylioppilas \\ "KOE").size)
  }

  it should "produce correct arvosana for each test" in {
    val arvosanat = (ylioppilas \\ "KOE" \ "ARVOSANA").map(_.text)
    extractTodistus(tutkinto, ylioppilas).map(_.arvio.arvosana) should equal(arvosanat)


  }

  it should "produce correct points for each test" in {
    val pisteet = (ylioppilas \\ "KOE" \ "YHTEISPISTEMAARA").map(p => Some(p.text.toInt))
    extractTodistus(tutkinto, ylioppilas).map(_.arvio.pisteet) should equal(pisteet)


  }

  it should "produce None for points if info is missing" in {
    extractTodistus(tutkinto, eiPisteita).map(_.arvio.pisteet).head should equal(None)


  }


  it should "produce correct date for each test" in {
    val paivat = (ylioppilas \\ "KOE" \ "TUTKINTOKERTA").map((e) => YTLXml.parseKausi(e.text)).flatten
    extractTodistus(tutkinto, ylioppilas).map(_.myonnetty) should equal(paivat)
  }




}
