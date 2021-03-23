package fi.vm.sade.hakurekisteri.integration.valpas

import java.time.ZoneId
import java.util.TimeZone
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
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku}
import fi.vm.sade.hakurekisteri.integration.koodisto.{
  GetKoodistoKoodiArvot,
  KoodistoActorRef,
  KoodistoKoodiArvot
}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{
  Hakukohde,
  HakukohdeOid,
  HakukohdeQuery,
  HakukohteenKoulutukset,
  TarjontaActorRef
}
import fi.vm.sade.hakurekisteri.integration.valintatulos.{
  HakemuksenValintatulos,
  SijoitteluTulos,
  ValintaTulosActorRef
}
import fi.vm.sade.hakurekisteri.integration.valpas
import org.joda.time.{DateTime, DateTimeZone, ReadableInstant}
import org.scalatra.swagger.runtime.annotations.ApiModelProperty
import org.slf4j.LoggerFactory

import scala.annotation.meta.field
import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}

object ValpasHakemusTila extends Enumeration {
  type ValpasHakemusTila = Value

  val AKTIIVINEN: valpas.ValpasHakemusTila.Value = Value("AKTIIVINEN")
  val PUUTTEELLINEN: valpas.ValpasHakemusTila.Value = Value("PUUTTEELLINEN")
}

case class ValpasHakutoive(
  alinValintaPistemaara: Option[Int],
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
  hakukohdeKoulutuskoodi: String,
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
  hakutapa: ValpasKoodi,
  hakutyyppi: ValpasKoodi,
  huoltajanNimi: Option[String],
  huoltajanPuhelinnumero: Option[String],
  huoltajanSahkoposti: Option[String],
  @(ApiModelProperty @field)(
    description = "yyyy-MM-dd'T'HH:mm:ss"
  )
  haunAlkamispaivamaara: String,
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
object ValpasHakemus {
  private val HelsinkiTimeZone = DateTimeZone.forID("Europe/Helsinki")
  private val Formatter = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(HelsinkiTimeZone)
  def formatHakuAlkamispaivamaara(date: ReadableInstant): String = Formatter.print(date)

  def fromFetchedResources(
    hakemus: HakijaHakemus,
    tulos: Option[SijoitteluTulos],
    oidToHakukohde: Map[String, Hakukohde],
    oidToKoulutus: Map[String, HakukohteenKoulutukset],
    oidToHaku: Map[String, Haku],
    hakutapa: KoodistoKoodiArvot,
    hakutyyppi: KoodistoKoodiArvot
  ): ValpasHakemus = {
    def uriToValpasKoodi(uri: String, koodisto: KoodistoKoodiArvot): ValpasKoodi = {
      val Array(koodi, versio) = uri.split("#")
      val Array(_, arvo) = koodi.split("_")
      ValpasKoodi(
        koodiarvo = koodisto.koodistoUri,
        nimi = koodisto.arvoToNimi(koodi),
        lyhytNimi = koodisto.arvoToLyhytNimi(koodi),
        koodistoUri = koodi,
        koodistoVersio = versio.toInt
      )
    }

    def hakutoiveToValpasHakutoive(c: HakutoiveDTO): ValpasHakutoive = {
      val hakukohdeOid = c.koulutusId.filterNot(_.isEmpty) match {
        case Some(oid) => oid
        case None =>
          throw new RuntimeException(
            s"Hakijalla ${hakemus.personOid.get} ei ole hakutoiveen OID-tunnistetta hakemuksella ${hakemus.oid}!"
          )
      }

      val key = (hakemus.oid, hakukohdeOid)
      val hakukohde: Hakukohde = oidToHakukohde(hakukohdeOid)
      val nimi = hakukohde.hakukohteenNimet
      val koulutus = oidToKoulutus(hakukohdeOid).koulutukset.head
      val knimi = koulutus.koulutusohjelma.tekstis

      ValpasHakutoive(
        alinValintaPistemaara = hakukohde.alinValintaPistemaara.filterNot(p => 0.equals(p)),
        koulutusNimi = Map(
          "fi" -> knimi.get("kieli_fi").filter(_.isEmpty),
          "sv" -> knimi.get("kieli_sv").filter(_.isEmpty),
          "en" -> knimi.get("kieli_en").filter(_.isEmpty)
        )
          .flatMap(kv => kv._2.map(k => (kv._1, k))),
        hakukohdeNimi = Map(
          "fi" -> nimi.get("kieli_fi").filter(_.isEmpty),
          "sv" -> nimi.get("kieli_sv").filter(_.isEmpty),
          "en" -> nimi.get("kieli_en").filter(_.isEmpty)
        )
          .flatMap(kv => kv._2.map(k => (kv._1, k))),
        pisteet = tulos.flatMap(t => t.pisteet.get(key)),
        ilmoittautumistila = tulos.flatMap(t => t.ilmoittautumistila.get(key).map(_.toString)),
        valintatila = tulos.flatMap(t => t.valintatila.get(key).map(_.toString)),
        vastaanottotieto = tulos.flatMap(t => t.vastaanottotila.get(key).map(_.toString)),
        hakutoivenumero = c.preferenceNumber,
        hakukohdeOid = hakukohdeOid,
        hakukohdeKoulutuskoodi = koulutus.tkKoulutuskoodi,
        varasijanumero = tulos.flatMap(t => t.varasijanumero.get(key).flatten),
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
        harkinnanvaraisuus = c.discretionaryFollowUp
      )
    }

    hakemus match {
      case a: AtaruHakemus => {
        val hakutoiveet: Option[List[ValpasHakutoive]] =
          a.hakutoiveet.map(h => h.map(hakutoiveToValpasHakutoive))
        val hakuOid = a.applicationSystemId
        val haku: Haku = oidToHaku(hakuOid)
        val nimi = haku.nimi

        ValpasHakemus(
          hakutapa = uriToValpasKoodi(haku.hakutapaUri, hakutapa),
          hakutyyppi = uriToValpasKoodi(haku.hakutyyppiUri, hakutyyppi),
          huoltajanNimi = None,
          huoltajanPuhelinnumero = None,
          huoltajanSahkoposti = None,
          haunAlkamispaivamaara = formatHakuAlkamispaivamaara(haku.aika.alku),
          oppijaOid = a.personOid.get,
          hakemusOid = a.oid,
          hakuNimi = Map("fi" -> nimi.fi, "sv" -> nimi.sv, "en" -> nimi.en)
            .flatMap(kv => kv._2.map(k => (kv._1, k))),
          hakuOid = a.applicationSystemId,
          matkapuhelin = a.matkapuhelin, // TODO
          postinumero = a.postinumero,
          lahiosoite = a.lahiosoite,
          postitoimipaikka = a.postitoimipaikka.getOrElse(""),
          email = a.email,
          hakutoiveet = hakutoiveet.getOrElse(Seq.empty)
        )
      }
      case h: FullHakemus => {
        val hakutoiveet: Option[List[ValpasHakutoive]] =
          h.hakutoiveet.map(h => h.map(hakutoiveToValpasHakutoive))
        val hakuOid = h.applicationSystemId
        val haku = oidToHaku(hakuOid)
        val nimi = haku.nimi

        ValpasHakemus(
          hakutapa = uriToValpasKoodi(haku.hakutapaUri, hakutapa),
          hakutyyppi = uriToValpasKoodi(haku.hakutyyppiUri, hakutyyppi),
          huoltajanNimi = h.henkilotiedot.flatMap(_.huoltajannimi),
          huoltajanPuhelinnumero = h.henkilotiedot.flatMap(_.huoltajanpuhelinnumero),
          huoltajanSahkoposti = h.henkilotiedot.flatMap(_.huoltajansahkoposti),
          haunAlkamispaivamaara = formatHakuAlkamispaivamaara(haku.aika.alku),
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
          postitoimipaikka = h.henkilotiedot.flatMap(_.Postitoimipaikka).getOrElse(""),
          hakutoiveet = hakutoiveet.getOrElse(Seq.empty)
        )
      }
    }
  }
}

case class ValpasQuery(oppijanumerot: Set[String])

class ValpasIntergration(
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

  def fetchValintarekisteriAndTarjonta(
    hakemukset: Seq[HakijaHakemus]
  ): Future[Seq[Try[ValpasHakemus]]] = {
    val hakukohdeOids: Set[String] =
      hakemukset
        .flatMap(_.hakutoiveet.getOrElse(Seq.empty))
        .flatMap(h => h.koulutusId)
        .toSet
        .filterNot(_.isEmpty)
    val hakuOids: Set[String] = hakemukset.map(_.applicationSystemId).toSet.filterNot(_.isEmpty)
    def hakemusToValintatulosQuery(h: HakijaHakemus): HakemuksenValintatulos =
      HakemuksenValintatulos(h.applicationSystemId, h.oid)

    val valintarekisteri: Future[Map[String, SijoitteluTulos]] = Future
      .sequence(
        hakemukset.map(h =>
          (valintaTulos.actor ? hakemusToValintatulosQuery(h))
            .mapTo[SijoitteluTulos]
            .map(s => (h.personOid.get, s))
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

    val haut: Future[Map[String, Haku]] = Future
      .sequence(hakuOids.map(oid => (hakuActor ? GetHaku(oid)).mapTo[Haku]))
      .map(_.map(h => (h.oid, h)).toMap)

    val hakutapa =
      (koodistoActor.actor ? GetKoodistoKoodiArvot("hakutapa")).mapTo[KoodistoKoodiArvot]
    val hakutyyppi =
      (koodistoActor.actor ? GetKoodistoKoodiArvot("hakutyyppi")).mapTo[KoodistoKoodiArvot]

    for {
      valintatulokset: Map[String, SijoitteluTulos] <- valintarekisteri
      oidToHakukohde: Map[String, Hakukohde] <- hakukohteet
      oidToKoulutus: Map[String, HakukohteenKoulutukset] <- koulutukset
      oidToHaku: Map[String, Haku] <- haut
      hakutapa: KoodistoKoodiArvot <- hakutapa
      hakutyyppi: KoodistoKoodiArvot <- hakutyyppi
    } yield {
      hakemukset.map(h =>
        Try(
          ValpasHakemus.fromFetchedResources(
            h,
            h.personOid.flatMap(valintatulokset.get),
            oidToHakukohde,
            oidToKoulutus,
            oidToHaku,
            hakutapa,
            hakutyyppi
          )
        )
      )
    }
  }

  def fetch(query: ValpasQuery): Future[Seq[ValpasHakemus]] = {
    if (query.oppijanumerot.isEmpty) {
      Future.successful(Seq())
    } else {

      (for {
        masterOids: Map[String, String] <- hakemusService.personOidstoMasterOids(
          query.oppijanumerot
        )
        hakemukset: Seq[HakijaHakemus] <- hakemusService.hakemuksetForPersons(
          masterOids.values.toSet
        )
        valpasHakemukset <-
          if (hakemukset.isEmpty) {
            Future.successful(Seq.empty)
          } else {
            fetchValintarekisteriAndTarjonta(hakemukset).flatMap(s => {
              if (s.exists(_.isFailure)) {
                Future.failed(
                  new RuntimeException(
                    s.flatMap {
                      case Failure(x) => Some(x.getMessage)
                      case _          => None
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
