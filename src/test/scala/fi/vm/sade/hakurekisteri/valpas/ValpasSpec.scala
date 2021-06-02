package fi.vm.sade.hakurekisteri.valpas
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import akka.actor.{Actor, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.dates.InFuture
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.hakemus.{AtaruResponse, FullHakemus, HakemusService}
import fi.vm.sade.hakurekisteri.integration.haku.{AllHaut, GetHaku, GetHakuOption, Haku, HakuRequest}
import fi.vm.sade.hakurekisteri.integration.henkilo.{Henkilo, HenkiloViite, IOppijaNumeroRekisteri, OppijaNumeroRekisteri}
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodistoKoodiArvot, Koodi, KoodistoActor, KoodistoActorRef, KoodistoKoodiArvot}
import fi.vm.sade.hakurekisteri.integration.mocks.SuoritusMock
import fi.vm.sade.hakurekisteri.integration.organisaatio.{ChildOids, HttpOrganisaatioActor, Organisaatio, OrganisaatioActorRef, OrganisaatioResponse}
import fi.vm.sade.hakurekisteri.integration.pistesyotto.{PistesyottoService, Pistetieto, PistetietoWrapper}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{Hakukohde, HakukohdeOid, HakukohdeQuery, HakukohteenKoulutukset, Hakukohteenkoulutus, Koulutus, RestHaku, TarjontaActorRef, TarjontaResultResponse}
import fi.vm.sade.hakurekisteri.integration.valintatulos.{ValintaTulos, ValintaTulosActorRef, VirkailijanValintatulos}
import fi.vm.sade.hakurekisteri.integration.valpas.{ValintalaskentaOsallistuminen, ValpasHakemus, ValpasIntergration, ValpasQuery}
import fi.vm.sade.hakurekisteri.integration.{ActorSystemSupport, OphUrlProperties, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.{UnknownRole, ValpasReadRole}
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
      val pisteClient: VirkailijaRestClient =
        mockPostPistesyottoClient(Seq("1.2.246.562.11.00000000000000446632"))
      val hakuAppHakemukset: Map[String, Seq[FullHakemus]] = Map()
      val hakuAppClient = mockPostHakuAppClient(Seq(oppijaOid))(hakuAppHakemukset)
      val valintalaskentaClient: VirkailijaRestClient =
        mockPostValintalaskentaClient(Seq(oppijaOid))
      val onrClient: VirkailijaRestClient = mockPostOnrClient(Seq(oppijaOid))
      val oppijaNumeroRekisteri: IOppijaNumeroRekisteri =
        new OppijaNumeroRekisteri(onrClient, system, Config.mockDevConfig)
      val haku = system.actorOf(Props(new Actor {
        val haku = resource[TarjontaResultResponse[Option[RestHaku]]](
          s"/mock-data/tarjonta/haku_1.2.246.562.29.36339915997.json"
        ).result.get

        override def receive: Actor.Receive = {
          case HakuRequest =>
            sender ! AllHaut(Seq(Haku(haku)(InFuture)))
          case GetHakuOption(_) => {
            sender ! Some(Haku(haku)(InFuture))
          }
        }
      }))

      val koodisto = KoodistoActorRef(system.actorOf(Props(new Actor {
        override def receive: Actor.Receive = { case GetKoodistoKoodiArvot(koodistoUri) =>
          val koodit = resource[Seq[Koodi]](
            s"/mock-data/koodisto/koodisto_$koodistoUri.json"
          )
          sender !
            KoodistoActor.kooditToKoodisto(koodistoUri, koodit)
        }
      })))

      val organisaatiot = OrganisaatioActorRef(system.actorOf(Props(new Actor {
        override def receive: Actor.Receive = { case oid: String =>
          sender ! Some(
            resource[Organisaatio](
              s"/mock-data/organisaatio/organisaatio_$oid.json"
            )
          )
        }
      })))

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
                Some(k.koulutusohjelma)
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
        new PistesyottoService(pisteClient),
        valintalaskentaClient,
        organisaatiot,
        koodisto,
        tarjonta,
        haku,
        ValintaTulosActorRef(system.actorOf(Props(new Actor {
          override def receive: Actor.Receive = { case _ =>
            sender !
              List(
                resource[ValintaTulos](
                  s"/mock-data/valintatulos/valintatulos-haku-hakemus-valpas.json"
                )
              )
          }
        }))),
        hakemusService
      ).fetch(ValpasQuery(Set(oppijaOid), ainoastaanAktiivisetHaut = true))

      val result = run(v)
      result.head.hakutapa.koodiarvo should equal("01")
      result.size should equal(1)
    }
  }
  private def mockPostPistesyottoClient(post: Seq[String]): VirkailijaRestClient = {
    val client: VirkailijaRestClient = mock[VirkailijaRestClient]

    Mockito
      .when(
        client.postObject[Seq[String], Seq[PistetietoWrapper]](
          "pistesyotto-service.hakemuksen.pisteet"
        )(200, post)
      )
      .thenReturn(
        Future.successful(
          post.map(o =>
            PistetietoWrapper(
              hakemusOID = o,
              pisteet = Seq(
                Pistetieto(
                  aikaleima = None,
                  tunniste = "lisanaytto_luonnonvara_osio_1_motivaatio_k2019",
                  arvo = "Jep",
                  osallistuminen = "OSALLISTUU"
                )
              )
            )
          )
        )
      )

    client
  }
  private def mockPostValintalaskentaClient(post: Seq[String]): VirkailijaRestClient = {
    val valintalaskentaClient: VirkailijaRestClient = mock[VirkailijaRestClient]

    Mockito
      .when(
        valintalaskentaClient.postObject[Set[String], Seq[ValintalaskentaOsallistuminen]](
          "valintalaskenta-service.bypersonoid"
        )(200, post.toSet)
      )
      .thenReturn(
        Future.successful(
          resource[Seq[ValintalaskentaOsallistuminen]](
            "/mock-data/valintalaskenta/valintalaskenta.json"
          )
        )
      )
    valintalaskentaClient
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
