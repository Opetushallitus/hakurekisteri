package fi.vm.sade.hakurekisteri.oppija

import java.util.concurrent.{TimeUnit, Executors}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.integration.{JSessionIdActor, ServiceConfig, VirkailijaRestClient}
import org.scalatest.{Matchers, FlatSpec}

import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods._
import scala.compat.Platform
import scala.concurrent.duration._

import scala.concurrent.{Await, Future, ExecutionContext}
import scala.util.{Success, Try}

class OppijaResourceLoadSpec extends FlatSpec with Matchers {

  behavior of "oppijat-service"

  val system = ActorSystem("oppijat-service-load-test")
  implicit val formats = DefaultFormats
  implicit val ec: ExecutionContext = system.dispatcher

  val oppijatConfig = ServiceConfig(
    serviceUrl = "https://testi.virkailija.opintopolku.fi/suoritusrekisteri",
    casUrl = Some("https://testi.virkailija.opintopolku.fi/cas"),
    user = Some("robotti"),
    password = Some("Testaaja!")
  )
  val sessionActor = system.actorOf(Props(new JSessionIdActor()))
  val oppijaClient = new VirkailijaRestClient(oppijatConfig, Some(sessionActor))( ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()), system)

  ignore should "handle loading of all hakukohteet from haku" in {
    val hakuOid = "1.2.246.562.29.173465377510"
    val jsonString = scala.io.Source.fromFile("src/test/resources/test-hakukohteet.json").getLines().mkString
    val hakukohteet = parse(jsonString).extract[Hakukohteet]
    val hakukohdeOids = hakukohteet.results.map(_.oid)

    val count = new AtomicInteger(1)
    val batchStart = Platform.currentTime
    hakukohdeOids.foreach(h => {
      val start = Platform.currentTime
      val res: Future[Seq[Oppija]] = oppijaClient.readObject[Seq[Oppija]](s"/rest/v1/oppijat?haku=$hakuOid&hakukohde=$h", 200)
      res.onComplete((t: Try[Seq[Oppija]]) => {
        val end = Platform.currentTime
        val oppijas = t match {
          case Success(o) => o.size
          case _ => -1
        }
        println(s"${count.getAndIncrement} (${(end - batchStart) / 1000} seconds): took ${end - start} ms, got $oppijas oppijas")
      })
      val tulos = Await.result(res, Duration(500, TimeUnit.SECONDS))
    })

  }
}

case class Hakukohteet(results: Seq[Hakukohde])

case class Hakukohde(oid: String)

