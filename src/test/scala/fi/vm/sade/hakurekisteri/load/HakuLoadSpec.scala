package fi.vm.sade.hakurekisteri.load

import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusHaku, ListHakemus}
import org.scalatra.test.scalatest.ScalatraFunSuite
import fi.vm.sade.authentication.cas.CasClient
import com.stackmob.newman.dsl._
import java.net.URL
import com.stackmob.newman.response.HttpResponseCode
import scala.concurrent.{Await, ExecutionContext, Future}
import com.stackmob.newman.ApacheHttpClient
import java.util.concurrent.Executors
import scala.concurrent.duration.Duration

class HakuLoadSpec extends ScalatraFunSuite {
  val serverUrl: String = "https://test-virkailija.oph.ware.fi"
  val ticketUrl: String = serverUrl + "/cas/v1/tickets"
  implicit val httpClient = new ApacheHttpClient
  implicit val ec = ExecutionContext.fromExecutorService(Executors.newSingleThreadScheduledExecutor)

  ignore("fetch 1000 applications") {
    val url = new URL(serverUrl + "/haku-app/applications/list/fullName/asc?appState=ACTIVE&orgSearchExpanded=true&checkAllApplications=false&start=0&rows=100")
    val ticket = CasClient.getTicket(ticketUrl, "robotti", "Testaaja!", serverUrl + "/haku-app/j_spring_cas_security_check")
    val x: Future[Seq[ListHakemus]] = GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {
        val hakemusHaku = response.bodyAsCaseClass[HakemusHaku].toOption
        hakemusHaku.map(_.results).getOrElse(Seq())
      } else {
        throw new RuntimeException("virhe kutsuttaessa hakupalvelua: %s".format(response.code))
      }
    })

    val urlsAndTickets: Future[Seq[(URL, String)]] = x.map(_.map((h) => {
      (new URL(serverUrl + "/haku-app/applications/" + h.oid), CasClient.getTicket(ticketUrl, "robotti", "Testaaja!", serverUrl + "/haku-app/j_spring_cas_security_check"))
    }))

    val result: Future[Seq[Long]] = urlsAndTickets.map(_.map((url) => {
      val foo = GET(url._1).addHeaders("CasSecurityTicket" -> url._2)
      val ts = System.currentTimeMillis
      Await.result(foo.apply, Duration.Inf)
      System.currentTimeMillis - ts
    }))

    val xx = Await.result(result, Duration.Inf)
    println(xx.reduce(_ + _) / xx.size)
  }

  ignore("fetch maakoodi 1000 times") {
    val maakoodit = Seq("FIN", "SVE", "ARM", "CPV", "KOR", "GIB", "YEM", "BVT")
    val urls: Seq[URL] = Stream.continually(maakoodit.toStream).flatten.take(1000).map(koodi => {
      new URL(serverUrl + "/koodisto-service/rest/json/relaatio/rinnasteinen/maatjavaltiot1_" + koodi.toLowerCase)
    })

    val result: Seq[Long] = urls.map((url) => {
      val foo = GET(url)
      val ts = System.currentTimeMillis
      Await.result(foo.apply, Duration.Inf)
      System.currentTimeMillis - ts
    })

    val ka: Long = result.reduce(_ + _) / result.size
    val kh: Double = result.map(a => (a - ka)^2).reduce(_ + _) / (result.size - 1).toDouble
    println(ka)
    println(kh)

  }

}
