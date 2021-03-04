package fi.vm.sade.hakurekisteri.integration.valpas

import java.util.concurrent.Executors

import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.{
  AtaruHakemus,
  FullHakemus,
  HakijaHakemus,
  HakutoiveDTO,
  IHakemusService
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
  Ilmoittautumistila,
  SijoitteluTulos,
  ValintaTulosActorRef,
  Valintatila,
  Vastaanottotila
}
import fi.vm.sade.hakurekisteri.integration.valpas
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, ExecutionContextExecutorService, Future}
import scala.concurrent.duration._

object ValpasHakemusTila extends Enumeration {
  type ValpasHakemusTila = Value

  val AKTIIVINEN: valpas.ValpasHakemusTila.Value = Value("AKTIIVINEN")
  val PUUTTEELLINEN: valpas.ValpasHakemusTila.Value = Value("PUUTTEELLINEN")
}

case class ValpasHakutoive(
  hakukohdeNimi: Option[String], // TODO
  koulutusNimi: Option[String], // TODO
  pisteet: Option[BigDecimal],
  vastaanottotieto: Option[String], // Vastaanottotila.Vastaanottotila
  valintatila: Option[String], // Valintatila.Valintatila
  ilmoittautumistila: Option[String], //Ilmoittautumistila.Ilmoittautumistila
  hakutoivenumero: Int,
  hakukohdeOid: String,
  hakukohdeKoulutuskoodi: String,
  hakukohdeOrganisaatio: String,
  koulutusOid: Option[String],
  harkinnanvaraisuus: Option[String]
) {}

case class ValpasHakemus(
  muokattu: String,
  oppijaOid: String,
  hakemusOid: String,
  hakuOid: String,
  hakuNimi: String,
  email: String,
  matkapuhelin: String,
  osoite: String,
  hakutoiveet: Seq[ValpasHakutoive]
) {}
object ValpasHakemus {

  def apply(
    hakemus: HakijaHakemus,
    tulos: Option[SijoitteluTulos],
    oidToHakukohde: Map[String, Hakukohde],
    oidToKoulutus: Map[String, HakukohteenKoulutukset]
  ): ValpasHakemus = {
    def hakutoiveToValpasHakutoive(c: HakutoiveDTO): ValpasHakutoive = {
      val hakukohdeOid = c.koulutusId match {
        case Some(oid) => oid
        case None =>
          throw new RuntimeException(
            s"Hakemukselle ${hakemus.oid} ei ole hakutoiveen OID-tunnistetta!"
          )
      }

      val key = (hakemus.oid, hakukohdeOid)
      val hakukohde: Hakukohde = oidToHakukohde(hakukohdeOid)
      val koulutus: HakukohteenKoulutukset = oidToKoulutus(hakukohdeOid)
      ValpasHakutoive(
        koulutusNimi = None, // TODO
        hakukohdeNimi = None, // TODO
        pisteet = tulos.flatMap(t => t.pisteet.get(key)),
        ilmoittautumistila = tulos.flatMap(t => t.ilmoittautumistila.get(key).map(_.toString)),
        valintatila = tulos.flatMap(t => t.valintatila.get(key).map(_.toString)),
        vastaanottotieto = tulos.flatMap(t => t.vastaanottotila.get(key).map(_.toString)),
        hakutoivenumero = c.preferenceNumber,
        hakukohdeOid = hakukohdeOid,
        hakukohdeKoulutuskoodi = koulutus.koulutukset.head.tkKoulutuskoodi,
        // tieto siitä, onko kutsuttu pääsy- ja soveltuvuuskokeeseen
        // mahdollisen pääsy- ja soveltuvuuskokeen pistemäärä
        // mahdollinen kielitaidon arviointi
        // mahdollinen lisänäyttö
        // yhteispistemäärä
        // alimman hakukohteeseen hyväksytyn pistemäärä
        // varasijanumero, jos varalla // TODO
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
      case a: AtaruHakemus =>
        val hakutoiveet: Option[List[ValpasHakutoive]] =
          a.hakutoiveet.map(h => h.map(hakutoiveToValpasHakutoive))

        ValpasHakemus(
          muokattu = "", // TODO
          oppijaOid = a.personOid.get,
          hakemusOid = a.oid,
          hakuOid = a.applicationSystemId,
          hakuNimi = "", // TODO
          matkapuhelin = a.matkapuhelin, // TODO
          osoite = s"${a.lahiosoite}, ${a.postinumero} ${a.postitoimipaikka}",
          email = a.email,
          hakutoiveet = hakutoiveet.getOrElse(Seq.empty)
        )
      case h: FullHakemus => {
        val hakutoiveet: Option[List[ValpasHakutoive]] =
          h.hakutoiveet.map(h => h.map(hakutoiveToValpasHakutoive))

        ValpasHakemus(
          hakutoiveet = hakutoiveet.getOrElse(Seq.empty),
          muokattu = "", // TODO
          oppijaOid = h.personOid.get,
          hakemusOid = h.oid,
          hakuNimi = "", // TODO
          matkapuhelin = h.answers
            .flatMap(a => a.henkilotiedot)
            .flatMap(h => h.matkapuhelinnumero1.orElse(h.matkapuhelinnumero2))
            .get,
          hakuOid = h.applicationSystemId,
          email = h.answers.flatMap(a => a.henkilotiedot).flatMap(h => h.Sähköposti).get,
          osoite = h.answers
            .flatMap(a => a.henkilotiedot)
            .flatMap(a =>
              (a.lahiosoite, a.Postinumero, a.Postitoimipaikka) match {
                case (Some(lahiosoite), Some(postinumero), Some(postitoimipaikka)) =>
                  Some(s"${lahiosoite}, ${postinumero} ${postitoimipaikka}")
                case _ =>
                  None
              }
            )
            .get
        )
      }
    }
  }
}

case class ValpasQuery(oppijanumerot: Set[String])

class ValpasIntergration(
  tarjontaActor: TarjontaActorRef,
  valintaTulos: ValintaTulosActorRef,
  hakemusService: IHakemusService
) {
  implicit val ec: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(5))
  implicit val defaultTimeout: Timeout = 120.seconds
  private val logger = LoggerFactory.getLogger(getClass)

  def fetchValintarekisteriAndTarjonta(
    hakemukset: Seq[HakijaHakemus]
  ): Future[Seq[ValpasHakemus]] = {
    val hakukohdeOids: Set[String] =
      hakemukset.flatMap(_.hakutoiveet.getOrElse(Seq.empty)).flatMap(h => h.koulutusId).toSet
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

    for {
      valintatulokset: Map[String, SijoitteluTulos] <- valintarekisteri
      oidToHakukohde: Map[String, Hakukohde] <- hakukohteet
      oidToKoulutus: Map[String, HakukohteenKoulutukset] <- koulutukset
    } yield {
      hakemukset.map(h =>
        ValpasHakemus(h, h.personOid.flatMap(valintatulokset.get), oidToHakukohde, oidToKoulutus)
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
        valpasHakemukset <- fetchValintarekisteriAndTarjonta(hakemukset)
      } yield {
        valpasHakemukset
      }).recoverWith { case e: Exception =>
        logger.error(s"Failed to fetch Valpas-tiedot:", e)
        Future.failed(e)
      }
    }
  }
}
