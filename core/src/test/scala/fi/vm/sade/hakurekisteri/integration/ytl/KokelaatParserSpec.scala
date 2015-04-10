package fi.vm.sade.hakurekisteri.integration.ytl

import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.matchers.ShouldMatchers
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import YtlData._
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.xml.Node

class KokelaatParserSpec extends FlatSpec with Matchers with FutureWaiting {

  behavior of "parsing Kokelaat"

  import YTLXml.findKokelaat

  it should "parse each kokelas like kokelas parser" in {
    val kokelaat =
      <YLIOPPILAAT>
        {ylioppilas}
        {eiValmis}
        {suorittanut}
      </YLIOPPILAAT>


    val results = for (
      parsed: Seq[Kokelas] <- Future.sequence(findKokelaat(Source.fromString(kokelaat.toString), (hetu) => Future.successful(hetu)));
      expected: Seq[Kokelas] <- Future.sequence(Seq(ylioppilas,eiValmis,suorittanut).map((k) => YtlParsing.parseKokelas(Future.successful((k \ "HENKILOTUNNUS").text),k))))
    yield (parsed, expected)

    waitFuture(results){
      case (parsed, expected) =>
        parsed should equal (expected)
    }
  }



}

object YtlParsing {

  def parseKokelas(oidFuture: Future[String], kokelas: Node)(implicit ec: ExecutionContext): Future[Kokelas] = {
    for {
      oid <- oidFuture
    } yield {
      val yo = YTLXml.extractYo(oid, kokelas)
      Kokelas(oid, yo , YTLXml.extractLukio(oid, kokelas), YTLXml.extractTodistus(yo, kokelas), YTLXml.extractOsakoe(yo, kokelas))
    }
  }
}
