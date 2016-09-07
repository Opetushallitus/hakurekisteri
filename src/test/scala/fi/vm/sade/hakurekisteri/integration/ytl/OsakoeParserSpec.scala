package fi.vm.sade.hakurekisteri.integration.ytl

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.arvosana.ArvioOsakoe
import org.joda.time.LocalDate
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

class OsakoeParserSpec extends FlatSpec with Matchers {

  behavior of "YTL Osakoe Parser"


  it should "parse osakoe from YTL xml" in {
    implicit val system = ActorSystem("ytl-osakoe-test")
    implicit val ec: ExecutionContext = system.dispatcher

    YtlExtractor.parseFile(getClass.getResource("/ytl-osakoe-test.xml").getFile).head.osakokeet should contain (Osakoe(ArvioOsakoe("52"), "BB", "HM1", "21", new LocalDate(2013, 12, 21)))

    Await.result(system.terminate(), 15.seconds)
  }

}
