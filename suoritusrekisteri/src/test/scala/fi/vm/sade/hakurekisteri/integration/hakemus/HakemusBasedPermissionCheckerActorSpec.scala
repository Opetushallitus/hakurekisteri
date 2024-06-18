package fi.vm.sade.hakurekisteri.integration.hakemus

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.MockDevConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.integration.{ActorSystemSupport, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.web.rest.support.TestUser
import org.mockito.Mockito.when
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, _}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

class HakemusBasedPermissionCheckerActorSpec
    extends FlatSpec
    with Matchers
    with MockitoSugar
    with ActorSystemSupport
    with HakeneetSupport {
  private val hakuAppClient: VirkailijaRestClient = mock[VirkailijaRestClient]
  private val ataruClient: VirkailijaRestClient = mock[VirkailijaRestClient]
  private implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))
  private implicit val timeoutDuration: FiniteDuration = Duration(10, SECONDS)
  private implicit val timeout: Timeout = Timeout(timeoutDuration)

  behavior of "hakemusBasedPermissionCheckerActor"

  private val user: User = TestUser
  private val oppijanumero: String = "1.2.3.4.5.6"
  private val permissionRequest: PermissionRequest =
    PermissionRequest(List(oppijanumero), Vector("1.10.3"), List())

  it should "return false if both backend systems return false" in {
    withSystem { system =>
      when(
        hakuAppClient.postObject[PermissionRequest, PermissionResponse]("haku-app.permissioncheck")(
          200,
          permissionRequest
        )
      ).thenReturn(Future.successful(PermissionResponse(Some(false))))
      when(
        ataruClient.postObject[PermissionRequest, PermissionResponse]("ataru.permissioncheck")(
          200,
          permissionRequest
        )
      ).thenReturn(Future.successful(PermissionResponse(Some(false))))
      run(actor(system) ? HasPermission(user, oppijanumero)) should equal(false)
    }
  }

  it should "return true if Ataru returns true even if haku-app returns false" in {
    withSystem { system =>
      when(
        hakuAppClient.postObject[PermissionRequest, PermissionResponse]("haku-app.permissioncheck")(
          200,
          permissionRequest
        )
      ).thenReturn(Future.successful(PermissionResponse(Some(false))))
      when(
        ataruClient.postObject[PermissionRequest, PermissionResponse]("ataru.permissioncheck")(
          200,
          permissionRequest
        )
      ).thenReturn(Future.successful(PermissionResponse(Some(true))))
      run(actor(system) ? HasPermission(user, oppijanumero)) should equal(true)
    }
  }

  it should "return true if haku-app returns true even if Ataru returns false" in {
    withSystem { system =>
      when(
        hakuAppClient.postObject[PermissionRequest, PermissionResponse]("haku-app.permissioncheck")(
          200,
          permissionRequest
        )
      ).thenReturn(Future.successful(PermissionResponse(Some(true))))
      when(
        ataruClient.postObject[PermissionRequest, PermissionResponse]("ataru.permissioncheck")(
          200,
          permissionRequest
        )
      ).thenReturn(Future.successful(PermissionResponse(Some(false))))
      run(actor(system) ? HasPermission(user, oppijanumero)) should equal(true)
    }
  }

  it should "return true if Ataru returns true even if haku-app fails" in {
    withSystem { system =>
      when(
        hakuAppClient.postObject[PermissionRequest, PermissionResponse]("haku-app.permissioncheck")(
          200,
          permissionRequest
        )
      ).thenReturn(
        Future.failed(
          new RuntimeException("This haku-app exception should not inhibit Ataru response")
        )
      )
      when(
        ataruClient.postObject[PermissionRequest, PermissionResponse]("ataru.permissioncheck")(
          200,
          permissionRequest
        )
      ).thenReturn(Future.successful(PermissionResponse(Some(true))))
      run(actor(system) ? HasPermission(user, oppijanumero)) should equal(true)
    }
  }

  it should "return true if Ataru returns true even if Haku-app is too slow" in {
    withSystem { system =>
      when(
        hakuAppClient.postObject[PermissionRequest, PermissionResponse]("haku-app.permissioncheck")(
          200,
          permissionRequest
        )
      ).thenAnswer(new Answer[Future[PermissionResponse]] {
        override def answer(invocation: InvocationOnMock): Future[PermissionResponse] = Future {
          Thread.sleep(10 * timeout.duration.toMillis)
          throw new RuntimeException(
            "This Haku-app response should come so slow that it isn't even seen."
          )
        }
      })
      when(
        ataruClient.postObject[PermissionRequest, PermissionResponse]("ataru.permissioncheck")(
          200,
          permissionRequest
        )
      ).thenReturn(Future.successful(PermissionResponse(Some(true))))
      run(actor(system) ? HasPermission(user, oppijanumero)) should equal(true)
    }
  }

  private def actor(system: ActorSystem): ActorRef = {
    system.actorOf(
      Props(
        new HakemusBasedPermissionCheckerActor(
          hakuAppClient,
          ataruClient,
          organisaatioActor,
          new MockDevConfig
        )
      )
    )
  }

  private def run[T](future: Future[T])(implicit timeout: FiniteDuration): T =
    Await.result(future, timeout)
}
