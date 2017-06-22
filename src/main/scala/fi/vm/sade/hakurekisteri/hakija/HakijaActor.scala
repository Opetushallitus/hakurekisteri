package fi.vm.sade.hakurekisteri.hakija

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.hakija.ForkedSeq._
import fi.vm.sade.hakurekisteri.hakija.TupledFuture._
import fi.vm.sade.hakurekisteri.hakija.representation._
import fi.vm.sade.hakurekisteri.integration.hakemus.Hakupalvelu
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodi, GetRinnasteinenKoodiArvoQuery, Koodi}
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila.Ilmoittautumistila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.{ValintaTulosQuery, _}
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.suoritus.{Komoto, Suoritus}
import fi.vm.sade.hakurekisteri.web.kkhakija.Query

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex
import scala.util.{Failure, Try}


case class Hakukohde(koulutukset: Set[Komoto], hakukohdekoodi: String, oid: String)

sealed abstract class Hakutoive {
  val jno: Int
  val hakukohde: Hakukohde
  val kaksoistutkinto: Option[Boolean]
  val urheilijanammatillinenkoulutus: Option[Boolean]
  val harkinnanvaraisuusperuste: Option[String]
  val aiempiperuminen: Option[Boolean]
  val terveys: Option[Boolean]
  val yhteispisteet: Option[BigDecimal]
  val organisaatioParendOidPath: String
  val koulutuksenKieli: Option[String]

  def withPisteet(pisteet: Option[BigDecimal]): Hakutoive
}

sealed trait Lasnaolo
case class Lasna(kausi: Kausi) extends Lasnaolo
case class Poissa(kausi: Kausi) extends Lasnaolo
case class PoissaEiKulutaOpintoaikaa(kausi: Kausi) extends Lasnaolo
case class Puuttuu(kausi: Kausi) extends Lasnaolo

sealed trait Kausi
case class Kevat(vuosi:Int) extends Kausi
case class Syksy(vuosi:Int) extends Kausi


sealed trait Valittu

sealed trait IlmoitusLahetetty extends Valittu

sealed trait VastaanottanutPaikan extends IlmoitusLahetetty {

}

object Hakutoive{

  import Valintatila.isHyvaksytty
  import Vastaanottotila.isVastaanottanut

  def apply(ht: Hakutoive, valinta: Option[Valintatila], vastaanotto: Option[Vastaanottotila], ilmoittautumistila: Option[Ilmoittautumistila]) = (valinta, vastaanotto) match {
    case (Some(v), Some(Vastaanottotila.KESKEN)) if isHyvaksytty(v) => Hyvaksytty(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli)
    case (Some(v), Some(vt)) if isHyvaksytty(v) && isVastaanottanut(vt) => Vastaanottanut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli, ilmoittautumistila)
    case (Some(v), Some(Vastaanottotila.EI_VASTAANOTETTU_MAARA_AIKANA)) if isHyvaksytty(v) => EiVastaanotettu(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli)
    case (Some(v), Some(Vastaanottotila.PERUNUT)) if isHyvaksytty(v) => PerunutValinnan(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli)
    case (Some(v), Some(Vastaanottotila.PERUUTETTU)) if isHyvaksytty(v) => PeruutettuValinta(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli)
    case (Some(Valintatila.VARALLA), _) => Varalla(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli)
    case (Some(Valintatila.HYLATTY), _) => Hylatty(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli)
    case (Some(Valintatila.PERUUTETTU), _) => Peruutettu(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli)
    case (Some(Valintatila.PERUUNTUNUT), _)  => Peruuntunut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli)
    case (Some(Valintatila.PERUNUT), _) => Perunut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli)
    case (_, _) => didntMatchAnything(ht, valinta, vastaanotto, ilmoittautumistila)
  }

  private def didntMatchAnything(ht: Hakutoive, valinta: Option[Valintatila], vastaanotto: Option[Vastaanottotila], ilmoittautumistila: Option[Ilmoittautumistila]): Toive = {
    Toive(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath, ht.koulutuksenKieli)
  }
}

case class Toive(jno: Int, hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                 harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                 yhteispisteet: Option[BigDecimal] = None, organisaatioParendOidPath: String, koulutuksenKieli: Option[String]) extends Hakutoive {
  override def withPisteet(pisteet: Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Hyvaksytty(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, koulutuksenKieli: Option[String]) extends Hakutoive with Valittu {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class PerunutValinnan(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, koulutuksenKieli: Option[String]) extends Hakutoive with Valittu {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class PeruutettuValinta(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                           harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                           yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, koulutuksenKieli: Option[String]) extends Hakutoive with Valittu {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class EiVastaanotettu(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, koulutuksenKieli: Option[String]) extends Hakutoive with IlmoitusLahetetty {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class Vastaanottanut(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, koulutuksenKieli: Option[String], ilmoittautumistila: Option[Ilmoittautumistila]) extends Hakutoive with VastaanottanutPaikan {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class Varalla(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, koulutuksenKieli: Option[String]) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Hylatty(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, koulutuksenKieli: Option[String]) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Perunut(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, koulutuksenKieli: Option[String]) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Peruutettu(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, koulutuksenKieli: Option[String]) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Peruuntunut(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                       harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                       yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, koulutuksenKieli: Option[String]) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Hakemus(hakutoiveet: Seq[Hakutoive], hakemusnumero: String, julkaisulupa: Boolean, hakuOid: String, lisapistekoulutus: Option[String], liitteet: Seq[Liite], osaaminen: Osaaminen)

case class Hakija(henkilo: Henkilo, suoritukset: Seq[Suoritus], opiskeluhistoria: Seq[Opiskelija], hakemus: Hakemus)
object Hakija {
  val mies: Regex = "\\d{6}[-A]\\d{2}[13579].".r
  val nainen: Regex = "\\d{6}[-A]\\d{2}[24680].".r
  val valid: Regex = "([12])".r

  def resolveSukupuoli(hakija:Hakija):String = (hakija.henkilo.hetu, hakija.henkilo.sukupuoli) match {
    case (mies(), _) => "1"
    case (nainen(), _) => "2"
    case (_, valid(sukupuoli)) => sukupuoli
    case _ => "0"
  }
}



case class Osaaminen(yleinen_kielitutkinto_fi: Option[String], valtionhallinnon_kielitutkinto_fi: Option[String],
                     yleinen_kielitutkinto_sv: Option[String], valtionhallinnon_kielitutkinto_sv: Option[String],
                     yleinen_kielitutkinto_en: Option[String], valtionhallinnon_kielitutkinto_en: Option[String],
                     yleinen_kielitutkinto_se: Option[String], valtionhallinnon_kielitutkinto_se: Option[String])

class HakijaActor(hakupalvelu: Hakupalvelu, organisaatioActor: ActorRef, koodistoActor: ActorRef, valintaTulosActor: ActorRef) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher
  implicit val defaultTimeout: Timeout = 120.seconds
  val tuntematonOppilaitos = "00000"

  def receive = {
    case q: HakijaQuery => {
      Try(q.version match {
        case 1 => XMLQuery(q) pipeTo sender
        case 2 => JSONQuery(q) pipeTo sender
        case 3 => JSONQueryV3(q) pipeTo sender
      }) match {
        case Failure(fail) =>
          log.error(s"Unexpected failure ${fail}")
        case _ =>
      }
    }
    case something =>
      log.error(s"Unexpected query ${something}")
  }

  def resolveOppilaitosKoodi(o: Organisaatio): Future[Option[String]] = o.oppilaitosKoodi match {
    case None => findOppilaitoskoodi(o.parentOid)
    case Some(k) => Future.successful(Some(k))
  }

  def getOrg(oid: String): Future[Option[Organisaatio]] = {
    Try((organisaatioActor ? oid).mapTo[Option[Organisaatio]]).getOrElse(Future.successful(None))
  }

  def findOppilaitoskoodi(parentOid: Option[String]): Future[Option[String]] = parentOid match {
    case None => Future.successful(Some(tuntematonOppilaitos))
    case Some(oid) => getOrg(oid).flatMap(_.fold[Future[Option[String]]](Future.successful(Some(tuntematonOppilaitos)))(resolveOppilaitosKoodi))
  }

  def hakutoive2XMLHakutoive(ht: Hakutoive): Future[Option[XMLHakutoive]] = {
   for(
     orgData: Option[(Organisaatio, String)] <- findOrgData(ht.hakukohde.koulutukset.head.tarjoaja)
   ) yield
     for ((org: Organisaatio, oppilaitos: String) <- orgData)
       yield XMLHakutoive(ht, org, oppilaitos)
  }

  def getXmlHakutoiveet(hakija: Hakija): Future[Seq[XMLHakutoive]] = {
    val futureToiveet = for (ht <- hakija.hakemus.hakutoiveet) yield hakutoive2XMLHakutoive(ht)
    futureToiveet.join.map(_.flatten)
  }

  def extractOption(t: (Option[Organisaatio], Option[String])): Option[(Organisaatio, String)] = t._1 match {
    case None => None
    case Some(o) => Some((o, t._2.get))
  }

  def findOrgData(tarjoaja: String): Future[Option[(Organisaatio,String)]] = {
    getOrg(tarjoaja).flatMap((o) => findOppilaitoskoodi(o.map(_.oid)).map(k => extractOption(o, k)))
  }

  def createHakemus(hakija: Hakija)(opiskelija: Option[Opiskelija], org: Option[Organisaatio], ht: Seq[XMLHakutoive], os: Option[XMLOsaaminen]) = XMLHakemus(hakija, opiskelija, org, ht, os)

  def getXmlHakemus(hakija: Hakija): Future[XMLHakemus] = {
    val (opiskelutieto, lahtokoulu) = getOpiskelijaTiedot(hakija)
    val ht: Future[Seq[XMLHakutoive]] = getXmlHakutoiveet(hakija)
    val osaaminen: Future[Option[XMLOsaaminen]] = Future.successful(
      Option(XMLOsaaminen(hakija.hakemus.osaaminen.yleinen_kielitutkinto_fi, hakija.hakemus.osaaminen.valtionhallinnon_kielitutkinto_fi,
        hakija.hakemus.osaaminen.yleinen_kielitutkinto_sv, hakija.hakemus.osaaminen.valtionhallinnon_kielitutkinto_sv,
        hakija.hakemus.osaaminen.yleinen_kielitutkinto_en, hakija.hakemus.osaaminen.valtionhallinnon_kielitutkinto_en,
        hakija.hakemus.osaaminen.yleinen_kielitutkinto_se, hakija.hakemus.osaaminen.valtionhallinnon_kielitutkinto_se)))
    val data = (opiskelutieto, lahtokoulu, ht, osaaminen).join

    data.tupledMap(createHakemus(hakija))
  }

  def getOpiskelijaTiedot(hakija: Hakija): (Future[Option[Opiskelija]], Future[Option[Organisaatio]]) = hakija.opiskeluhistoria match {
    case opiskelijaTiedot :: _ => (Future.successful(Some(opiskelijaTiedot)), getOrg(opiskelijaTiedot.oppilaitosOid))
    case _ => (Future.successful(None), Future.successful(None))
  }

  def getMaakoodi(koodiArvo: String): Future[String] = koodiArvo.toLowerCase match {
    case "fin" => Future.successful("246")
    case arvo =>
      val maaFuture = (koodistoActor ? GetRinnasteinenKoodiArvoQuery("maatjavaltiot1", arvo, "maatjavaltiot2")).mapTo[String]
      maaFuture.onFailure {
        case t: Throwable => log.error(t, s"failed to fetch country $koodiArvo")
      }
      maaFuture
  }

  def getPostitoimipaikka(maa: String, postitoimipaikka: String, postinumero: String): Future[String] = maa match {
    case "246" =>
      val postitoimipaikkaFuture = (koodistoActor ? GetKoodi("posti", s"posti_$postinumero")).mapTo[Option[Koodi]]
      postitoimipaikkaFuture.onFailure {
        case t: Throwable => log.error(t, s"failed to fetch postoffice for code $postinumero")
      }
      postitoimipaikkaFuture.map(koodi => {
        koodi
          .map(_.metadata.find(_.kieli.toLowerCase == "fi")
            .map(_.nimi)
            .getOrElse(""))
          .getOrElse("")
      })
    case arvo => Future.successful(postitoimipaikka)
  }

  def hakija2XMLHakija(hakija: Hakija): Future[XMLHakija] = {
    getXmlHakemus(hakija).map(data2XmlHakija(hakija))
  }

  def hakija2JSONHakija(hakija: Hakija): Future[JSONHakija] = {
    getXmlHakemus(hakija).map(data2JsonHakija(hakija))
  }

  def hakija2JSONHakijaV3(hakija: Hakija): Future[JSONHakija] = {
    getXmlHakemus(hakija).map(data2JsonHakijaV3(hakija))
  }

  def data2XmlHakija(hakija: Hakija)(hakemus: XMLHakemus) = {
    val hakutoiveet2 = hakemus.hakutoiveet.map(toive => toive.copy(koulutuksenKieli = None))
    val hakemus2 = hakemus.copy(osaaminen = None, hakutoiveet = hakutoiveet2)
    XMLHakija(hakija, hakemus2)
  }

  def data2JsonHakijaV3(hakija: Hakija)(hakemus: XMLHakemus) = {
    JSONHakija(hakija, hakemus)
  }

  def data2JsonHakija(hakija: Hakija)(hakemus: XMLHakemus) = {
    val hakutoiveet2 = hakemus.hakutoiveet.map(toive => toive.copy(koulutuksenKieli = None))
    val hakemus2 = hakemus.copy(osaaminen = None, hakutoiveet = hakutoiveet2)
    JSONHakija(hakija, hakemus2)
  }

  def hakijat2XmlHakijat(hakijat: Seq[Hakija]): Future[Seq[XMLHakija]] =
    hakijat.map(hakija2XMLHakija).join

  def hakijat2JsonHakijatV2(hakijat: Seq[Hakija]): Future[Seq[JSONHakija]] =
    hakijat.map(hakija2JSONHakija).join

  def hakijat2JsonHakijatV3(hakijat: Seq[Hakija]): Future[Seq[JSONHakija]] =
    hakijat.map(hakija2JSONHakijaV3).join

  def matchSijoitteluAndHakemus(hakijas: Seq[Hakija])(tulos: SijoitteluTulos): Seq[Hakija] =
    hakijas.map(tila(tulos.valintatila, tulos.vastaanottotila, tulos.ilmoittautumistila)).map(yhteispisteet(tulos.pisteet))

  def yhteispisteet(pisteet: (String, String) => Option[BigDecimal])(h:Hakija) : Hakija = {
    val toiveet = h.hakemus.hakutoiveet.map((ht) => {
      val oid: String = ht.hakukohde.oid
      val yhteispisteet: Option[BigDecimal] = pisteet(h.hakemus.hakemusnumero, oid)
      ht withPisteet yhteispisteet
    })
    h.copy(hakemus = h.hakemus.copy(hakutoiveet = toiveet))
  }

  def tila(valinta: (String, String) => Option[Valintatila], vastaanotto: (String, String) => Option[Vastaanottotila], ilmoittautumistila: (String,String) => Option[Ilmoittautumistila])(h:Hakija): Hakija = {
    val hakemusnumero: String = h.hakemus.hakemusnumero
    h.copy(hakemus =
      h.hakemus.copy(hakutoiveet =
        for (ht <- h.hakemus.hakutoiveet)
          yield Hakutoive(ht, valinta(hakemusnumero, ht.hakukohde.oid), vastaanotto(hakemusnumero, ht.hakukohde.oid), ilmoittautumistila(hakemusnumero, ht.hakukohde.oid))))
  }

  def combine2sijoittelunTulos(user: Option[User])(hakijat: Seq[Hakija]): Future[Seq[Hakija]] = Future.fold(
    hakijat.groupBy(_.hakemus.hakuOid).
      map { case (hakuOid, hakijas) => (valintaTulosActor ? ValintaTulosQuery(hakuOid, None)).mapTo[SijoitteluTulos].map(matchSijoitteluAndHakemus(hakijas))}
  )(Seq[Hakija]())(_ ++ _)


  def hakutoiveFilter(predicate: (XMLHakutoive) => Boolean)(xh: XMLHakija): XMLHakija = xh.copy(hakemus = xh.hakemus.copy(hakutoiveet = xh.hakemus.hakutoiveet.filter(predicate)))

  def matchOrganisaatio(oid: Option[String], parentOidPath: String): Boolean = oid match {
    case Some(o) => parentOidPath.isEmpty || parentOidPath.split(",").contains(o)
    case None => true
  }

  def matchHakukohdekoodi(koodi: Option[String], koulutuskoodi: String): Boolean = koodi match {
    case Some(k) => koulutuskoodi == k || k.split('_').last == koulutuskoodi
    case None => true
  }

  def matchesHakukohdeKoodi(h: Hakutoive, q: HakijaQuery): Boolean = {
    def getSuffix(s: String): String = s.split('_').last

    q.hakukohdekoodi.isEmpty || getSuffix(h.hakukohde.hakukohdekoodi) == getSuffix(q.hakukohdekoodi.get)
  }

  def matchesOrganisation(h: Hakutoive, q: HakijaQuery) = q.organisaatio.isEmpty || h.organisaatioParendOidPath.contains(q.organisaatio.get)

  def filterByQuery(q: HakijaQuery)(toiveet: Seq[Hakutoive]): Seq[Hakutoive] = {
    val hakutoives: Seq[Hakutoive] = q.hakuehto match {
      case Hakuehto.Kaikki => toiveet
      case Hakuehto.Hyvaksytyt => toiveet.collect {
        case ht: Valittu if matchOrganisaatio(q.organisaatio, ht.organisaatioParendOidPath) && matchHakukohdekoodi(q.hakukohdekoodi, ht.hakukohde.hakukohdekoodi) => ht
      }
      case Hakuehto.Vastaanottaneet => toiveet.collect {
        case ht: Vastaanottanut if matchOrganisaatio(q.organisaatio, ht.organisaatioParendOidPath) && matchHakukohdekoodi(q.hakukohdekoodi, ht.hakukohde.hakukohdekoodi) => ht
      }
      case Hakuehto.Hylatyt => toiveet.collect {
        case ht: Hylatty if matchOrganisaatio(q.organisaatio, ht.organisaatioParendOidPath) && matchHakukohdekoodi(q.hakukohdekoodi, ht.hakukohde.hakukohdekoodi) => ht
      }
    }
    if (q.version == 2 || q.version == 3)
      hakutoives.filter(h => matchesHakukohdeKoodi(h, q) && matchesOrganisation(h, q))
    else
      hakutoives
  }

  def filterHakijatHakutoiveetByQuery(q: HakijaQuery)(hakijat: Seq[Hakija]): Future[Seq[Hakija]] = {
    Future.successful(hakijat.flatMap(filterHakutoiveetByQuery(q)(_)))
  }

  def filterHakutoiveetByQuery(q: HakijaQuery)(hakija: Hakija): Option[Hakija] = {
    val filteredHakutoiveet = filterByQuery(q)(hakija.hakemus.hakutoiveet)
    if(filteredHakutoiveet.nonEmpty) {
      Some(hakija.copy(hakija.henkilo, hakija.suoritukset, hakija.opiskeluhistoria, hakemus =
          hakija.hakemus.copy(hakutoiveet = filteredHakutoiveet)))
    } else {
      None
    }
  }

  def enrichHakijat(hakijat: Seq[Hakija]): Future[Seq[Hakija]] = Future.sequence(for {
    hakija <- hakijat
  } yield for {
      kansalaisuus <- getMaakoodi(hakija.henkilo.kansalaisuus)
      maa <- getMaakoodi(hakija.henkilo.maa)
      postitoimipaikka <- getPostitoimipaikka(maa, hakija.henkilo.postitoimipaikka, hakija.henkilo.postinumero)
    } yield {
      val h = hakija.henkilo
      Hakija(
        henkilo = Henkilo(
          hetu = h.hetu,
          syntymaaika = h.syntymaaika,
          oppijanumero = h.oppijanumero,
          sukupuoli = h.sukupuoli,
          sukunimi = h.sukunimi,
          etunimet = h.etunimet,
          kutsumanimi = h.kutsumanimi,
          turvakielto = h.turvakielto,
          lahiosoite = h.lahiosoite,
          postinumero = h.postinumero,
          maa = maa,
          postitoimipaikka = postitoimipaikka,
          matkapuhelin = h.matkapuhelin,
          puhelin = h.puhelin,
          sahkoposti = h.sahkoposti,
          kotikunta = h.kotikunta,
          kansalaisuus = kansalaisuus,
          kaksoiskansalaisuus = h.kaksoiskansalaisuus,
          asiointiKieli = h.asiointiKieli,
          eiSuomalaistaHetua = h.eiSuomalaistaHetua,
          markkinointilupa = h.markkinointilupa,
          kiinnostunutoppisopimuksesta = h.kiinnostunutoppisopimuksesta,
          huoltajannimi = h.huoltajannimi,
          huoltajanpuhelinnumero = h.huoltajanpuhelinnumero,
          huoltajansahkoposti = h.huoltajansahkoposti,
          lisakysymykset = h.lisakysymykset,
          liitteet = h.liitteet,
          muukoulutus = h.muukoulutus
        ),
        suoritukset = hakija.suoritukset,
        opiskeluhistoria = hakija.opiskeluhistoria,
        hakemus = hakija.hakemus
      )
    })

  def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = {
    hakupalvelu.getHakijat(q)
      .flatMap(enrichHakijat)
      .flatMap(combine2sijoittelunTulos(q.user))
      .flatMap(filterHakijatHakutoiveetByQuery(q))
  }

  val hakijaWithValittu: (XMLHakija) => XMLHakija = hakutoiveFilter(_.valinta == Some("1"))

  val hakijaWithVastaanotettu = hakutoiveFilter(_.vastaanotto == Some("3")) _

  def XMLQuery(q: HakijaQuery): Future[XMLHakijat] = getHakijat(q).flatMap(hakijat2XmlHakijat).map(XMLHakijat)
  def JSONQuery(q: HakijaQuery): Future[JSONHakijat] = getHakijat(q).flatMap(hakijat2JsonHakijatV2).map(JSONHakijat)
  def JSONQueryV3(q: HakijaQuery): Future[JSONHakijat] = getHakijat(q).flatMap(hakijat2JsonHakijatV3).map(JSONHakijat)
}

case class HakijaQuery(haku: Option[String], organisaatio: Option[String], hakukohdekoodi: Option[String], hakuehto: Hakuehto.Hakuehto, user: Option[User], version: Int) extends Query


object Hakuehto extends Enumeration {
  type Hakuehto = Value
  val Kaikki, Hyvaksytyt, Vastaanottaneet, Hylatyt = Value
}