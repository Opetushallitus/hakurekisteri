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
import fi.vm.sade.hakurekisteri.integration.haku.{GetHakuOption, Haku}
import fi.vm.sade.hakurekisteri.integration.hakukohde.Hakukohde.isKoutaHakukohdeOid
import fi.vm.sade.hakurekisteri.integration.hakukohde.{
  Hakukohde,
  HakukohdeQuery,
  HakukohteenKoulutuksetQuery
}
import fi.vm.sade.hakurekisteri.integration.koodisto.{
  GetKoodistoKoodiArvot,
  KoodistoActorRef,
  KoodistoKoodiArvot
}
import fi.vm.sade.hakurekisteri.integration.kouta.{KoutaInternalActor, KoutaInternalActorRef}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Organisaatio, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.integration.pistesyotto.{
  PistesyottoService,
  Pistetieto,
  PistetietoWrapper
}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{
  HakukohdeOid,
  HakukohteenKoulutukset,
  Koulutusohjelma,
  TarjontaActorRef,
  TarjontaHakukohde
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
  hakukohdeKoulutuskoodi: Option[ValpasKoodi],
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
  hakutyyppi: Option[ValpasKoodi],
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
  maa: ValpasKoodi,
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

object SlowFutureLogger {
  private val logger = LoggerFactory.getLogger(getClass)

  def apply[T](tag: String, future: Future[T])(implicit ec: ExecutionContext) = {
    val start = System.currentTimeMillis()
    future.onComplete({ case _ =>
      val time = System.currentTimeMillis() - start
      if (time > 500) {
        logger.warn(s"Slow future $tag took ${time}ms")
      }
    })
    future
  }
}
object ValintakoeTunnisteParser {
  private def toValpasPistetieto(p: Pistetieto): Option[ValpasPistetieto] = {
    if (p.isValid) {
      Some(ValpasPistetieto(p.tunniste, p.arvo.toString, p.osallistuminen))
    } else {
      None
    }
  }
  def findPaasykoe(pistetiedot: Seq[Pistetieto]): Option[ValpasPistetieto] = {
    pistetiedot.find(_.tunniste.contains("paasykoe")).flatMap(toValpasPistetieto)
  }
  def findKielikoe(pistetiedot: Seq[Pistetieto]): Option[ValpasPistetieto] = {
    pistetiedot.find(_.tunniste.contains("kielikoe")).flatMap(toValpasPistetieto)
  }
  def findLisanaytto(pistetiedot: Seq[Pistetieto]): Option[ValpasPistetieto] = {
    pistetiedot.find(_.tunniste.contains("lisanaytto")).flatMap(toValpasPistetieto)
  }
}

class ValpasIntergration(
  pistesyottoService: PistesyottoService,
  valintalaskentaClient: VirkailijaRestClient,
  organisaatioActor: OrganisaatioActorRef,
  koodistoActor: KoodistoActorRef,
  tarjontaActor: TarjontaActorRef,
  koutaActor: KoutaInternalActorRef,
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
  private def hakemusToValintatulosQuery(
    hakuOid: String,
    hakemukset: Seq[HakijaHakemus]
  ): VirkailijanValintatulos = {
    val hetulla = hakemukset.filter(_.hetu.isDefined)
    val ilmanHetua = hakemukset.filter(_.hetu.isEmpty)

    def groupBy(h: Seq[HakijaHakemus]): Map[String, Set[String]] = {
      h.groupBy(_.personOid.get).map { case (henkilo, hakemukset) =>
        (henkilo, hakemukset.map(_.oid).toSet)
      }
    }
    VirkailijanValintatulos(hakuOid, groupBy(hetulla), groupBy(ilmanHetua))
  }

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
    maaKoodit1: KoodistoKoodiArvot,
    maaKoodit2: KoodistoKoodiArvot,
    postiKoodit: KoodistoKoodiArvot
  ): ValpasHakemus = {
    def arvoToValpasKoodi(arvo: String, koodisto: KoodistoKoodiArvot): ValpasKoodi = {
      val uri = koodisto.arvoToUri(arvo)
      ValpasKoodi(
        koodiarvo = arvo,
        nimi = koodisto.uriToNimi(uri),
        lyhytNimi = koodisto.uriToLyhytNimi(uri),
        koodistoUri = koodisto.koodistoUri,
        koodistoVersio = koodisto.arvoToVersio(arvo)
      )
    }
    def uriToValpasKoodiWithoutArvo(uri: String, koodisto: KoodistoKoodiArvot): ValpasKoodi = {
      val koodi = uri.split("#").head
      val koodiarvo = koodisto.uriToArvo(koodi)
      ValpasKoodi(
        koodiarvo = koodiarvo,
        nimi = koodisto.uriToNimi(koodi),
        lyhytNimi = koodisto.uriToLyhytNimi(koodi),
        koodistoUri = koodisto.koodistoUri,
        koodistoVersio = koodisto.arvoToVersio(koodiarvo)
      )
    }
    def maaKoodiToValpasKoodi(arvo: String): ValpasKoodi =
      if (maaKoodit2.arvoToUri.contains(arvo))
        arvoToValpasKoodi(arvo, maaKoodit2)
      else
        arvoToValpasKoodi(arvo, maaKoodit1)
    def koulutusKoodiToValpasKoodi(arvo: String): ValpasKoodi =
      arvoToValpasKoodi(arvo, koulutusKoodit)
    def hakutoiveWithOidToValpasHakutoive(
      attachmentsChecked: Option[Boolean],
      hakukohdeOid: String,
      hakutoiveNro: Int,
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
                    .flatMap(a => if (a.arvo == null) None else Some(a.arvo.toString))
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
      val koulutus = oidToKoulutus(hakukohdeOid).koulutukset.headOption
      val knimi = koulutus.flatMap(_.koulutusohjelma).getOrElse(Koulutusohjelma(Map.empty)).tekstis
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
          "fi" -> knimi.get("kieli_fi").orElse(nimi.get("fi")).filterNot(_.isEmpty),
          "sv" -> knimi.get("kieli_sv").orElse(nimi.get("sv")).filterNot(_.isEmpty),
          "en" -> knimi.get("kieli_en").orElse(nimi.get("en")).filterNot(_.isEmpty)
        )
          .flatMap(kv => kv._2.map(k => (kv._1, k))),
        hakukohdeNimi = Map(
          "fi" -> nimi.get("kieli_fi").orElse(nimi.get("fi")).filterNot(_.isEmpty),
          "sv" -> nimi.get("kieli_sv").orElse(nimi.get("sv")).filterNot(_.isEmpty),
          "en" -> nimi.get("kieli_en").orElse(nimi.get("en")).filterNot(_.isEmpty)
        )
          .flatMap(kv => kv._2.map(k => (kv._1, k))),
        pisteet = hakutoiveenTulos.flatMap(_.pisteet),
        ilmoittautumistila = hakutoiveenTulos.map(_.ilmoittautumistila.ilmoittautumistila.toString),
        valintatila = hakutoiveenTulos.map(_.valintatila.toString),
        vastaanottotieto = hakutoiveenTulos.map(_.vastaanottotila.toString),
        hakutoivenumero = hakutoiveNro,
        hakukohdeOid = hakukohdeOid,
        //TODO: Valpas-palvelulle pitäisi palauttaa kaikki koulutuskoodit
        hakukohdeKoulutuskoodi = koulutus
          .flatMap(_.tkKoulutuskoodi)
          .map(koulutusKoodiToValpasKoodi),
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
      hakutoiveNro: Int,
      c: HakutoiveDTO
    ): Option[ValpasHakutoive] = {
      c.koulutusId.filterNot(_.isEmpty) match {
        case Some(hakukohdeOid) =>
          Some(hakutoiveWithOidToValpasHakutoive(attachmentsChecked, hakukohdeOid, hakutoiveNro, c))
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
                  hk.preferenceNumber,
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
          hakutapa = uriToValpasKoodiWithoutArvo(haku.hakutapaUri, hakutapa),
          hakutyyppi = haku.hakutyyppiUri.map(uri => uriToValpasKoodiWithoutArvo(uri, hakutyyppi)),
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
          maa = maaKoodiToValpasKoodi(a.asuinmaa),
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
            .map(h => h.flatMap(hk => hakutoiveToValpasHakutoive(None, hk.preferenceNumber, hk)))
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
          hakutapa = uriToValpasKoodiWithoutArvo(haku.hakutapaUri, hakutapa),
          hakutyyppi = haku.hakutyyppiUri.map(uri => uriToValpasKoodiWithoutArvo(uri, hakutyyppi)),
          huoltajanNimi = h.henkilotiedot.flatMap(_.huoltajannimi),
          huoltajanPuhelinnumero = h.henkilotiedot.flatMap(_.huoltajanpuhelinnumero),
          huoltajanSahkoposti = h.henkilotiedot.flatMap(_.huoltajansahkoposti),
          haunAlkamispaivamaara = formatHakuAlkamispaivamaara(haku.aika.alku),
          hakemuksenMuokkauksenAikaleima = h.updated.orElse(h.received).map(Formatter.print),
          oppijaOid = h.personOid.get,
          hakemusOid = h.oid,
          hakuNimi = Map("fi" -> nimi.fi, "sv" -> nimi.sv, "en" -> nimi.en).flatMap(kv =>
            kv._2.map(k => (kv._1, k))
          ),
          matkapuhelin = h.henkilotiedot.flatMap(_.matkapuhelinnumero1).get,
          hakuOid = h.applicationSystemId,
          email = h.henkilotiedot.flatMap(h => h.Sähköposti).get,
          maa = maaKoodiToValpasKoodi(h.henkilotiedot.flatMap(_.asuinmaa).getOrElse("")),
          postinumero = h.henkilotiedot
            .flatMap(_.Postinumero)
            .orElse(h.henkilotiedot.flatMap(_.postinumeroUlkomaa))
            .getOrElse(""),
          lahiosoite = h.henkilotiedot
            .flatMap(_.lahiosoite)
            .orElse(h.henkilotiedot.flatMap(_.osoiteUlkomaa))
            .getOrElse(""),
          postitoimipaikka = h.henkilotiedot
            .flatMap(_.Postinumero)
            .flatMap(postinumero =>
              postiKoodit.arvoToUri
                .get(postinumero)
                .flatMap(postiKoodit.uriToNimi.get)
                .flatMap(_.get("fi"))
            )
            .orElse(h.henkilotiedot.flatMap(_.kaupunkiUlkomaa))
            .getOrElse(""),
          hakutoiveet = hakutoiveet
        )
      }
      case _ => ???
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

    val valintarekisteri: Future[Map[String, Seq[ValintaTulos]]] = {
      val byHaku: Map[String, Seq[HakijaHakemus]] =
        hakemukset.filterNot(_.personOid.isEmpty).groupBy(_.applicationSystemId)

      Future
        .sequence(byHaku.map { case (hakuOid, hakemukset) =>
          fetchHaunTulokset(hakuOid, hakemukset)
        })
        .map(_.flatten.toSeq.groupBy(_.hakemusOid))
    }

    val hakukohteet: Future[Map[String, Hakukohde]] = fetchHakukohteet(hakukohdeOids)
      .map(_.flatten)
      .map(_.map(h => (h.oid, h)).toMap)

    val koulutukset: Future[Map[String, HakukohteenKoulutukset]] = fetchKoulutukset(hakukohdeOids)
      .map(_.map(h => (h.hakukohdeOid, h)).toMap)

    val organisaatiot: Future[Map[String, Organisaatio]] = fetchOrganisaatiot(organisaatioOids)
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
    val maatjavaltiot1 =
      (koodistoActor.actor ? GetKoodistoKoodiArvot("maatjavaltiot1")).mapTo[KoodistoKoodiArvot]
    val maatjavaltiot2 =
      (koodistoActor.actor ? GetKoodistoKoodiArvot("maatjavaltiot2")).mapTo[KoodistoKoodiArvot]
    val pisteet: Future[Seq[PistetietoWrapper]] =
      fetchPisteet(hakemukset)

    for {
      oidToPisteet: Map[String, Seq[PistetietoWrapper]] <- SlowFutureLogger(
        "pisteet",
        pisteet.map(_.groupBy(_.hakemusOID))
      )
      valintatulokset: Map[String, Seq[ValintaTulos]] <- SlowFutureLogger(
        "valintarekisteri",
        valintarekisteri
      )
      oidToHakukohde: Map[String, Hakukohde] <- SlowFutureLogger("hakukohteet", hakukohteet)
      oidToKoulutus: Map[String, HakukohteenKoulutukset] <- SlowFutureLogger(
        "koulutukset",
        koulutukset
      )
      hakutapa: KoodistoKoodiArvot <- SlowFutureLogger("hakutapa", hakutapa)
      hakutyyppi: KoodistoKoodiArvot <- SlowFutureLogger("hakutyyppi", hakutyyppi)
      koulutus: KoodistoKoodiArvot <- SlowFutureLogger("koulutus", koulutus)
      posti: KoodistoKoodiArvot <- SlowFutureLogger("posti", posti)
      maatjavaltiot1: KoodistoKoodiArvot <- SlowFutureLogger("maatjavaltiot1", maatjavaltiot1)
      maatjavaltiot2: KoodistoKoodiArvot <- SlowFutureLogger("maatjavaltiot2", maatjavaltiot2)
      oidToOrganisaatio <- SlowFutureLogger("organisaatio", organisaatiot)
      osallistumiset: Map[String, Seq[ValintalaskentaOsallistuminen]] <- SlowFutureLogger(
        "osallistumiset",
        osallistumisetFuture.map(_.groupBy(_.hakemusOid))
      )
    } yield {
      hakemukset.map(h =>
        Try(
          fromFetchedResources(
            oidToPisteet,
            osallistumiset.getOrElse(h.oid, Seq.empty),
            h,
            valintatulokset.get(h.oid) match {
              case Some(tulokset) =>
                if (tulokset.size > 1) {
                  throw new RuntimeException("Multiple valintatulokset for single hakemus!")
                }
                tulokset.headOption
              case None => None
            },
            oidToHakukohde,
            oidToKoulutus,
            oidToOrganisaatio,
            oidToHaku,
            hakutapa,
            hakutyyppi,
            koulutus,
            maatjavaltiot1,
            maatjavaltiot2,
            posti
          )
        )
      )
    }
  }

  private def fetchOrganisaatiot(organisaatioOids: Set[String]) = {
    Future
      .sequence(
        organisaatioOids.toSeq.map(oid =>
          (organisaatioActor.actor ? oid).mapTo[Option[Organisaatio]]
        )
      )
  }

  private def fetchKoulutukset(hakukohdeOids: Set[String]): Future[Seq[HakukohteenKoulutukset]] = {
    Future
      .sequence(
        hakukohdeOids.toSeq.map(oid =>
          if (isKoutaHakukohdeOid(oid)) {
            (koutaActor.actor ? HakukohteenKoulutuksetQuery(oid))
              .mapTo[HakukohteenKoulutukset]
          } else {
            (tarjontaActor.actor ? HakukohdeOid(oid)).mapTo[HakukohteenKoulutukset]
          }
        )
      )
  }

  private def fetchHakukohteet(hakukohdeOids: Set[String]): Future[Seq[Option[Hakukohde]]] = {
    Future.sequence(
      hakukohdeOids.toSeq.map(oid =>
        if (isKoutaHakukohdeOid(oid)) {
          (koutaActor.actor ? HakukohdeQuery(oid)).mapTo[Option[Hakukohde]]
        } else {
          (tarjontaActor.actor ? HakukohdeQuery(oid)).mapTo[Option[Hakukohde]]
        }
      )
    )
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

  def warmupCache(hakuOid: String, warmUpValintatulokset: Boolean) = {
    val haku: Future[Option[Haku]] = (hakuActor ? GetHakuOption(hakuOid)).mapTo[Option[Haku]]
    haku.onComplete {
      case Success(Some(hk)) =>
        hk.kohdejoukkoUri.filter(_.startsWith("haunkohdejoukko_11")) match {
          case Some(f) =>
            hakemusService.hakemuksetForHaku(hakuOid, None).onComplete {
              case Success(hakemukset) =>
                val hakukohteet: Set[String] = hakemukset
                  .flatMap(h => h.hakutoiveet.getOrElse(Seq.empty))
                  .flatMap(h => h.koulutusId)
                  .toSet
                val organisaatioOids =
                  hakemukset
                    .flatMap(_.hakutoiveet.getOrElse(List.empty))
                    .flatMap(_.organizationOid)
                    .toSet
                    .filterNot(_.isEmpty)
                val f1 = fetchHakukohteet(hakukohteet)
                val f2 = fetchKoulutukset(hakukohteet)
                val f3 = fetchOrganisaatiot(organisaatioOids)

                val hakutapa =
                  (koodistoActor.actor ? GetKoodistoKoodiArvot("hakutapa"))
                    .mapTo[KoodistoKoodiArvot]
                val hakutyyppi =
                  (koodistoActor.actor ? GetKoodistoKoodiArvot("hakutyyppi"))
                    .mapTo[KoodistoKoodiArvot]
                val koulutus =
                  (koodistoActor.actor ? GetKoodistoKoodiArvot("koulutus"))
                    .mapTo[KoodistoKoodiArvot]
                val posti =
                  (koodistoActor.actor ? GetKoodistoKoodiArvot("posti")).mapTo[KoodistoKoodiArvot]
                val maatjavaltiot1 =
                  (koodistoActor.actor ? GetKoodistoKoodiArvot("maatjavaltiot1"))
                    .mapTo[KoodistoKoodiArvot]
                val maatjavaltiot2 =
                  (koodistoActor.actor ? GetKoodistoKoodiArvot("maatjavaltiot2"))
                    .mapTo[KoodistoKoodiArvot]

                val tulokset: Future[Seq[ValintaTulos]] =
                  if (warmUpValintatulokset) {
                    fetchHaunTulokset(hakuOid, hakemukset)
                  } else {
                    Future.successful(Seq.empty[ValintaTulos])
                  }

                Future.sequence(
                  List(
                    tulokset,
                    f1,
                    f2,
                    f3,
                    hakutapa,
                    hakutyyppi,
                    koulutus,
                    posti,
                    maatjavaltiot1,
                    maatjavaltiot2
                  )
                ) onComplete {
                  case Success(_) =>
                    logger.info(s"Caches successfully warmed up on haku $hakuOid!")
                  case Failure(e) =>
                    logger.error(s"Couldn't warm up cache on haku $hakuOid!", e)
                }
              case Failure(e) =>
                logger.error(
                  s"Couldn't warm up cache on none existing haku $hakuOid! Hakemukset fetch failed!",
                  e
                )
            }
          case None =>
            logger.error(
              s"Wont warm up cache on haku $hakuOid because kohde joukko not 11 (was ${hk.kohdejoukkoUri})!"
            )
        }
      case Success(None) =>
        logger.error(s"Couldn't warm up cache on none existing haku $hakuOid!")
      case Failure(e) =>
        logger.error(s"Couldn't warm up cache on haku $hakuOid!", e)
    }
  }

  private val MAX_TULOKSET_KERRALLA = 10000

  private def fetchHaunTulokset(
    hakuOid: String,
    hakemukset: Seq[HakijaHakemus]
  ): Future[Seq[ValintaTulos]] = {
    val hks: List[Seq[HakijaHakemus]] =
      hakemukset.sliding(MAX_TULOKSET_KERRALLA, MAX_TULOKSET_KERRALLA).toList

    Future
      .sequence(
        hks.map(h =>
          (valintaTulos.actor ? hakemusToValintatulosQuery(hakuOid, h)).mapTo[Seq[ValintaTulos]]
        )
      )
      .map(vv => vv.flatten)
  }

  private val MAX_PISTEET_KERRALLA = 30000

  private def fetchPisteet(hakemukset: Seq[HakijaHakemus]): Future[Seq[PistetietoWrapper]] = {
    val pisteet: List[Set[String]] =
      hakemukset.map(_.oid).toSet.sliding(MAX_PISTEET_KERRALLA, MAX_PISTEET_KERRALLA).toList

    Future.sequence(pisteet.map(pistesyottoService.fetchPistetiedot)).map(vv => vv.flatten)
  }

  def fetch(query: ValpasQuery): Future[Seq[ValpasHakemus]] = {
    if (query.oppijanumerot.isEmpty) {
      Future.successful(Seq())
    } else {
      val masterOids: Future[Map[String, String]] =
        hakemusService.personOidstoMasterOids(query.oppijanumerot)

      val hakemuksetFuture = masterOids
        .flatMap(hakemusService.hakemuksetForPersons)
        .map(_.filter(_.stateValid))

      val osallistumisetFuture =
        masterOids.flatMap(masterOids => fetchOsallistumiset(masterOids.values.toSet))

      def excludeHakemusInHaku(haku: Option[Haku]): Boolean = {
        haku match {
          case Some(haku) =>
            !haku.kohdejoukkoUri.exists(_.startsWith("haunkohdejoukko_11")) ||
              haku.kkHaku ||
              (query.ainoastaanAktiivisetHaut && !haku.isActive)
          case None =>
            true
        }
      }

      (for {
        allHakemukset: Seq[HakijaHakemus] <- SlowFutureLogger("hakemukset", hakemuksetFuture)
        haut: Map[String, Haku] <- SlowFutureLogger(
          "haut",
          Future
            .sequence(
              allHakemukset
                .map(_.applicationSystemId)
                .toSet[String]
                .map(oid => (hakuActor ? GetHakuOption(oid)).mapTo[Option[Haku]])
            )
            .map(_.flatMap(h => h.map(j => (j.oid, j))).toMap)
        )
        hakemukset <- Future.successful(
          allHakemukset.filterNot(hakemus =>
            excludeHakemusInHaku(haut.get(hakemus.applicationSystemId))
          )
        )
        valpasHakemukset <-
          if (hakemukset.isEmpty) {
            Future.successful(Seq.empty)
          } else {
            fetchValintarekisteriAndTarjonta(haut, hakemukset, osallistumisetFuture)
              .flatMap(collectValintatulosResults)
          }
      } yield valpasHakemukset).recoverWith { case e: Exception =>
        logger.error(s"Failed to fetch Valpas-tiedot:", e)
        Future.failed(e)
      }
    }
  }

  private def collectValintatulosResults(s: Seq[Try[ValpasHakemus]]): Future[Seq[ValpasHakemus]] = {
    if (s == null || !s.exists(a => a != null)) {
      logger.warn("Zero results for Valpas query")
      Future.successful(Seq.empty[ValpasHakemus])
    } else {
      val fails: Seq[Try[ValpasHakemus]] = s.filter(a => a != null).filter(_.isFailure)
      if (fails.nonEmpty) {
        Future.failed(
          new RuntimeException(
            fails
              .flatMap {
                case Failure(x) =>
                  logger.error("Valpas fetch failed!", x)
                  Some(if (x == null) "null" else s"${x.getMessage}")
                case _ => None
              }
              .mkString(", ")
          )
        )
      } else {
        Future.successful(s.filter(a => a != null).flatMap {
          case Success(value) =>
            Some(value)
          case _ => None
        })
      }
    }
  }
}
