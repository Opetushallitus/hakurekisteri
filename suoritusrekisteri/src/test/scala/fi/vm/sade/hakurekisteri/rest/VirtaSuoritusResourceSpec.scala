package fi.vm.sade.hakurekisteri.rest

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.integration.hakemus.{
  HakemusBasedPermissionCheckerActorRef,
  HasPermission
}
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  Henkilo,
  IOppijaNumeroRekisteri,
  LinkedHenkiloOids,
  PersonOidsWithAliases
}
import fi.vm.sade.hakurekisteri.integration.virta.{
  VirtaClient,
  VirtaResourceActor,
  VirtaResourceActorRef,
  VirtaResults
}
import fi.vm.sade.hakurekisteri.integration.{
  CapturingAsyncHttpClient,
  DispatchSupport,
  Endpoint,
  ExecutorUtil
}
import fi.vm.sade.hakurekisteri.web.integration.virta.VirtaSuoritusResource
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.mockito.Mockito
import org.scalatestplus.mockito.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Future

class VirtaSuoritusResourceSpec extends ScalatraFunSuite with DispatchSupport with MockitoSugar {
  implicit val system = ActorSystem()
  implicit val clientEc = ExecutorUtil.createExecutor(1, "virta-resource-test-pool")
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val security: Security = new SuoritusResourceTestSecurity
  private val mockConfig: MockConfig = new MockConfig

  import Mockito._

  val endPoint = mock[Endpoint]

  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.4")
    )
  ).thenReturn((200, List(), VirtaResults.emptyResp))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("111111-1976")
    )
  ).thenReturn((200, List(), VirtaResults.emptyResp))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.5")
    )
  ).thenReturn((500, List(), "Internal Server Error"))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.3.0")
    )
  ).thenReturn((200, List(), VirtaResults.multipleStudents))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.5.0")
    )
  ).thenReturn((500, List(), VirtaResults.fault))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.3")
    )
  ).thenReturn((200, List(), VirtaResults.testResponse))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.9")
    )
  ).thenReturn((200, List(), VirtaResults.opiskeluoikeustyypit))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("111111-1975")
    )
  ).thenReturn((200, List(), VirtaResults.testResponse))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.106")
    )
  ).thenReturn((200, List(), VirtaResults.testResponse106))

  val virtaClient = new VirtaClient(aClient = Some(new CapturingAsyncHttpClient(endPoint)))
  val virtaSuoritusActor = new VirtaResourceActorRef(
    system.actorOf(Props(new VirtaResourceActor(virtaClient, mockConfig)))
  )

  val permissionChecker = new HakemusBasedPermissionCheckerActorRef(TestActorRef(new Actor {
    override def receive: Receive = { case d: HasPermission =>
      sender ! true
    }
  }))

  val fakeOppijaNumeroRekisteri = new IOppijaNumeroRekisteri {
    override def fetchLinkedHenkiloOidsMap(henkiloOids: Set[String]): Future[LinkedHenkiloOids] = {
      throw new UnsupportedOperationException("Not implemented")
    }
    override def getByHetu(hetu: String): Future[Henkilo] = {
      if (hetu.equals("111111-1975")) {
        Future.successful(
          Henkilo(
            oidHenkilo = "1.2.4",
            hetu = Some("111111-1975"),
            kaikkiHetut = Some(List("111111-1975")),
            etunimet = None,
            kutsumanimi = None,
            sukunimi = None,
            aidinkieli = None,
            kansalaisuus = List.empty,
            syntymaaika = None,
            sukupuoli = None,
            turvakielto = Some(false)
          )
        )
      } else {
        Future.successful(
          Henkilo(
            oidHenkilo = "1.2.4",
            hetu = Some("111111-1976"),
            kaikkiHetut = Some(List("111111-1976")),
            etunimet = None,
            kutsumanimi = None,
            sukunimi = None,
            aidinkieli = None,
            kansalaisuus = List.empty,
            syntymaaika = None,
            sukupuoli = None,
            turvakielto = Some(false)
          )
        )
      }
    }

    override def enrichWithAliases(henkiloOids: Set[String]): Future[PersonOidsWithAliases] = {
      fetchLinkedHenkiloOidsMap(henkiloOids)
        .map(_.oidToLinkedOids)
        .map(PersonOidsWithAliases(henkiloOids, _))
    }

    override def getByOids(oids: Set[String]): Future[Map[String, Henkilo]] = Future.successful(
      Map(
        (
          "1.2.3",
          Henkilo(
            oidHenkilo = "1.2.3",
            hetu = Some("111111-1975"),
            kaikkiHetut = Some(List("111111-1975")),
            etunimet = None,
            kutsumanimi = None,
            sukunimi = None,
            aidinkieli = None,
            kansalaisuus = List.empty,
            syntymaaika = None,
            sukupuoli = None,
            turvakielto = Some(false)
          )
        )
      )
    )

    override def fetchHenkilotInBatches(henkiloOids: Set[String]): Future[Map[String, Henkilo]] =
      ???
  }

  addServlet(
    new VirtaSuoritusResource(virtaSuoritusActor, permissionChecker, fakeOppijaNumeroRekisteri),
    "/*"
  )

  test("should return required fields from Virta for empty response when querying with hetu") {
    get("/111111-1976") {
      status should be(200)
      body should be(
        "{\"oppijanumero\":\"1.2.4\",\"opiskeluoikeudet\":[],\"tutkinnot\":[],\"suoritukset\":[]}"
      )
    }
  }

  test("should return required fields from Virta response when querying with hetu") {
    get("/111111-1975") {
      status should be(200)
      body should include("875101")
    }
  }

  test("should return required fields from Virta response") {
    get("/1.2.3") {
      status should be(200)
      body should include("875101")
    }
  }
}
