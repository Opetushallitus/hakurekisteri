package fi.vm.sade.hakurekisteri.integration.parametrit

import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.MockCacheFactory
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.scalaproperties.OphProperties
import org.joda.time.DateTime
import org.mockito.Mockito._
import org.scalatest.Matchers
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._

class ParameterActorSpec extends ScalatraFunSuite with Matchers with AsyncAssertions with MockitoSugar with DispatchSupport with ActorSystemSupport with FutureWaiting with LocalhostProperties {

  implicit val timeout: Timeout = 60.seconds
  val parameterConfig = ServiceConfig(serviceUrl = "http://localhost/ohjausparametrit-service")
  val cacheFactory = MockCacheFactory.get

  def createEndPoint = {
    val e = mock[Endpoint]

    when(e.request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/ALL"))).thenReturn((200, List(), ParameterResults.all))
    when(e.request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/tiedonsiirtosendingperiods"))).thenReturn((200, List(), ParameterResults.tiedonsiirto))
    when(e.request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/restrictedperiods"))).thenReturn((200, List(), ParameterResults.restrictions))
    e
  }

  test("ParameterActor should return hakukierros end date when it exists") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val parameterActor = system.actorOf(Props(new HttpParameterActor(
          new VirkailijaRestClient(config = parameterConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))), cacheFactory)))

        waitFuture((parameterActor ? KierrosRequest("1.2.246.562.29.32820950486")).mapTo[HakuParams])(h => {
          h.end should be (new DateTime("2014-12-31T14:07:23.213+02:00"))
        })

        verify(endPoint, times(1)).request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/ALL"))
      }
    )
  }

  test("ParameterActor multiple hakukierros requests should not populate more than one request to the rest service") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val parameterActor = system.actorOf(Props(new HttpParameterActor(
          new VirkailijaRestClient(config = parameterConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))), cacheFactory)))

        waitFuture((parameterActor ? KierrosRequest("1.2.246.562.29.32820950486")).mapTo[HakuParams])(h => {
          h.end should be (new DateTime("2014-12-31T14:07:23.213+02:00"))
        })

        waitFuture((parameterActor ? KierrosRequest("1.2.246.562.29.32820950486")).mapTo[HakuParams])(h => {
          h.end should be (new DateTime("2014-12-31T14:07:23.213+02:00"))
        })

        waitFuture((parameterActor ? KierrosRequest("1.2.246.562.29.32820950486")).mapTo[HakuParams])(h => {
          h.end should be (new DateTime("2014-12-31T14:07:23.213+02:00"))
        })

        verify(endPoint, times(1)).request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/ALL"))
      }
    )
  }

  test("ParameterActor should return failure if end date does not exist") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val parameterActor = system.actorOf(Props(new HttpParameterActor(
          new VirkailijaRestClient(config = parameterConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))), cacheFactory)))

        expectFailure[NoParamFoundException](parameterActor ? KierrosRequest("1.2.246.562.29.43114244536"))

        verify(endPoint, times(1)).request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/ALL"))
      }
    )
  }

  test("ParameterActor should return failure if haku oid does not exists") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val parameterActor = system.actorOf(Props(new HttpParameterActor(
          new VirkailijaRestClient(config = parameterConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))),
          CacheFactory.apply(new OphProperties().addDefault("redis_suoritusrekisteri_enabled", "false")))))

        expectFailure[NoParamFoundException](parameterActor ? KierrosRequest("1.2.246.562.29.foobar"))

        verify(endPoint, times(1)).request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/ALL"))
      }
    )
  }

  test("ParameterActor should return true for tiedonsiirto period query") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val parameterActor = system.actorOf(Props(new HttpParameterActor(
          new VirkailijaRestClient(config = parameterConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))), cacheFactory)))

        waitFuture((parameterActor ? IsSendingEnabled("perustiedot")).mapTo[Boolean])(b => {
          b should be (true)
        })

        verify(endPoint, times(1)).request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/tiedonsiirtosendingperiods"))
      }
    )
  }

  test("ParameterActor should return true for restriction with active restriction period") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val parameterActor = system.actorOf(Props(new HttpParameterActor(
          new VirkailijaRestClient(config = parameterConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))), cacheFactory)))

        waitFuture((parameterActor ? IsRestrictionActive("opoUpdateGraduation")).mapTo[Boolean])(b => {
          b should be (true)
        })

        verify(endPoint, times(1)).request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/restrictedperiods"))
      }
    )
  }

  test("ParameterActor should return false for restriction with inactive restriction period") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val parameterActor = system.actorOf(Props(new HttpParameterActor(
          new VirkailijaRestClient(config = parameterConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))), cacheFactory)))

        waitFuture((parameterActor ? IsRestrictionActive("inactiveRestriction")).mapTo[Boolean])(b => {
          b should be (false)
        })

        verify(endPoint, times(1)).request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/restrictedperiods"))
      }
    )
  }


  test("ParameterActor should cache tiedonsiirto period query results") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val parameterActor = system.actorOf(Props(new HttpParameterActor(
          new VirkailijaRestClient(config = parameterConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))), cacheFactory)))

        waitFuture((parameterActor ? IsSendingEnabled("perustiedot")).mapTo[Boolean])(b => {
          b should be (true)
        })

        waitFuture((parameterActor ? IsSendingEnabled("perustiedot")).mapTo[Boolean])(b => {
          b should be (true)
        })

        waitFuture((parameterActor ? IsSendingEnabled("perustiedot")).mapTo[Boolean])(b => {
          b should be (true)
        })

        verify(endPoint, times(1)).request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/tiedonsiirtosendingperiods"))
      }
    )
  }

}

object ParameterResults {
  val all = scala.io.Source.fromURL(getClass.getResource("/mock-data/parametrit/parametrit-all.json")).mkString
  val restrictions = scala.io.Source.fromURL(getClass.getResource("/mock-data/parametrit/restrictions-opoUpdateGraduation.json")).mkString
  val tiedonsiirto = scala.io.Source.fromURL(getClass.getResource("/mock-data/parametrit/parametrit-tiedonsiirtosendingperiods.json")).mkString
}