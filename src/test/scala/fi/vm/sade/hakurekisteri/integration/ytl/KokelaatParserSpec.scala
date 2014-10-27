package fi.vm.sade.hakurekisteri.integration.ytl

import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.matchers.ShouldMatchers
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import YtlData._
import scala.concurrent.Future

class KokelaatParserSpec extends FlatSpec with Matchers with FutureWaiting {

  behavior of "parsing Kokelaat"

  import YTLXml.parseKokelaat

  it should "parse each kokelas like kokelas parser" in {
    val kokelaat =
      <YLIOPPILAAT>
        {ylioppilas}
        {eiValmis}
        {suorittanut}
      </YLIOPPILAAT>

    val results = for (
      parsed: Seq[Kokelas] <- Future.sequence(parseKokelaat(kokelaat, (_) => Future.successful("oid")));
      expected: Seq[Kokelas] <- Future.sequence(Seq(ylioppilas,eiValmis,suorittanut).map(YTLXml.parseKokelas(Future.successful("oid"),_))))
    yield (parsed, expected)

    waitFuture(results){
      case (parsed, expected) => parsed should equal (expected)
    }
  }

}
