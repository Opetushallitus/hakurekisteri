package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorSystem, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import org.scalatest.{Matchers, FlatSpec}

import scala.compat.Platform
import scala.concurrent.duration._
import akka.pattern.ask

import scala.concurrent.{Await, Future, ExecutionContext}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods._

class ValintaTulosLoadSpec extends FlatSpec with Matchers {

  behavior of "valinta-tulos-service"

  val system = ActorSystem("valinta-tulos-load-test")
  implicit val formats = DefaultFormats
  implicit val timeout: Timeout = 120.seconds
  implicit val ec: ExecutionContext = system.dispatcher

  val valintaTulosConfig = ServiceConfig(serviceUrl = "https://localhost:33000/valinta-tulos-service")
  val valintaTulos = system.actorOf(Props(new ValintaTulosActor(new VirkailijaRestClient(valintaTulosConfig)(ec, system))), "valintaTulos")

  ignore should "handle loading the status of 5000 applications" in {
    val jsonString = scala.io.Source.fromFile("src/test/resources/test-applications.json").mkString
    val applications = parse(jsonString).extract[Applications]
    val hakemusOids = applications.results

    val count = new AtomicInteger(1)
    val batchStart = Platform.currentTime
    hakemusOids.foreach(h => {
      val start = Platform.currentTime
      val res: Future[ValintaTulos] = (valintaTulos ? ValintaTulosQuery("1.2.246.562.29.173465377510", Some(h.oid), cachedOk = true)).mapTo[ValintaTulos]
      res.onComplete(t => {
        val end = Platform.currentTime
        println(s"${count.getAndIncrement} (${(end - batchStart) / 1000} seconds): took ${end - start} ms")
      })
      val tulos = Await.result(res, Duration(120, TimeUnit.SECONDS))
    })
  }
}

case class Application(oid: String)
case class Applications(results: Seq[Application])

