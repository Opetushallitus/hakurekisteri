package fi.vm.sade.hakurekisteri.integration.ytl

import org.json4s.jackson.JsonMethods._
import org.scalatest.{Matchers, FlatSpec}
import YtlData._
import org.json4s._
import org.json4s.DefaultFormats._
//import org.json4s.native.JsonMethods._
import org.json4s.jackson.Serialization.{read, write, writePretty}
import org.json4s.jackson.Serialization
class TodistusParserSpec extends FlatSpec with Matchers {


  behavior of "YTL YO Todistus parser"

  import YTLXml.{extractTodistus,extractOsakoe}

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

  it should "include osakoe arvosanat" in {
    val osakokeet = (ylioppilas \\ "KOE" \ "OSAKOKEET" \ "OSAKOE" \ "OSAKOETUNNUS").map(_.text)

    extractOsakoe(tutkinto, ylioppilas).map(_.osakoetunnus) should contain theSameElementsAs osakokeet
  }


}
