package fi.vm.sade.hakurekisteri.integration.organisaatio

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.{MockCacheFactory, MockConfig}
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.Matchers
import org.scalatest.concurrent.Waiters
import org.scalatest.mockito.MockitoSugar
import org.scalatest.time.{Millis, Span}
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class OrganisaatioActorSpec
    extends ScalatraFunSuite
    with Matchers
    with Waiters
    with MockitoSugar
    with DispatchSupport
    with ActorSystemSupport
    with LocalhostProperties {

  implicit val timeout: Timeout = 60.seconds
  private var delayMillisForOrganization99999 = 0
  private var delayMillisForOrganization8888 = 0
  val organisaatioConfig = ServiceConfig(serviceUrl = "http://localhost/organisaatio-service")

  def createEndPoint(implicit ec: ExecutionContext) = {
    val e = mock[Endpoint]

    when(
      e.request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"
        )
      )
    ).thenReturn((200, List(), OrganisaatioResults.hae))
    when(e.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/99999")))
      .thenAnswer(new Answer[(Int, List[Nothing], String)]() {
        override def answer(invocation: InvocationOnMock): (Int, List[Nothing], String) = {
          Thread.sleep(delayMillisForOrganization99999)
          (200, List(), OrganisaatioResults.ysiysiysiysiysi)
        }
      })

    when(e.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127")))
      .thenReturn((200, List(), OrganisaatioResults.pikkola))
    when(
      e.request(
        forUrl("http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.16546622305")
      )
    ).thenReturn((200, List(), OrganisaatioResults.pikkola))

    when(
      e.request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.165466228888"
        )
      )
    ).thenAnswer(new Answer[(Int, List[Nothing], String)]() {
      override def answer(invocation: InvocationOnMock): (Int, List[Nothing], String) = {
        Thread.sleep(delayMillisForOrganization8888)
        (200, List(), OrganisaatioResults.kasikasikasikasi)
      }
    })

    e
  }

  test("OrganisaatioActor should contain all organisaatios after startup") {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val (endPoint, organisaatioActor) = initOrganisaatioActor()

      waitFuture((organisaatioActor ? Oppilaitos("05127")).mapTo[OppilaitosResponse])(o => {
        o.oppilaitos.oppilaitosKoodi.get should be("05127")
      })

      verify(endPoint, times(1)).request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"
        )
      )
      verify(endPoint, times(0)).request(
        forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127")
      )
    })
  }

  test("OrganisaatioActor should return organisaatio from cache using organisaatio oid") {
    1.to(10).foreach { n =>
      withSystem(implicit system => {
        implicit val ec = system.dispatcher
        val (endPoint, organisaatioActor) = initOrganisaatioActor()
        waitFuture((organisaatioActor ? "1.2.246.562.10.16546622305").mapTo[Option[Organisaatio]])(
          o => {
            o.get.oppilaitosKoodi.get should be("05127")
          }
        )

        verify(endPoint, times(1)).request(
          forUrl(
            "http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"
          )
        )
        verify(endPoint, times(0)).request(
          forUrl(
            "http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.16546622305"
          )
        )
      })
    }
  }

  test("OrganisaatioActor should return organisaatio from cache using oppilaitoskoodi") {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val (endPoint, organisaatioActor) = initOrganisaatioActor()

      waitFuture((organisaatioActor ? Oppilaitos("05127")).mapTo[OppilaitosResponse])(o => {
        o.oppilaitos.oppilaitosKoodi.get should be("05127")
      })

      verify(endPoint, times(1)).request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"
        )
      )
      verify(endPoint, times(0)).request(
        forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127")
      )
    })
  }

  test(
    "OrganisaatioActor should find organisaatio and its children from organisaatio-service if not found in cache"
  ) {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val (endPoint, organisaatioActor) = initOrganisaatioActor()

      when(
        endPoint.request(
          forUrl(
            "http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.00000000002/childoids"
          )
        )
      ).thenReturn((200, List(), """{ "oids": ["1.2.246.562.10.16546622305"] }"""))
      when(
        endPoint.request(
          forUrl(
            "http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.16546622305/childoids"
          )
        )
      ).thenReturn((200, List(), """{ "oids": [] }"""))

      waitFuture((organisaatioActor ? Oppilaitos("99999")).mapTo[OppilaitosResponse])(o => {
        o.oppilaitos.oppilaitosKoodi.get should be("99999")
        o.oppilaitos.children should have size 1
        o.oppilaitos.children.head.oid should be("1.2.246.562.10.16546622305")
      })

      verify(endPoint, times(1)).request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"
        )
      )
      verify(endPoint, times(1)).request(
        forUrl("http://localhost/organisaatio-service/rest/organisaatio/99999")
      )
      verify(endPoint, times(1)).request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.00000000002/childoids"
        )
      )
      verify(endPoint, times(1)).request(
        forUrl("http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.16546622305")
      )
      verify(endPoint, times(1)).request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.16546622305/childoids"
        )
      )
    })
  }

  test("OrganisaatioActor should cache a single koodi based result") {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val (endPoint, organisaatioActor) = initOrganisaatioActor()

      delayMillisForOrganization99999 = 150

      organisaatioActor ! Oppilaitos("99999")

      Thread.sleep(delayMillisForOrganization99999 - 50)

      1.to(10).foreach { _ => organisaatioActor ! Oppilaitos("99999") }

      delayMillisForOrganization99999 = 0

      when(
        endPoint.request(
          forUrl(
            "http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.00000000002/childoids"
          )
        )
      ).thenReturn((200, List(), """{ "oids": [] }"""))

      waitFuture((organisaatioActor ? Oppilaitos("99999")).mapTo[OppilaitosResponse])(o => {
        o.oppilaitos.oppilaitosKoodi.get should be("99999")
      })

      verify(endPoint, times(1)).request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"
        )
      )
      verify(endPoint, times(1)).request(
        forUrl("http://localhost/organisaatio-service/rest/organisaatio/99999")
      )
    })
  }

  test("OrganisaatioActor should cache a single oid based result") {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val (endPoint, organisaatioActor) = initOrganisaatioActor()

      delayMillisForOrganization8888 = 150

      when(
        endPoint.request(
          forUrl(
            "http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.165466228888/childoids"
          )
        )
      ).thenReturn((200, List(), """{ "oids": [] }"""))

      organisaatioActor ! "1.2.246.562.10.165466228888"

      Thread.sleep(delayMillisForOrganization8888 - 50)

      1.to(10).foreach { _ => organisaatioActor ! "1.2.246.562.10.165466228888" }

      delayMillisForOrganization99999 = 0

      waitFuture((organisaatioActor ? "1.2.246.562.10.165466228888").mapTo[Option[Organisaatio]])(
        o => {
          o.isDefined should be(true)
          o.get.oppilaitosKoodi.get should be("88888")
        }
      )

      verify(endPoint, times(1)).request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"
        )
      )
      verify(endPoint, times(1)).request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.165466228888"
        )
      )
    })
  }

  test("OrganisaatioActor throws exception for values not found") {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val (endPoint, organisaatioActor) = initOrganisaatioActor()

      an[PreconditionFailedException] should be thrownBy {
        waitFuture((organisaatioActor ? "inexistent").mapTo[Option[Organisaatio]])(o => {
          o.isDefined should be(false)
        })
      }

      verify(endPoint, times(1)).request(
        forUrl("http://localhost/organisaatio-service/rest/organisaatio/inexistent")
      )
    })
  }

  test("OrganisaatioActor should refresh cache") {
    withSystem(implicit system => {
      implicit val ec = system.dispatcher
      val (endPoint, organisaatioActor) = initOrganisaatioActor(Some(2.seconds))

      Thread.sleep(1500)

      verify(endPoint, atLeastOnce()).request(
        forUrl(
          "http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"
        )
      )
    })
  }

  def initOrganisaatioActor(
    ttl: Option[FiniteDuration] = None
  )(implicit system: ActorSystem, ec: ExecutionContext): (Endpoint, ActorRef) = {
    val endPoint = createEndPoint
    val organisaatioActor = system.actorOf(
      Props(
        new HttpOrganisaatioActor(
          new VirkailijaRestClient(
            config = organisaatioConfig,
            aClient = Some(new CapturingAsyncHttpClient(endPoint))
          ),
          new MockConfig,
          MockCacheFactory.get,
          initDuringStartup = false,
          ttl
        )
      )
    )

    Await.result(
      (organisaatioActor ? RefreshOrganisaatioCache).mapTo[Boolean],
      Duration(10, TimeUnit.SECONDS)
    )

    (endPoint, organisaatioActor)
  }

  def waitFuture[A](f: Future[A])(assertion: A => Unit)(implicit ec: ExecutionContext) = {
    val w = new Waiter

    f.onComplete(r => {
      w(assertion(r.get))
      w.dismiss()
    })

    w.await(timeout(Span(5000, Millis)), dismissals(1))
  }

}

object OrganisaatioResults {
  def hae(implicit ec: ExecutionContext) = {
    Await.result(Future { Thread.sleep(50) }, Duration(1, TimeUnit.SECONDS))
    scala.io.Source
      .fromURL(getClass.getResource("/mock-data/organisaatio/organisaatio-hae.json"))
      .mkString
  }
  val pikkola = scala.io.Source
    .fromURL(getClass.getResource("/mock-data/organisaatio/organisaatio-pikkola.json"))
    .mkString
  def ysiysiysiysiysi(implicit ec: ExecutionContext) = {
    Await.result(Future { Thread.sleep(10) }, Duration(1, TimeUnit.SECONDS))
    scala.io.Source
      .fromURL(getClass.getResource("/mock-data/organisaatio/organisaatio-99999.json"))
      .mkString
  }
  def kasikasikasikasi(implicit ec: ExecutionContext) = {
    scala.io.Source
      .fromURL(
        getClass.getResource(
          "/mock-data/organisaatio/organisaatio-1.2.246.562.10.165466228888.json"
        )
      )
      .mkString
  }
}
