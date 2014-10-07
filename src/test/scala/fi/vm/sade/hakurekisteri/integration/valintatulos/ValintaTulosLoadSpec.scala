package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.util.concurrent.{TimeUnit, ThreadFactory, Executors}
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ActorSystem, Props}
import akka.util.Timeout
import com.stackmob.newman.{ApacheHttpClient, HttpClient}
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import org.apache.http.conn.ClientConnectionManager
import org.apache.http.conn.scheme.{Scheme, SchemeRegistry}
import org.apache.http.conn.ssl.SSLSocketFactory
import org.apache.http.impl.NoConnectionReuseStrategy
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.impl.conn.PoolingClientConnectionManager
import org.apache.http.params.HttpConnectionParams
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

import net.liftweb.json.JsonParser._
import net.liftweb.json.DefaultFormats
import scala.compat.Platform
import scala.concurrent.duration._
import akka.pattern.ask

import scala.concurrent.{Await, Future, ExecutionContext}

class ValintaTulosLoadSpec extends FlatSpec with ShouldMatchers {

  behavior of "valinta-tulos-service"

  val system = ActorSystem("valinta-tulos-load-test")
  implicit val formats = DefaultFormats
  implicit val timeout: Timeout = 120.seconds
  implicit val ec: ExecutionContext = system.dispatcher

  val valintaTulosConfig = ServiceConfig(serviceUrl = "https://localhost:33000/valinta-tulos-service")
  val valintaTulos = system.actorOf(Props(new ValintaTulosActor(new VirkailijaRestClient(valintaTulosConfig)(TestClient.getClient, ec))), "valintaTulos")

  ignore should "handle loading the status of 5000 applications" in {
    val jsonString = scala.io.Source.fromFile("src/test/resources/test-applications.json").getLines.mkString
    val applications = parse(jsonString).extract[Applications]
    val hakemusOids = applications.results

    val count = new AtomicInteger(1)
    val batchStart = Platform.currentTime
    hakemusOids.foreach(h => {
      val start = Platform.currentTime
      val res: Future[ValintaTulos] = (valintaTulos ? ValintaTulosQuery("1.2.246.562.29.173465377510", h.oid)).mapTo[ValintaTulos]
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

object TestClient {
  def getClient: HttpClient = getClient("default")

  val socketTimeout = 120000
  val connectionTimeout = 15000

  def createApacheHttpClient(maxConnections: Int): org.apache.http.client.HttpClient = {
    val connManager: ClientConnectionManager = {
      val registry = new SchemeRegistry()
      val socketFactory = SSLSocketFactory.getSocketFactory()
      val hostnameVerifier = org.apache.http.conn.ssl.SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER
      socketFactory.setHostnameVerifier(hostnameVerifier)
      registry.register(new Scheme("https", 443, socketFactory))

      val cm = new PoolingClientConnectionManager(registry)
      cm.setDefaultMaxPerRoute(maxConnections)
      cm.setMaxTotal(maxConnections)
      cm
    }

    val client = new DefaultHttpClient(connManager)
    val httpParams = client.getParams
    HttpConnectionParams.setConnectionTimeout(httpParams, connectionTimeout)
    HttpConnectionParams.setSoTimeout(httpParams, socketTimeout)
    HttpConnectionParams.setStaleCheckingEnabled(httpParams, false)
    HttpConnectionParams.setSoKeepalive(httpParams, false)
    client.setReuseStrategy(new NoConnectionReuseStrategy())
    client
  }

  def getClient(poolName: String = "default", threads: Int = 10, maxConnections: Int = 100): HttpClient = {
    if (poolName == "default") new ApacheHttpClient(createApacheHttpClient(maxConnections))()
    else {
      val threadNumber = new AtomicInteger(1)
      val pool = Executors.newFixedThreadPool(threads, new ThreadFactory() {
        override def newThread(r: Runnable): Thread = {
          new Thread(r, poolName + "-" + threadNumber.getAndIncrement)
        }
      })
      new ApacheHttpClient(createApacheHttpClient(maxConnections))(ExecutionContext.fromExecutorService(pool))
    }
  }
}