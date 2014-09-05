package fi.vm.sade.hakurekisteri.integration.ytl

import scala.concurrent.Future
import fi.vm.sade.hakurekisteri.integration.ytl.YtlData._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting


class KokelasParserSpec extends FlatSpec with ShouldMatchers with FutureWaiting {

  behavior of "Kokelas parser"

  import YTLXml.parseKokelas

  it should "parse a kokelas with correct oid for successfully found oid" in {

    waitFuture(parseKokelas(Future.successful("oid"), ylioppilas)) {
      (kokelas: Kokelas) =>
        kokelas.oid should be ("oid")

    }

  }

  it should "fail for a kokelas with failed oid finding" in {
    expectFailure[NoSuchElementException](
      parseKokelas(Future.failed(new NoSuchElementException), ylioppilas))
  }



  it should "have yotutkinto parsed by yo parser" in {

    waitFuture(parseKokelas(Future.successful("oid"), ylioppilas)) {
      (kokelas: Kokelas) =>
        kokelas.yo should be (YTLXml.extractYo("oid", ylioppilas))

    }


  }

  it should "have lukio suoritus parsed by lukio parser" in {

    waitFuture(parseKokelas(Future.successful("oid"), ylioppilas)) {
      (kokelas: Kokelas) =>
        kokelas.lukio should be (YTLXml.extractLukio("oid", ylioppilas))

    }
  }


  it should "have yo todistus parsed by yo todistus parser" in {

    waitFuture(parseKokelas(Future.successful("oid"), ylioppilas)) {
      (kokelas: Kokelas) =>
        kokelas.yoTodistus should be (YTLXml.extractTodistus(kokelas.yo.get, ylioppilas))

    }
  }

}
