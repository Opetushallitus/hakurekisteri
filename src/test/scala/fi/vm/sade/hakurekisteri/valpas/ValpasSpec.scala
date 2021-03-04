package fi.vm.sade.hakurekisteri.valpas
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS

import akka.actor.{Actor, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.dates.InFuture
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.hakemus.{
  AtaruHakemusDto,
  AtaruResponse,
  FullHakemus,
  HakemusService
}
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku}
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  Henkilo,
  HenkiloViite,
  IOppijaNumeroRekisteri,
  Kieli,
  OppijaNumeroRekisteri
}
import fi.vm.sade.hakurekisteri.integration.mocks.SuoritusMock
import fi.vm.sade.hakurekisteri.integration.organisaatio.{
  ChildOids,
  HttpOrganisaatioActor,
  Organisaatio,
  OrganisaatioActorRef,
  OrganisaatioResponse
}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{
  Hakukohde,
  HakukohdeOid,
  HakukohdeQuery,
  HakukohteenKoulutukset,
  Hakukohteenkoulutus,
  Koulutus,
  RestHaku,
  TarjontaActorRef,
  TarjontaResultResponse
}
import fi.vm.sade.hakurekisteri.integration.valintatulos.{
  HakemuksenValintatulos,
  SijoitteluTulos,
  ValintaTulos,
  ValintaTulosActorRef,
  Valintatila
}
import fi.vm.sade.hakurekisteri.integration.valpas.{ValpasHakemus, ValpasIntergration, ValpasQuery}
import fi.vm.sade.hakurekisteri.integration.{
  ActorSystemSupport,
  OphUrlProperties,
  VirkailijaRestClient
}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.json4s.ext.EnumNameSerializer
import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.JsonMethods.parse
import org.mockito.Mockito
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, _}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

class ValpasSpec
    extends FlatSpec
    with Matchers
    with MockitoSugar
    with ActorSystemSupport
    with HakeneetSupport {
  private implicit val ec: ExecutionContext = {
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))
  }
  private implicit val timeoutDuration: FiniteDuration = Duration(20, SECONDS)
  private implicit val timeout: Timeout = Timeout(timeoutDuration)

  private val oppijaOid = "1.2.246.562.24.82344311114"

  behavior of "Valpas Resource"

  it should "handle combining Ataru and HakuApp hakemukset with valintatulokset" in {
    withSystem { system =>
      val ataruHakemukset: AtaruResponse = parse(
        SuoritusMock.getResourceJson("/mock-data/hakemus/hakemus-valpas-ataru.json")
      ).extract[AtaruResponse]
      val ataruClient = mockPostAtaruClient(Seq(oppijaOid))(ataruHakemukset)

      val hakuAppHakemukset: Map[String, Seq[FullHakemus]] = Map()
      val hakuAppClient = mockPostHakuAppClient(Seq(oppijaOid))(hakuAppHakemukset)

      val onrClient: VirkailijaRestClient = mockPostOnrClient(Seq(oppijaOid))
      val oppijaNumeroRekisteri: IOppijaNumeroRekisteri =
        new OppijaNumeroRekisteri(onrClient, system, Config.mockDevConfig)
      val haku = system.actorOf(Props(new Actor {
        override def receive: Actor.Receive = {
          case GetHaku(oid) => {
            val haku = resource[TarjontaResultResponse[Option[RestHaku]]](
              s"/mock-data/tarjonta/haku_$oid.json"
            ).result.get
            sender ! Haku(haku)(InFuture)
          }
        }
      }))
      val tarjonta = TarjontaActorRef(system.actorOf(Props(new Actor {
        override def receive: Actor.Receive = {
          case HakukohdeOid(oid) =>
            def kToHk(k: Koulutus) = {
              val kkKoulutusId = k.tunniste
              Hakukohteenkoulutus(
                k.komoOid,
                "631101",
                kkKoulutusId,
                k.koulutuksenAlkamiskausi,
                k.koulutuksenAlkamisvuosi,
                k.koulutuksenAlkamisPvms,
                k.koulutusohjelma
              )
            }
            oid match {
              case "1.2.246.562.20.12060097362" =>
                val koulutus = resource[TarjontaResultResponse[Option[Koulutus]]](
                  s"/mock-data/tarjonta/koulutus_1.2.246.562.17.45578326691.json"
                ).result.get
                sender ! HakukohteenKoulutukset(oid, None, Seq(kToHk(koulutus)))
              case "1.2.246.562.20.652840958110" =>
                val koulutus = resource[TarjontaResultResponse[Option[Koulutus]]](
                  s"/mock-data/tarjonta/koulutus_1.2.246.562.17.98887607319.json"
                ).result.get
                sender ! HakukohteenKoulutukset(oid, None, Seq(kToHk(koulutus)))
              case "1.2.246.562.20.743491195410" =>
                val koulutus = resource[TarjontaResultResponse[Option[Koulutus]]](
                  s"/mock-data/tarjonta/koulutus_1.2.246.562.17.97949993477.json"
                ).result.get
                sender ! HakukohteenKoulutukset(oid, None, Seq(kToHk(koulutus)))
            }
          case HakukohdeQuery(oid) =>
            sender ! resource[TarjontaResultResponse[Option[Hakukohde]]](
              s"/mock-data/tarjonta/hakukohde_$oid.json"
            ).result
        }
      })))
      val hakemusService = new HakemusService(
        hakuAppClient,
        ataruClient,
        tarjonta,
        OrganisaatioActorRef(
          system.actorOf(
            Props(
              new HttpOrganisaatioActor(
                onrClient,
                Config.mockDevConfig,
                CacheFactory.apply(OphUrlProperties)(system)
              )
            )
          )
        ),
        oppijaNumeroRekisteri,
        150
      )(system)

      val v: Future[Seq[ValpasHakemus]] = new ValpasIntergration(
        tarjonta,
        haku,
        ValintaTulosActorRef(system.actorOf(Props(new Actor {
          override def receive: Actor.Receive = { case HakemuksenValintatulos(hakuOid, _) =>
            sender !
              SijoitteluTulos(
                hakuOid,
                resource[ValintaTulos](s"/mock-data/valintatulos/valintatulos-hakemus-valpas.json")
              )
          }
        }))),
        hakemusService
      ).fetch(ValpasQuery(Set(oppijaOid)))

      val result = run(v)
      result.size should equal(1)
    }
  }

  private def mockPostOnrClient(post: Seq[String]): VirkailijaRestClient = {
    val orgs =
      Seq("1.2.246.562.10.98212669513", "1.2.246.562.10.60554652846", "1.2.246.562.10.83878914437")
    val onrClient: VirkailijaRestClient = mock[VirkailijaRestClient]

    def toOrg(oid: String): Organisaatio =
      resource[Organisaatio](s"/mock-data/organisaatio/organisaatio_$oid.json")
    def mockOrgResponse(oid: String) = {
      val org = toOrg(oid)
      Mockito
        .when(
          onrClient.readObject[Organisaatio](
            "organisaatio-service.organisaatio",
            oid
          )(200, maxRetries = 1)
        )
        .thenReturn(Future.successful(org))
      Mockito
        .when(
          onrClient.readObject[ChildOids](
            "organisaatio-service.organisaatio.childoids",
            oid
          )(200, maxRetries = 1)
        )
        .thenReturn(
          Future.successful(
            ChildOids(
              oids = Seq.empty
            )
          )
        )
    }
    orgs.foreach(mockOrgResponse)

    Mockito
      .when(
        onrClient.readObject[OrganisaatioResponse](
          "organisaatio-service.hierarkia.hae"
        )(200)
      )
      .thenReturn(
        Future.successful(
          OrganisaatioResponse(
            numHits = Some(1),
            organisaatiot = orgs.map(toOrg)
          )
        )
      )
    Mockito
      .when(
        onrClient.postObject[Map[String, Set[String]], Seq[HenkiloViite]](
          "oppijanumerorekisteri-service.duplicatesByPersonOids"
        )(200, Map("henkiloOids" -> post.toSet))
      )
      .thenReturn(Future.successful(post.map(o => HenkiloViite(o, o))))
    Mockito
      .when(
        onrClient.postObject[Set[String], Map[String, Henkilo]](
          "oppijanumerorekisteri-service.henkilotByOids"
        )(200, post.toSet)
      )
      .thenReturn(
        Future.successful(
          resource[Map[String, Henkilo]]("/mock-data/henkilo/henkilo-valpas.json")
        )
      )
    onrClient
  }
  private def mockPostHakuAppClient(
    post: Seq[String]
  )(response: Map[String, Seq[FullHakemus]]): VirkailijaRestClient = {
    val hakuAppClient: VirkailijaRestClient = mock[VirkailijaRestClient]
    Mockito
      .when(
        hakuAppClient
          .postObject[Set[String], Map[String, Seq[FullHakemus]]](
            "haku-app.bypersonoid"
          )(200, post.toSet)
      )
      .thenReturn(Future.successful(response))
    hakuAppClient
  }
  private def mockPostAtaruClient(
    post: Seq[String]
  )(response: AtaruResponse): VirkailijaRestClient = {
    val ataruClient: VirkailijaRestClient = mock[VirkailijaRestClient]
    Mockito
      .when(
        ataruClient
          .postObjectWithCodes[Map[String, Any], AtaruResponse](
            uriKey = "ataru.applications",
            acceptedResponseCodes = List(200),
            maxRetries = 2,
            resource = Map("hakijaOids" -> post.toList),
            basicAuth = false
          )
      )
      .thenReturn(Future.successful(response))
    ataruClient
  }

  private def resource[A](n: String)(implicit mf: Manifest[A]): A = {
    parse(SuoritusMock.getResourceJson(n)).extract[A]
  }

  private def run[T](future: Future[T])(implicit timeout: FiniteDuration): T =
    Await.result(future, timeout)
}
