package fi.vm.sade.hakurekisteri.kkhakija

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.oppija.Hakukohteet
import fi.vm.sade.hakurekisteri.web.kkhakija.Hakija
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods._
import org.scalatest.{FlatSpec, Matchers}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Success, Try}

class KkhakijaResourceLoadSpec extends AnyFlatSpec with Matchers {

  behavior of "kkhakijat-service"

  val system = ActorSystem("kkhakijat-service-load-test")
  implicit val formats = DefaultFormats
  implicit val ec: ExecutionContext = system.dispatcher

  val oppijatConfig = ServiceConfig(
    serviceUrl = "https://testi.virkailija.opintopolku.fi/suoritusrekisteri",
    casUrl = Some("https://testi.virkailija.opintopolku.fi/cas"),
    user = Some("robotti"),
    password = Some("Testaaja!")
  )
  val oppijaClient = new VirkailijaRestClient(oppijatConfig, None)(ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor()), system)

  ignore should "handle loading of all hakukohteet from haku" in {
    val hakuOid = "1.2.246.562.29.173465377510"
    val jsonString = scala.io.Source.fromFile("src/test/resources/test-hakukohteet.json").getLines().mkString
    val hakukohteet = parse(jsonString).extract[Hakukohteet]
    val hakukohdeOids = hakukohteet.results.map(_.oid)

    val count = new AtomicInteger(1)
    val batchStart = Platform.currentTime
    hakukohdeOids.foreach(h => {
      val start = Platform.currentTime
      val res: Future[Seq[Hakija]] = oppijaClient.readObjectFromUrl[Seq[Hakija]](s"/rest/v1/kkhakijat?haku=$hakuOid&hakukohde=$h", 200)
      res.onComplete((t: Try[Seq[Hakija]]) => {
        val end = Platform.currentTime
        val hakijas = t match {
          case Success(o) => o.size
          case _ => -1
        }
        println(s"${count.getAndIncrement} (${(end - batchStart) / 1000} seconds): took ${end - start} ms, got $hakijas hakijas")
      })
      val tulos = Await.result(res, Duration(500, TimeUnit.SECONDS))
    })

  }
}





