package fi.vm.sade.hakurekisteri.kkhakija

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorSystem, Props}
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.{HttpClientUtil, JSessionIdActor, ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.oppija.Hakukohteet
import net.liftweb.json.DefaultFormats
import net.liftweb.json.JsonParser._
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Success, Try}

class KkhakijaResourceLoadSpec extends FlatSpec with ShouldMatchers {

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
  val httpClient = HttpClientUtil.createHttpClient("kkhakija", 1, 1)
  val sessionActor = system.actorOf(Props(new JSessionIdActor()))
  val oppijaClient = new VirkailijaRestClient(oppijatConfig, Some(sessionActor))(httpClient, ec, system)

  ignore should "handle loading of all hakukohteet from haku" in {
    val hakuOid = "1.2.246.562.29.173465377510"
    val jsonString = scala.io.Source.fromFile("src/test/resources/test-hakukohteet.json").getLines().mkString
    val hakukohteet = parse(jsonString).extract[Hakukohteet]
    val hakukohdeOids = hakukohteet.results.map(_.oid)

    val count = new AtomicInteger(1)
    val batchStart = Platform.currentTime
    hakukohdeOids.foreach(h => {
      val start = Platform.currentTime
      val res: Future[Seq[Hakija]] = oppijaClient.readObject[Seq[Hakija]](s"/rest/v1/kkhakijat?haku=$hakuOid&hakukohde=$h", HttpResponseCode.Ok)
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





