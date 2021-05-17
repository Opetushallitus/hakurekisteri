package fi.vm.sade.hakurekisteri.integration.valpas

import java.util.concurrent.Executors
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.{
  AtaruHakemus,
  FullHakemus,
  HakijaHakemus,
  HakutoiveDTO,
  IHakemusService
}
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, GetHakuOption, Haku}
import fi.vm.sade.hakurekisteri.integration.koodisto.{
  GetKoodistoKoodiArvot,
  KoodistoActorRef,
  KoodistoKoodiArvot
}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Organisaatio, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.integration.pistesyotto.{
  PistesyottoService,
  Pistetieto,
  PistetietoWrapper
}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{
  Hakukohde,
  HakukohdeOid,
  HakukohdeQuery,
  HakukohteenKoulutukset,
  Koulutusohjelma,
  TarjontaActorRef
}
import fi.vm.sade.hakurekisteri.integration.valintatulos.{
  ValintaTulos,
  ValintaTulosActorRef,
  ValintaTulosHakutoive,
  VirkailijanValintatulos
}
import fi.vm.sade.hakurekisteri.integration.{OphUrlProperties, VirkailijaRestClient, valpas}
import org.joda.time.{DateTimeZone, ReadableInstant}
import org.scalatra.swagger.runtime.annotations.ApiModelProperty
import org.slf4j.LoggerFactory

import scala.annotation.meta.field
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import org.joda.time.format.DateTimeFormat

object ValpasHakemusTila extends Enumeration {
  type ValpasHakemusTila = Value

  val AKTIIVINEN: valpas.ValpasHakemusTila.Value = Value("AKTIIVINEN")
  val PUUTTEELLINEN: valpas.ValpasHakemusTila.Value = Value("PUUTTEELLINEN")
}
case class Valintakoe(
  osallistuminen: String,
  laskentatila: String,
  valintakoeOid: String,
  valintakoeTunniste: String,
  nimi: String,
  valinnanVaiheOid: String,
  valinnanVaiheJarjestysluku: Int,
  arvo: Option[String]
) {}
case class ValpasHakutoive(
  paasykoe: Option[ValpasPistetieto],
  kielikoe: Option[ValpasPistetieto],
  lisanaytto: Option[ValpasPistetieto],
  liitteetTarkastettu: Option[Boolean],
  valintakoe: Seq[Valintakoe],
  alinHyvaksyttyPistemaara: Option[String],
  alinValintaPistemaara: Option[Int],
  organisaatioNimi: Map[String, String],
  hakukohdeNimi: Map[String, String],
  koulutusNimi: Map[String, String],
  pisteet: Option[BigDecimal],
  @(ApiModelProperty @field)(
    description =
      "KESKEN,VASTAANOTTANUT_SITOVASTI,EI_VASTAANOTETTU_MAARA_AIKANA,PERUNUT,PERUUTETTU,OTTANUT_VASTAAN_TOISEN_PAIKAN,EHDOLLISESTI_VASTAANOTTANUT",
    allowableValues =
      "KESKEN,VASTAANOTTANUT_SITOVASTI,EI_VASTAANOTETTU_MAARA_AIKANA,PERUNUT,PERUUTETTU,OTTANUT_VASTAAN_TOISEN_PAIKAN,EHDOLLISESTI_VASTAANOTTANUT"
  )
  vastaanottotieto: Option[String], // Vastaanottotila.Vastaanottotila
  @(ApiModelProperty @field)(
    description =
      "HYVAKSYTTY,HARKINNANVARAISESTI_HYVAKSYTTY,VARASIJALTA_HYVAKSYTTY,VARALLA,PERUUTETTU,PERUNUT,HYLATTY,PERUUNTUNUT,KESKEN",
    allowableValues =
      "HYVAKSYTTY,HARKINNANVARAISESTI_HYVAKSYTTY,VARASIJALTA_HYVAKSYTTY,VARALLA,PERUUTETTU,PERUNUT,HYLATTY,PERUUNTUNUT,KESKEN"
  )
  valintatila: Option[String], // Valintatila.Valintatila
  @(ApiModelProperty @field)(
    description =
      "EI_TEHTY,LASNA_KOKO_LUKUVUOSI,POISSA_KOKO_LUKUVUOSI,EI_ILMOITTAUTUNUT,LASNA_SYKSY,POISSA_SYKSY,LASNA,POISSA",
    allowableValues =
      "EI_TEHTY,LASNA_KOKO_LUKUVUOSI,POISSA_KOKO_LUKUVUOSI,EI_ILMOITTAUTUNUT,LASNA_SYKSY,POISSA_SYKSY,LASNA,POISSA"
  )
  ilmoittautumistila: Option[String], //Ilmoittautumistila.Ilmoittautumistila
  hakutoivenumero: Int,
  hakukohdeOid: String,
  hakukohdeKoulutuskoodi: ValpasKoodi,
  varasijanumero: Option[Int],
  hakukohdeOrganisaatio: String,
  koulutusOid: Option[String],
  harkinnanvaraisuus: Option[String]
) {}
case class ValpasKoodi(
  koodiarvo: String,
  nimi: Map[String, String],
  lyhytNimi: Map[String, String],
  koodistoUri: String,
  koodistoVersio: Int
)
case class ValpasHakemus(
  aktiivinenHaku: Boolean,
  hakemusUrl: String,
  hakutapa: ValpasKoodi,
  hakutyyppi: ValpasKoodi,
  huoltajanNimi: Option[String],
  huoltajanPuhelinnumero: Option[String],
  huoltajanSahkoposti: Option[String],
  @(ApiModelProperty @field)(
    description = "yyyy-MM-dd'T'HH:mm:ss"
  )
  haunAlkamispaivamaara: String,
  hakemuksenMuokkauksenAikaleima: Option[String],
  oppijaOid: String,
  hakemusOid: String,
  hakuOid: String,
  hakuNimi: Map[String, String],
  email: String,
  matkapuhelin: String,
  postinumero: String,
  lahiosoite: String,
  postitoimipaikka: String,
  hakutoiveet: Seq[ValpasHakutoive]
) {}
case class ValpasPistetieto(tunniste: String, arvo: String, osallistuminen: String)
case class ValpasQuery(oppijanumerot: Set[String], ainoastaanAktiivisetHaut: Boolean)
case class Osallistuminen(osallistuminen: String, laskentaTila: String) {}
case class ValintalaskentaValintakoe(
  valintakoeOid: String,
  valintakoeTunniste: String,
  aktiivinen: Boolean,
  nimi: String,
  osallistuminenTulos: Osallistuminen
) {}
case class ValintalaskentaValinnanVaihe(
  valinnanVaiheOid: String,
  valinnanVaiheJarjestysluku: Int,
  valintakokeet: Seq[ValintalaskentaValintakoe]
) {}
case class ValintalaskentaHakutoive(
  hakukohdeOid: String,
  valinnanVaiheet: Seq[ValintalaskentaValinnanVaihe]
) {}
case class ValintalaskentaOsallistuminen(
  hakuOid: String,
  hakijaOid: String,
  hakemusOid: String,
  createdAt: Long,
  hakutoiveet: Seq[ValintalaskentaHakutoive]
) {}

object ValintakoeTunnisteParser {
  private def toValpasPistetieto(p: Pistetieto): ValpasPistetieto = {
    ValpasPistetieto(p.tunniste, p.arvo.toString, p.osallistuminen)
  }
  def findPaasykoe(pistetiedot: Seq[Pistetieto]): Option[ValpasPistetieto] = {
    pistetiedot.find(_.tunniste.contains("paasykoe")).map(toValpasPistetieto)
  }
  def findKielikoe(pistetiedot: Seq[Pistetieto]): Option[ValpasPistetieto] = {
    pistetiedot.find(_.tunniste.contains("kielikoe")).map(toValpasPistetieto)
  }
  def findLisanaytto(pistetiedot: Seq[Pistetieto]): Option[ValpasPistetieto] = {
    pistetiedot.find(_.tunniste.contains("lisanaytto")).map(toValpasPistetieto)
  }
}

class ValpasIntergration(
  pistesyottoService: PistesyottoService,
  valintalaskentaClient: VirkailijaRestClient,
  organisaatioActor: OrganisaatioActorRef,
  koodistoActor: KoodistoActorRef,
  tarjontaActor: TarjontaActorRef,
  hakuActor: ActorRef,
  valintaTulos: ValintaTulosActorRef,
  hakemusService: IHakemusService
) {
  implicit val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))
  implicit val defaultTimeout: Timeout = 120.seconds
  private val logger = LoggerFactory.getLogger(getClass)

  private val HelsinkiTimeZone = DateTimeZone.forID("Europe/Helsinki")
  private val Formatter =
    DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(HelsinkiTimeZone)
  def formatHakuAlkamispaivamaara(date: ReadableInstant): String = Formatter.print(date)

  def fromFetchedResources(
    oidToPisteet: Map[String, Seq[PistetietoWrapper]],
    osallistumiset: Seq[ValintalaskentaOsallistuminen],
    hakemus: HakijaHakemus,
    tulos: Option[ValintaTulos],
    oidToHakukohde: Map[String, Hakukohde],
    oidToKoulutus: Map[String, HakukohteenKoulutukset],
    oidToOrganisaatio: Map[String, Organisaatio],
    oidToHaku: Map[String, Haku],
    hakutapa: KoodistoKoodiArvot,
    hakutyyppi: KoodistoKoodiArvot,
    koulutusKoodit: KoodistoKoodiArvot,
    postiKoodit: KoodistoKoodiArvot
  ): ValpasHakemus = {

    def uriToValpasKoodi(uri: String, koodisto: KoodistoKoodiArvot): ValpasKoodi = {
      val Array(koodi, versio) = uri.split("#")
      val Array(_, arvo) = koodi.split("_")
      val k = ValpasKoodi(
        koodiarvo = arvo,
        nimi = koodisto.arvoToNimi(koodi),
        lyhytNimi = koodisto.arvoToLyhytNimi(koodi),
        koodistoUri = koodisto.koodistoUri,
        koodistoVersio = versio.toInt
      )
      k
    }
    def koulutusKoodiToValpasKoodi(kk: String): ValpasKoodi =
      uriToValpasKoodi(s"koulutus_$kk#1", koulutusKoodit)
    def hakutoiveWithOidToValpasHakutoive(
      attachmentsChecked: Option[Boolean],
      hakukohdeOid: String,
      c: HakutoiveDTO
    ): ValpasHakutoive = {
      def hkToVk(hk: ValintalaskentaHakutoive, pisteet: Seq[Pistetieto]): Seq[Valintakoe] = {
        val vks = hk.valinnanVaiheet.flatMap(vv =>
          vv.valintakokeet.flatMap(vk =>
            if (vk.aktiivinen)
              Some(
                Valintakoe(
                  osallistuminen = vk.osallistuminenTulos.osallistuminen,
                  laskentatila = vk.osallistuminenTulos.laskentaTila,
                  valintakoeOid = vk.valintakoeOid,
                  valintakoeTunniste = vk.valintakoeTunniste,
                  nimi = vk.nimi,
                  valinnanVaiheOid = vv.valinnanVaiheOid,
                  valinnanVaiheJarjestysluku = vv.valinnanVaiheJarjestysluku,
                  arvo = pisteet
                    .find(_.tunniste.equals(vk.valintakoeTunniste))
                    .map(_.arvo.toString)
                )
              )
            else None
          )
        )

        vks
      }

      def julkaistuHakutoiveenTulos(): Option[ValintaTulosHakutoive] = {
        tulos
          .flatMap(
            _.hakutoiveet
              .find(thk => thk.julkaistavissa && thk.hakukohdeOid.equals(hakukohdeOid))
          )
      }
      val hakemuksenPisteet: Seq[Pistetieto] = oidToPisteet
        .getOrElse(hakemus.oid, Seq.empty)
        .filter(_.hakemusOID.equals(hakemus.oid))
        .flatMap(_.pisteet)
      val valintakoe: Seq[Valintakoe] =
        osallistumiset
          .flatMap(_.hakutoiveet)
          .filter(hk => hakukohdeOid.equals(hk.hakukohdeOid))
          .flatMap(vhk =>
            hkToVk(
              vhk,
              julkaistuHakutoiveenTulos()
                .map(_ => hakemuksenPisteet)
                .getOrElse(Seq.empty)
            )
          )
      val hakukohteenJulkaistutPisteet: Seq[Pistetieto] =
        valintakoe.flatMap(vk => hakemuksenPisteet.filter(_.tunniste.equals(vk.valintakoeTunniste)))

      val hakukohde: Hakukohde = oidToHakukohde(hakukohdeOid)
      val nimi = hakukohde.hakukohteenNimet
      val koulutus = oidToKoulutus(hakukohdeOid).koulutukset.head
      val knimi = koulutus.koulutusohjelma.getOrElse(Koulutusohjelma(Map.empty)).tekstis
      val organisaatio: Option[Organisaatio] = c.organizationOid.flatMap(oidToOrganisaatio.get)
      val organisaatioNimi = organisaatio.map(_.nimi).getOrElse(Map.empty).filterNot(_._2.isEmpty)
      val hakutoiveenTulos: Option[ValintaTulosHakutoive] = tulos.flatMap(
        _.hakutoiveet.find(hk => hk.hakukohdeOid.equals(hakukohdeOid) && hk.julkaistavissa)
      )
      ValpasHakutoive(
        paasykoe = ValintakoeTunnisteParser.findPaasykoe(hakukohteenJulkaistutPisteet),
        kielikoe = ValintakoeTunnisteParser.findKielikoe(hakukohteenJulkaistutPisteet),
        lisanaytto = ValintakoeTunnisteParser.findLisanaytto(hakukohteenJulkaistutPisteet),
        liitteetTarkastettu = attachmentsChecked,
        valintakoe = valintakoe,
        alinHyvaksyttyPistemaara = hakutoiveenTulos.flatMap(tulos =>
          tulos.jonokohtaisetTulostiedot
            .find(jono => jono.julkaistavissa && jono.oid.equals(tulos.valintatapajonoOid))
            .flatMap(_.alinHyvaksyttyPistemaara.map(_.toString()))
        ),
        alinValintaPistemaara = hakukohde.alinValintaPistemaara.filterNot(p => 0.equals(p)),
        organisaatioNimi = organisaatioNimi,
        koulutusNimi = Map(
          "fi" -> knimi.get("kieli_fi").filterNot(_.isEmpty),
          "sv" -> knimi.get("kieli_sv").filterNot(_.isEmpty),
          "en" -> knimi.get("kieli_en").filterNot(_.isEmpty)
        )
          .flatMap(kv => kv._2.map(k => (kv._1, k))),
        hakukohdeNimi = Map(
          "fi" -> nimi.get("kieli_fi").filterNot(_.isEmpty),
          "sv" -> nimi.get("kieli_sv").filterNot(_.isEmpty),
          "en" -> nimi.get("kieli_en").filterNot(_.isEmpty)
        )
          .flatMap(kv => kv._2.map(k => (kv._1, k))),
        pisteet = hakutoiveenTulos.flatMap(_.pisteet),
        ilmoittautumistila = hakutoiveenTulos.map(_.ilmoittautumistila.ilmoittautumistila.toString),
        valintatila = hakutoiveenTulos.map(_.valintatila.toString),
        vastaanottotieto = hakutoiveenTulos.map(_.vastaanottotila.toString),
        hakutoivenumero = c.preferenceNumber,
        hakukohdeOid = hakukohdeOid,
        hakukohdeKoulutuskoodi = koulutusKoodiToValpasKoodi(koulutus.tkKoulutuskoodi),
        varasijanumero = hakutoiveenTulos.flatMap(_.varasijanumero),
        // tieto siitä, onko kutsuttu pääsy- ja soveltuvuuskokeeseen
        // mahdollisen pääsy- ja soveltuvuuskokeen pistemäärä
        // mahdollinen kielitaidon arviointi
        // mahdollinen lisänäyttö
        // alimman hakukohteeseen hyväksytyn pistemäärä
        hakukohdeOrganisaatio = c.organizationOid match {
          case Some(oid) => oid
          case None =>
            throw new RuntimeException(
              s"Hakemukselle ${hakemus.oid} ei ole hakutoiveen organisaatiota!"
            )
        },
        koulutusOid = hakukohde.hakukohdeKoulutusOids.headOption,
        harkinnanvaraisuus = c.discretionaryFollowUp.filterNot(_.isEmpty)
      )
    }

    def hakutoiveToValpasHakutoive(
      attachmentsChecked: Option[Boolean],
      c: HakutoiveDTO
    ): Option[ValpasHakutoive] = {
      c.koulutusId.filterNot(_.isEmpty) match {
        case Some(hakukohdeOid) =>
          Some(hakutoiveWithOidToValpasHakutoive(attachmentsChecked, hakukohdeOid, c))
        case _ =>
          logger.debug(
            s"Haun ${hakemus.applicationSystemId} hakemukselle ${hakemus.oid} ei löydy hakutoiveen tunnistetta: ${c.koulutusId}"
          )
          None
      }
    }

    hakemus match {
      case a: AtaruHakemus => {
        val hakutoiveet: List[ValpasHakutoive] =
          a.hakutoiveet
            .map(h =>
              h.flatMap(hk =>
                hakutoiveToValpasHakutoive(
                  hk.koulutusId.flatMap(a.liitteetTarkastettu.get).flatten,
                  hk
                )
              )
            )
            .getOrElse(List.empty)
        val hakuOid = a.applicationSystemId
        val haku: Haku = oidToHaku(hakuOid)
        val nimi = haku.nimi
        logger.debug(
          s"Luodaan Atarun ValpasHakemus ${a.oid} hakutoiveilla ${hakutoiveet
            .map(_.hakukohdeOid)}"
        )
        ValpasHakemus(
          aktiivinenHaku = haku.isActive,
          hakemusUrl = OphUrlProperties.url("ataru.hakemus", a.oid),
          hakutapa = uriToValpasKoodi(haku.hakutapaUri, hakutapa),
          hakutyyppi = uriToValpasKoodi(haku.hakutyyppiUri, hakutyyppi),
          huoltajanNimi = None,
          huoltajanPuhelinnumero = None,
          huoltajanSahkoposti = None,
          haunAlkamispaivamaara = formatHakuAlkamispaivamaara(haku.aika.alku),
          hakemuksenMuokkauksenAikaleima = Some(a.createdTime),
          oppijaOid = a.personOid.get,
          hakemusOid = a.oid,
          hakuNimi = Map("fi" -> nimi.fi, "sv" -> nimi.sv, "en" -> nimi.en)
            .flatMap(kv => kv._2.map(k => (kv._1, k))),
          hakuOid = a.applicationSystemId,
          matkapuhelin = a.matkapuhelin,
          postinumero = a.postinumero,
          lahiosoite = a.lahiosoite,
          postitoimipaikka = a.postitoimipaikka.getOrElse(""),
          email = a.email,
          hakutoiveet = hakutoiveet
        )
      }
      case h: FullHakemus => {
        val hakutoiveet: List[ValpasHakutoive] =
          h.hakutoiveet
            .map(h => h.flatMap(hakutoiveToValpasHakutoive(None, _)))
            .getOrElse(List.empty)
        val hakuOid = h.applicationSystemId
        val haku = oidToHaku(hakuOid)
        val nimi = haku.nimi

        logger.debug(
          s"Luodaan Haku-App:n ValpasHakemus ${h.oid} hakutoiveilla ${hakutoiveet
            .map(_.hakukohdeOid)}"
        )
        ValpasHakemus(
          aktiivinenHaku = haku.isActive,
          hakemusUrl = OphUrlProperties.url("haku-app.hakemus", h.oid),
          hakutapa = uriToValpasKoodi(haku.hakutapaUri, hakutapa),
          hakutyyppi = uriToValpasKoodi(haku.hakutyyppiUri, hakutyyppi),
          huoltajanNimi = h.henkilotiedot.flatMap(_.huoltajannimi),
          huoltajanPuhelinnumero = h.henkilotiedot.flatMap(_.huoltajanpuhelinnumero),
          huoltajanSahkoposti = h.henkilotiedot.flatMap(_.huoltajansahkoposti),
          haunAlkamispaivamaara = formatHakuAlkamispaivamaara(haku.aika.alku),
          hakemuksenMuokkauksenAikaleima = h.received.map(Formatter.print),
          oppijaOid = h.personOid.get,
          hakemusOid = h.oid,
          hakuNimi = Map("fi" -> nimi.fi, "sv" -> nimi.sv, "en" -> nimi.en).flatMap(kv =>
            kv._2.map(k => (kv._1, k))
          ),
          matkapuhelin = h.henkilotiedot.flatMap(_.matkapuhelinnumero1).get,
          hakuOid = h.applicationSystemId,
          email = h.henkilotiedot.flatMap(h => h.Sähköposti).get,
          postinumero = h.henkilotiedot.flatMap(_.Postinumero).getOrElse(""),
          lahiosoite = h.henkilotiedot.flatMap(_.lahiosoite).getOrElse(""),
          postitoimipaikka = h.henkilotiedot
            .flatMap(_.Postinumero)
            .flatMap(postinumero =>
              postiKoodit.arvoToNimi.get(s"posti_$postinumero").flatMap(_.get("fi"))
            )
            .getOrElse(""),
          hakutoiveet = hakutoiveet
        )
      }
    }
  }

  def fetchValintarekisteriAndTarjonta(
    oidToHaku: Map[String, Haku],
    hakemukset: Seq[HakijaHakemus],
    osallistumisetFuture: Future[Seq[ValintalaskentaOsallistuminen]]
  ): Future[Seq[Try[ValpasHakemus]]] = {
    val hakukohdeOids: Set[String] =
      hakemukset
        .flatMap(_.hakutoiveet.getOrElse(Seq.empty))
        .flatMap(h => h.koulutusId)
        .toSet
        .filterNot(_.isEmpty)
    val organisaatioOids =
      hakemukset
        .flatMap(_.hakutoiveet.getOrElse(List.empty))
        .flatMap(_.organizationOid)
        .toSet
        .filterNot(_.isEmpty)
    def hakemusToValintatulosQuery(h: HakijaHakemus): VirkailijanValintatulos =
      VirkailijanValintatulos(h.applicationSystemId, h.oid)

    val valintarekisteri: Future[Map[String, ValintaTulos]] = Future
      .sequence(
        hakemukset.map(h =>
          (valintaTulos.actor ? hakemusToValintatulosQuery(h))
            .mapTo[ValintaTulos]
            .map(s => (h.oid, s))
        )
      )
      .map(_.toMap)

    val hakukohteet: Future[Map[String, Hakukohde]] = Future
      .sequence(
        hakukohdeOids.toSeq.map(oid =>
          (tarjontaActor.actor ? HakukohdeQuery(oid)).mapTo[Option[Hakukohde]]
        )
      )
      .map(_.flatten)
      .map(_.map(h => (h.oid, h)).toMap)

    val koulutukset: Future[Map[String, HakukohteenKoulutukset]] = Future
      .sequence(
        hakukohdeOids.toSeq.map(oid =>
          (tarjontaActor.actor ? HakukohdeOid(oid)).mapTo[HakukohteenKoulutukset]
        )
      )
      .map(_.map(h => (h.hakukohdeOid, h)).toMap)

    val organisaatiot: Future[Map[String, Organisaatio]] = Future
      .sequence(
        organisaatioOids.toSeq.map(oid =>
          (organisaatioActor.actor ? oid).mapTo[Option[Organisaatio]]
        )
      )
      .map(s => s.flatten)
      .map(s => s.map(q => (q.oid, q)).toMap)

    val hakutapa =
      (koodistoActor.actor ? GetKoodistoKoodiArvot("hakutapa")).mapTo[KoodistoKoodiArvot]
    val hakutyyppi =
      (koodistoActor.actor ? GetKoodistoKoodiArvot("hakutyyppi")).mapTo[KoodistoKoodiArvot]
    val koulutus =
      (koodistoActor.actor ? GetKoodistoKoodiArvot("koulutus")).mapTo[KoodistoKoodiArvot]
    val posti =
      (koodistoActor.actor ? GetKoodistoKoodiArvot("posti")).mapTo[KoodistoKoodiArvot]
    val pisteet: Future[Seq[PistetietoWrapper]] =
      pistesyottoService.fetchPistetiedot(hakemukset.map(_.oid).toSet)
    for {
      oidToPisteet: Map[String, Seq[PistetietoWrapper]] <- pisteet.map(_.groupBy(_.hakemusOID))
      valintatulokset: Map[String, ValintaTulos] <- valintarekisteri
      oidToHakukohde: Map[String, Hakukohde] <- hakukohteet
      oidToKoulutus: Map[String, HakukohteenKoulutukset] <- koulutukset
      hakutapa: KoodistoKoodiArvot <- hakutapa
      hakutyyppi: KoodistoKoodiArvot <- hakutyyppi
      koulutus: KoodistoKoodiArvot <- koulutus
      posti: KoodistoKoodiArvot <- posti
      oidToOrganisaatio <- organisaatiot
      osallistumiset: Map[String, Seq[ValintalaskentaOsallistuminen]] <- osallistumisetFuture.map(
        _.groupBy(_.hakemusOid)
      )
    } yield {
      hakemukset.map(h =>
        Try(
          fromFetchedResources(
            oidToPisteet,
            osallistumiset.getOrElse(h.oid, Seq.empty),
            h,
            valintatulokset.get(h.oid),
            oidToHakukohde,
            oidToKoulutus,
            oidToOrganisaatio,
            oidToHaku,
            hakutapa,
            hakutyyppi,
            koulutus,
            posti
          )
        )
      )
    }
  }
  def fetchOsallistumiset(oids: Set[String]): Future[Seq[ValintalaskentaOsallistuminen]] = {
    valintalaskentaClient
      .postObject[Set[String], Seq[ValintalaskentaOsallistuminen]](
        "valintalaskenta-service.bypersonoid"
      )(
        200,
        oids
      )
  }

  def fetch(query: ValpasQuery): Future[Seq[ValpasHakemus]] = {
    if (query.oppijanumerot.isEmpty) {
      Future.successful(Seq())
    } else {
      val masterOids: Future[Map[String, String]] =
        hakemusService.personOidstoMasterOids(query.oppijanumerot)

      val hakemuksetFuture = masterOids
        .flatMap(masterOids => hakemusService.hakemuksetForPersons(masterOids.values.toSet))
        .map(_.filter(_.stateValid))

      val osallistumisetFuture =
        masterOids.flatMap(masterOids => fetchOsallistumiset(masterOids.values.toSet))

      def excludeHakemusInHaku(haku: Option[Haku]): Boolean = {
        haku match {
          case Some(haku) => {
            haku.kkHaku || (query.ainoastaanAktiivisetHaut && !haku.isActive)
          }
          case None =>
            false
        }

      }

      (for {
        allHakemukset: Seq[HakijaHakemus] <- hakemuksetFuture
        haut: Map[String, Haku] <- Future
          .sequence(
            allHakemukset
              .map(_.applicationSystemId)
              .toSet[String]
              .map(oid => (hakuActor ? GetHakuOption(oid)).mapTo[Option[Haku]])
          )
          .map(_.flatMap(h => h.map(j => (j.oid, j))).toMap)
        hakemukset <- Future.successful(
          allHakemukset.filterNot(hakemus =>
            excludeHakemusInHaku(haut.get(hakemus.applicationSystemId))
          )
        )
        valpasHakemukset <-
          if (hakemukset.isEmpty) {
            Future.successful(Seq.empty)
          } else {
            fetchValintarekisteriAndTarjonta(haut, hakemukset, osallistumisetFuture).flatMap(s => {
              if (s.exists(_.isFailure)) {
                Future.failed(
                  new RuntimeException(
                    s.flatMap {
                      case Failure(x) =>
                        logger.error("Valpas fetch failed!", x)
                        Some(x.getMessage)
                      case _ => None
                    }.mkString(", ")
                  )
                )
              } else {
                Future.successful(s.flatMap {
                  case Success(value) =>
                    Some(value)
                  case _ => None
                })
              }
            })
          }
      } yield valpasHakemukset).recoverWith { case e: Exception =>
        logger.error(s"Failed to fetch Valpas-tiedot:", e)
        Future.failed(e)
      }
    }
  }
}
