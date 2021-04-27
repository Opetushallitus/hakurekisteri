package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.util.concurrent.locks.ReentrantLock

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.haku.{
  AllHaut,
  Haku,
  HakuRequest,
  RestHaku,
  RestHakuAika
}
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.KESKEN
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.scalaproperties.OphProperties
import fi.vm.sade.utils.tcp.PortChecker
import org.joda.time.DateTime
import org.json4s.Formats
import org.mockito.Mockito
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite
import redis.embedded.RedisServer

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutorService, Future}

class ValintaTulosActorWithRedisSpec
    extends ScalatraFunSuite
    with FutureWaiting
    with DispatchSupport
    with MockitoSugar
    with ActorSystemSupport
    with LocalhostProperties
    with BeforeAndAfterAll {

  val rPort: Int = PortChecker.findFreeLocalPort
  val redisServer: RedisServer = new RedisServer(rPort)

  implicit val timeout: Timeout = 60.seconds
  val vtsConfig = ServiceConfig(serviceUrl = "http://localhost/valinta-tulos-service")
  val config = new MockConfig
  private def mockHakuActor = new Actor {
    override def receive: Receive = { case HakuRequest =>
      sender ! AllHaut(Seq.empty)
    }
  }

  def cacheFactory(implicit system: ActorSystem) = CacheFactory.apply(
    new OphProperties()
      .addDefault("suoritusrekisteri.cache.redis.enabled", "true")
      .addDefault("suoritusrekisteri.cache.redis.host", "localhost")
      .addDefault("suoritusrekisteri.cache.redis.numberOfWaitersToLog", "5")
      .addDefault("suoritusrekisteri.cache.redis.cacheHandlingThreadPoolSize", "3")
      .addDefault("suoritusrekisteri.cache.redis.slowRedisRequestThresholdMillis", "0")
      .addDefault("suoritusrekisteri.cache.redis.port", s"${rPort}")
  )(system)

  def createEndPoint = {
    val e = mock[Endpoint]

    when(e.request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.broken")))
      .thenReturn((500, List(), ""))
    when(
      e.request(
        forPattern("http://localhost/valinta-tulos-service/haku/1\\.2\\.246\\.562\\.29\\.[0-9]+")
      )
    )
      .thenReturn(
        (
          200,
          List(),
          scala.io.Source
            .fromURL(getClass.getResource("/mock-data/valintatulos/valintatulos-haku.json"))
            .mkString
        )
      )

    e
  }

  override def beforeAll() = {
    redisServer.start
  }

  test(
    "ValintaTulosActor should fire only one request to the backend even when asked multiple times"
  ) {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val endPoint = createEndPoint
      val valintaTulosActor = system.actorOf(
        Props(
          new ValintaTulosActor(
            config = config,
            cacheFactory = cacheFactory,
            client = new VirkailijaRestClient(
              config = vtsConfig,
              aClient = Some(new CapturingAsyncHttpClient(endPoint))
            ),
            hautActor = system.actorOf(Props(mockHakuActor))
          )
        )
      )

      valintaTulosActor ! HaunValintatulos("1.2.246.562.29.90697286251")
      waitFuture(querySijoitteluTulos(valintaTulosActor, "1.2.246.562.29.90697286251"))(t => {
        t.valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029")
          .toString should be(KESKEN.toString)
      })
      Thread.sleep(500)

      val cached = Await.result(
        cacheFactory
          .getInstance[String, SijoitteluTulos](
            1111,
            classOf[ValintaTulosActor],
            classOf[SijoitteluTulos],
            "sijoittelu-tulos"
          )
          .get(
            "1.2.246.562.29.90697286251",
            (_: String) => Future.failed(new RuntimeException("should not be called"))
          ),
        10.seconds
      )
      cached.get
        .valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029")
        .toString should be(KESKEN.toString)

      verify(endPoint, times(1)).request(
        forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286251")
      )
    })
  }

  test("ValintaTulosActor should update cache periodically") {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val endPoint = createEndPoint
      val valintaTulosActor = system.actorOf(
        Props(
          new ValintaTulosActor(
            config = config,
            cacheFactory = cacheFactory,
            client = new VirkailijaRestClient(
              config = vtsConfig,
              aClient = Some(new CapturingAsyncHttpClient(endPoint))
            ),
            hautActor = system.actorOf(Props(new Actor {
              override def receive: Receive = { case HakuRequest =>
                sender ! AllHaut(
                  Seq(
                    Haku(
                      RestHaku(
                        Some("1.2.246.562.29.90697286252"),
                        List(
                          RestHakuAika(
                            DateTime.now().minusDays(1).getMillis,
                            None
                          )
                        ),
                        Map(),
                        "",
                        "",
                        2000,
                        None,
                        None,
                        None,
                        None,
                        ""
                      )
                    )(DateTime.now().plusDays(1).toInstant)
                  )
                )
              }
            })),
            cacheTime = Some(2000)
          )
        )
      )
      Thread.sleep(2000)
      verify(endPoint, atLeastOnce()).request(
        forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286252")
      )
      val cached = Await.result(
        cacheFactory
          .getInstance[String, SijoitteluTulos](
            1111,
            classOf[ValintaTulosActor],
            classOf[SijoitteluTulos],
            "sijoittelu-tulos"
          )
          .get(
            "1.2.246.562.29.90697286252",
            (_: String) => Future.failed(new RuntimeException("should not be called"))
          ),
        10.seconds
      )
      cached.get
        .valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029")
        .toString should be(KESKEN.toString)
    })
  }

  test("ValintaTulosActor should use cached result also during refetch") {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val endPointLock = new ReentrantLock()
      val endPoint = mock[Endpoint]
      when(
        endPoint.request(
          forPattern("http://localhost/valinta-tulos-service/haku/1\\.2\\.246\\.562\\.29\\.[0-9]+")
        )
      )
        .thenAnswer((_: InvocationOnMock) => {
          try {
            endPointLock.lock()
            (
              200,
              List(),
              scala.io.Source
                .fromURL(getClass.getResource("/mock-data/valintatulos/valintatulos-haku.json"))
                .mkString
            )
          } finally {
            endPointLock.unlock()
          }
        })
      val valintaTulosActor = system.actorOf(
        Props(
          new ValintaTulosActor(
            config = config,
            cacheFactory = cacheFactory,
            client = new VirkailijaRestClient(
              config = vtsConfig,
              aClient = Some(new CapturingAsyncHttpClient(endPoint))
            ),
            hautActor = system.actorOf(Props(mockHakuActor)),
            cacheTime = Some(10000)
          )
        )
      )

      waitFuture(querySijoitteluTulos(valintaTulosActor, "1.2.246.562.29.90697286253"))(t => {
        t.valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029")
          .toString should be(KESKEN.toString)
      })
      verify(endPoint, times(1)).request(
        forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286253")
      )
      endPointLock.lock()
      valintaTulosActor ! AllHaut(
        Seq(
          Haku(
            RestHaku(
              Some("1.2.246.562.29.90697286253"),
              List(
                RestHakuAika(
                  DateTime.now().minusDays(1).getMillis,
                  None
                )
              ),
              Map(),
              "",
              "",
              2000,
              None,
              None,
              None,
              None,
              ""
            )
          )(DateTime.now().plusDays(1).toInstant)
        )
      )
      Thread.sleep(500)
      waitFuture(querySijoitteluTulos(valintaTulosActor, "1.2.246.562.29.90697286253"))(t => {
        t.valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029")
          .toString should be(KESKEN.toString)
      })
      endPointLock.unlock()
    })
  }

  test("ValintaTulosActor should refetch if request fails") {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val endPoint = createEndPoint
      val valintaTulosActor = system.actorOf(
        Props(
          new ValintaTulosActor(
            config = config,
            cacheFactory = cacheFactory,
            client = new VirkailijaRestClient(
              config = vtsConfig,
              aClient = Some(new CapturingAsyncHttpClient(endPoint))
            ),
            hautActor = system.actorOf(Props(mockHakuActor)),
            cacheTime = Some(1000)
          )
        )
      )

      expectFailure[PreconditionFailedException](
        querySijoitteluTulos(valintaTulosActor, "1.2.246.562.29.broken")
      )
      verify(endPoint, Mockito.atLeast(2))
        .request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.broken"))
    })
  }

  test("ValintaTulosActor should load even if data is already in redis") {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val endPoint = createEndPoint
      val valintaTulosActor = system.actorOf(
        Props(
          new ValintaTulosActor(
            config = config,
            cacheFactory = cacheFactory,
            client = new VirkailijaRestClient(
              config = vtsConfig,
              aClient = Some(new CapturingAsyncHttpClient(endPoint))
            ),
            hautActor = system.actorOf(Props(mockHakuActor)),
            cacheTime = Some(10000)
          )
        )
      )
      val cache = cacheFactory.getInstance[String, SijoitteluTulos](
        1111,
        classOf[ValintaTulosActor],
        classOf[SijoitteluTulos],
        "sijoittelu-tulos"
      )
      waitFuture(querySijoitteluTulos(valintaTulosActor, "1.2.246.562.29.90697286253"))(t => {
        t.valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029")
          .toString should be(KESKEN.toString)
      })
      Await.result(cache.contains("1.2.246.562.29.90697286253"), 1.second) should be(true)
      verify(endPoint, never()).request(
        forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286253")
      )
      valintaTulosActor ! AllHaut(
        Seq(
          Haku(
            RestHaku(
              Some("1.2.246.562.29.90697286253"),
              List(
                RestHakuAika(
                  DateTime.now().minusDays(1).getMillis,
                  None
                )
              ),
              Map(),
              "",
              "",
              2000,
              None,
              None,
              None,
              None,
              ""
            )
          )(DateTime.now().plusDays(1).toInstant)
        )
      )
      Thread.sleep(500)
      verify(endPoint, times(1)).request(
        forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286253")
      )
    })
  }

  test("ValintaTulosActor should answer correctly also during cache update") {
    withSystem(implicit system => {
      implicit val formats: Formats = HakurekisteriJsonSupport.format
      implicit val ec: ExecutionContextExecutorService =
        ExecutorUtil.createExecutor(10, classOf[ValintaTulosActorWithRedisSpec].getSimpleName)
      val endPoint = createEndPoint
      val valintaTulosActor = system.actorOf(
        Props(
          new ValintaTulosActor(
            config = config,
            cacheFactory = cacheFactory,
            client = new VirkailijaRestClient(
              config = vtsConfig,
              aClient = Some(new CapturingAsyncHttpClient(endPoint))
            ),
            hautActor = system.actorOf(Props(mockHakuActor))
          )
        )
      )

      val hakuOid = "1.2.246.562.29.70576649506"
      val tulokset: Seq[(Int, Future[SijoitteluTulos])] =
        1.to(10).map((_, querySijoitteluTulos(valintaTulosActor, hakuOid)))
      tulokset.foreach { case (_, f) =>
        Await
          .result(f, 5.seconds)
          .valintatila
          .get("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029") should be(Some(KESKEN))
      }

      verify(endPoint, times(1)).request(
        forUrl("http://localhost/valinta-tulos-service/haku/" + hakuOid)
      )
    })
  }

  private def querySijoitteluTulos(valintaTulosActor: ActorRef, hakuOid: String) =
    (valintaTulosActor ? HaunValintatulos(hakuOid)).mapTo[SijoitteluTulos]

  override def afterAll(): Unit = {
    redisServer.stop()
  }
}
