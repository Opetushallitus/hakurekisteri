package fi.vm.sade.hakurekisteri.hakija

import akka.actor.{ActorLogging, ActorRef, Actor}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.Hakupalvelu
import fi.vm.sade.hakurekisteri.integration.valintatulos._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila._
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import scala.util.Try
import akka.pattern.{pipe, ask}
import ForkedSeq._
import TupledFuture._
import fi.vm.sade.hakurekisteri.rest.support.User
import scala.xml.Node
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.integration.valintatulos.ValintaTulosQuery
import scala.Some
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.suoritus.Komoto
import fi.vm.sade.hakurekisteri.integration.koodisto.{KoodiMetadata, Koodi, GetKoodi, GetRinnasteinenKoodiArvoQuery}
import fi.vm.sade.hakurekisteri.suoritus.VirallinenSuoritus
import java.text.SimpleDateFormat
import fi.vm.sade.hakurekisteri.tools.RicherString


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
  val lasna: Seq[Lasnaolo]
}

object Hakutoive{
  import fi.vm.sade.hakurekisteri.rest.support.Kausi

  private def resolveLasnaolot(lasna: Boolean)(ht: Hakutoive): Seq[Lasnaolo] = ht.hakukohde.koulutukset.map((komoto) => (lasna, komoto.alkamisvuosi, komoto.alkamiskausi)).flatMap {
    case (true, Some(vuosi), Some(Kausi.Syksy)) => Try(Lasna(Syksy(vuosi.toInt))).toOption
    case (false, Some(vuosi), Some(Kausi.Syksy)) => Try(Poissa(Syksy(vuosi.toInt))).toOption
    case (true, Some(vuosi), Some(Kausi.Kevät)) => Try(Lasna(Kevat(vuosi.toInt))).toOption
    case (false, Some(vuosi), Some(Kausi.Kevät)) => Try(Poissa(Kevat(vuosi.toInt))).toOption
    case _ => None
  }.toSeq

  import Valintatila.isHyvaksytty
  import Vastaanottotila.isVastaanottanut

  def apply(ht: Hakutoive, valinta: Option[Valintatila], vastaanotto: Option[Vastaanottotila]) = (valinta, vastaanotto) match {
    case (Some(v), Some(Vastaanottotila.KESKEN)) if isHyvaksytty(v) => Hyvaksytty(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
    case (Some(v), Some(vt)) if isHyvaksytty(v) && isVastaanottanut(vt) => Vastaanottanut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
    case (Some(v), Some(Vastaanottotila.EI_VASTAANOTETTU_MAARA_AIKANA)) if isHyvaksytty(v) => EiVastaanotettu(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
    case (Some(v), Some(Vastaanottotila.PERUNUT)) if isHyvaksytty(v) => PerunutValinnan(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
    case (Some(v), Some(Vastaanottotila.PERUUTETTU)) if isHyvaksytty(v) => PeruutettuValinta(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
    case (Some(Valintatila.VARALLA), _) => Varalla(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
    case (Some(Valintatila.HYLATTY), _) => Hylatty(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
    case (Some(Valintatila.PERUUTETTU), _) => Peruutettu(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
    case (Some(Valintatila.PERUUNTUNUT), _)  => Peruuntunut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
    case (Some(Valintatila.PERUNUT), _) => Perunut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
    case (_, _) =>
      Toive(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, ht.organisaatioParendOidPath)
  }
}

case class Toive(jno: Int, hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                 harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                 yhteispisteet: Option[BigDecimal] = None, organisaatioParendOidPath: String) extends Hakutoive {
  override def withPisteet(pisteet: Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Hyvaksytty(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String) extends Hakutoive with Valittu {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class PerunutValinnan(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String) extends Hakutoive with Valittu {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class PeruutettuValinta(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                           harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                           yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String) extends Hakutoive with Valittu {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class EiVastaanotettu(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String) extends Hakutoive with IlmoitusLahetetty {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class Vastaanottanut(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String, lasna: Seq[Lasnaolo] = Seq()) extends Hakutoive with VastaanottanutPaikan {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class Varalla(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Hylatty(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Perunut(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Peruutettu(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Peruuntunut(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                       harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                       yhteispisteet: Option[BigDecimal], organisaatioParendOidPath: String) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Hakemus(hakutoiveet: Seq[Hakutoive], hakemusnumero: String, julkaisulupa: Boolean, hakuOid: String, lisapistekoulutus: Option[String])

case class Hakija(henkilo: Henkilo, suoritukset: Seq[Suoritus], opiskeluhistoria: Seq[Opiskelija], hakemus: Hakemus)

class HakijaActor(hakupalvelu: Hakupalvelu, organisaatioActor: ActorRef, koodistoActor: ActorRef, valintaTulosActor: ActorRef) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher
  implicit val defaultTimeout: Timeout = 120.seconds
  val tuntematonOppilaitos = "00000"

  def receive = {
    case q: HakijaQuery => {
      q.version match {
        case 1 => XMLQuery(q) pipeTo sender
        case 2 => JSONQuery(q) pipeTo sender
      }
    }
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

  def createHakemus(hakija: Hakija)(opiskelija: Option[Opiskelija], org: Option[Organisaatio], ht: Seq[XMLHakutoive]) = XMLHakemus(hakija, opiskelija, org, ht)

  def getXmlHakemus(hakija: Hakija): Future[XMLHakemus] = {
    val (opiskelutieto, lahtokoulu) = getOpiskelijaTiedot(hakija)
    val ht: Future[Seq[XMLHakutoive]] = getXmlHakutoiveet(hakija)
    val data = (opiskelutieto, lahtokoulu, ht).join

    data.tupledMap(createHakemus(hakija))
  }

  def getOpiskelijaTiedot(hakija: Hakija): (Future[Option[Opiskelija]], Future[Option[Organisaatio]]) = hakija.opiskeluhistoria match {
    case opiskelijaTiedot :: _ => (Future.successful(Some(opiskelijaTiedot)), getOrg(opiskelijaTiedot.oppilaitosOid))
    case _ => (Future.successful(None), Future.successful(None))
  }

  def getMaakoodi(koodiArvo: String): Future[String] = koodiArvo.toLowerCase match {
    case "fin" => Future.successful("246")
    case arvo =>
      val maaFuture = (koodistoActor ? GetRinnasteinenKoodiArvoQuery("maatjavaltiot1_" + arvo, "maatjavaltiot2")).mapTo[String]
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

  def data2XmlHakija(hakija: Hakija)(hakemus: XMLHakemus) =
    XMLHakija(hakija, hakemus)

  def data2JsonHakija(hakija: Hakija)(hakemus: XMLHakemus) =
    JSONHakija(hakija, hakemus)

  def hakijat2XmlHakijat(hakijat: Seq[Hakija]): Future[Seq[XMLHakija]] =
    hakijat.map(hakija2XMLHakija).join

  def hakijat2JsonHakijat(hakijat: Seq[Hakija]): Future[Seq[JSONHakija]] =
    hakijat.map(hakija2JSONHakija).join

  def matchSijoitteluAndHakemus(hakijas: Seq[Hakija])(tulos: SijoitteluTulos): Seq[Hakija] =
    hakijas.map(tila(tulos.valintatila, tulos.vastaanottotila)).map(yhteispisteet(tulos.pisteet))

  def yhteispisteet(pisteet: (String, String) => Option[BigDecimal])(h:Hakija) : Hakija = {
    val toiveet = h.hakemus.hakutoiveet.map((ht) => {
      val oid: String = ht.hakukohde.oid
      val yhteispisteet: Option[BigDecimal] = pisteet(h.hakemus.hakemusnumero, oid)
      ht withPisteet yhteispisteet
    })
    h.copy(hakemus = h.hakemus.copy(hakutoiveet = toiveet))
  }

  def tila(valinta: (String, String) => Option[Valintatila], vastaanotto: (String, String) => Option[Vastaanottotila])(h:Hakija): Hakija = {
    val hakemusnumero: String = h.hakemus.hakemusnumero
    h.copy(hakemus =
      h.hakemus.copy(hakutoiveet =
        for (ht <- h.hakemus.hakutoiveet)
          yield Hakutoive(ht, valinta(hakemusnumero, ht.hakukohde.oid), vastaanotto(hakemusnumero, ht.hakukohde.oid))))
  }

  def combine2sijoittelunTulos(user: Option[User])(hakijat: Seq[Hakija]): Future[Seq[Hakija]] = Future.fold(
    hakijat.groupBy(_.hakemus.hakuOid).
      map { case (hakuOid, hakijas) => (valintaTulosActor ? ValintaTulosQuery(hakuOid, None)).mapTo[SijoitteluTulos].map(matchSijoitteluAndHakemus(hakijas))}
  )(Seq[Hakija]())(_ ++ _)


  def hakutoiveFilter(predicate: (XMLHakutoive) => Boolean)(xh: XMLHakija): XMLHakija = xh.copy(hakemus = xh.hakemus.copy(hakutoiveet = xh.hakemus.hakutoiveet.filter(predicate)))

  def matchOrganisaatio(oid: Option[String], parentOidPath: String): Boolean = oid match {
    case Some(o) => parentOidPath.split(",").contains(o)
    case None => true
  }

  def matchHakukohdekoodi(koodi: Option[String], koulutuskoodi: String): Boolean = koodi match {
    case Some(k) => koulutuskoodi == k
    case None => true
  }

  def filterByQuery(q: HakijaQuery)(toiveet: Seq[Hakutoive]): Seq[Hakutoive] = q.hakuehto match {
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

  def filterHakutoiveetByQuery(q: HakijaQuery)(hakija: Hakija): Hakija = {
    hakija.copy(hakija.henkilo, hakija.suoritukset, hakija.opiskeluhistoria, hakemus = hakija.hakemus.copy(filterByQuery(q)(hakija.hakemus.hakutoiveet), hakija.hakemus.hakemusnumero, hakija.hakemus.julkaisulupa, hakija.hakemus.hakuOid, hakija.hakemus.lisapistekoulutus))
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
          lahiosoite = h.lahiosoite,
          postinumero = h.postinumero,
          maa = maa,
          postitoimipaikka = postitoimipaikka,
          matkapuhelin = h.matkapuhelin,
          puhelin = h.puhelin,
          sahkoposti = h.sahkoposti,
          kotikunta = h.kotikunta,
          kansalaisuus = kansalaisuus,
          asiointiKieli = h.asiointiKieli,
          eiSuomalaistaHetua = h.eiSuomalaistaHetua,
          markkinointilupa = h.markkinointilupa,
          kiinnostunutoppisopimuksesta = h.kiinnostunutoppisopimuksesta,
          huoltajannimi = h.huoltajannimi,
          huoltajanpuhelinnumero = h.huoltajanpuhelinnumero,
          huoltajansahkoposti = h.huoltajansahkoposti,
          lisakysymykset = h.lisakysymykset
        ),
        suoritukset = hakija.suoritukset,
        opiskeluhistoria = hakija.opiskeluhistoria,
        hakemus = hakija.hakemus
      )
    })

  def getHakijat(q: HakijaQuery): Future[Seq[XMLHakija]] = {
    hakupalvelu.getHakijat(q).flatMap(enrichHakijat).flatMap(combine2sijoittelunTulos(q.user)).flatMap(hakijat => hakijat2XmlHakijat(hakijat.map(filterHakutoiveetByQuery(q))))
  }
  def getHakijatV2(q: HakijaQuery): Future[Seq[JSONHakija]] = {
    hakupalvelu.getHakijat(q).flatMap(enrichHakijat).flatMap(combine2sijoittelunTulos(q.user)).flatMap(hakijat => hakijat2JsonHakijat(hakijat.map(filterHakutoiveetByQuery(q))))
  }

  val hakijaWithValittu: (XMLHakija) => XMLHakija = hakutoiveFilter(_.valinta == Some("1"))

  val hakijaWithVastaanotettu = hakutoiveFilter(_.vastaanotto == Some("3")) _

  def XMLQuery(q: HakijaQuery): Future[XMLHakijat] = getHakijat(q).map((hakijat) => XMLHakijat(hakijat.filter(_.hakemus.hakutoiveet.nonEmpty)))
  def JSONQuery(q: HakijaQuery): Future[JSONHakijat] = getHakijatV2(q).map((hakijat) => JSONHakijat(hakijat.filter(_.hakemus.hakutoiveet.nonEmpty)))
}



object XMLUtil {
  def toBooleanX(b: Boolean): String = if (b) "X" else ""
  def toBoolean10(b: Boolean): String = if (b) "1" else "0"
}


import XMLUtil._

case class XMLHakutoive(hakujno: Short, oppilaitos: String, opetuspiste: Option[String], opetuspisteennimi: Option[String], koulutus: String,
                        harkinnanvaraisuusperuste: Option[String], urheilijanammatillinenkoulutus: Option[Boolean], yhteispisteet: Option[BigDecimal],
                        valinta: Option[String], vastaanotto: Option[String], lasnaolo: Option[String], terveys: Option[Boolean], aiempiperuminen: Option[Boolean],
                        kaksoistutkinto: Option[Boolean]) {
  def toXml: Node = {
    <Hakutoive>
      <Hakujno>{hakujno}</Hakujno>
      <Oppilaitos>{oppilaitos}</Oppilaitos>
      {if (opetuspiste.isDefined) <Opetuspiste>{opetuspiste.get}</Opetuspiste>}
      {if (opetuspisteennimi.isDefined) <Opetuspisteennimi>{opetuspisteennimi.get}</Opetuspisteennimi>}
      <Koulutus>{koulutus}</Koulutus>
      {if (harkinnanvaraisuusperuste.isDefined) <Harkinnanvaraisuusperuste>{harkinnanvaraisuusperuste.get}</Harkinnanvaraisuusperuste>}
      {if (urheilijanammatillinenkoulutus.isDefined) <Urheilijanammatillinenkoulutus>{toBoolean10(urheilijanammatillinenkoulutus.get)}</Urheilijanammatillinenkoulutus>}
      {if (yhteispisteet.isDefined) <Yhteispisteet>{yhteispisteet.get}</Yhteispisteet>}
      {if (valinta.isDefined) <Valinta>{valinta.get}</Valinta>}
      {if (vastaanotto.isDefined) <Vastaanotto>{vastaanotto.get}</Vastaanotto>}
      {if (lasnaolo.isDefined) <Lasnaolo>{lasnaolo.get}</Lasnaolo>}
      {if (terveys.isDefined) <Terveys>{toBooleanX(terveys.get)}</Terveys>}
      {if (aiempiperuminen.isDefined) <Aiempiperuminen>{toBooleanX(aiempiperuminen.get)}</Aiempiperuminen>}
      {if (kaksoistutkinto.isDefined) <Kaksoistutkinto>{toBooleanX(kaksoistutkinto.get)}</Kaksoistutkinto>}
    </Hakutoive>
  }
}

object XMLHakutoive {
  def apply(ht: Hakutoive, o: Organisaatio, k: String): XMLHakutoive = XMLHakutoive(ht.jno.toShort, k, o.toimipistekoodi, o.nimi.get("fi").orElse(o.nimi.get("sv").orElse(o.nimi.get("en"))),
    ht.hakukohde.hakukohdekoodi, ht.harkinnanvaraisuusperuste, ht.urheilijanammatillinenkoulutus,
    ht.yhteispisteet, valinta.lift(ht), vastaanotto.lift(ht), None,
    ht.terveys, ht.aiempiperuminen, ht.kaksoistutkinto)

  def valinta: PartialFunction[Hakutoive, String] = {
    case v: Valittu     => "1"
    case v: Varalla     => "2"
    case v: Hylatty     => "3"
    case v: Perunut     => "4"
    case v: Peruuntunut => "4"
    case v: Peruutettu  => "5"
  }

  def vastaanotto: PartialFunction[Hakutoive, String] = {
    case v: Hyvaksytty        => "1"
    case v: Vastaanottanut    => "3"
    case v: PerunutValinnan   => "4"
    case v: EiVastaanotettu   => "5"
    case v: PeruutettuValinta => "6"
  }
}

case class XMLHakemus(vuosi: String, kausi: String, hakemusnumero: String, lahtokoulu: Option[String], lahtokoulunnimi: Option[String], luokka: Option[String],
                      luokkataso: Option[String], pohjakoulutus: String, todistusvuosi: Option[String], julkaisulupa: Option[Boolean], yhteisetaineet: Option[BigDecimal],
                      lukiontasapisteet: Option[BigDecimal], lisapistekoulutus: Option[String], yleinenkoulumenestys: Option[BigDecimal],
                      painotettavataineet: Option[BigDecimal], hakutoiveet: Seq[XMLHakutoive]) {
  def toXml: Node = {
    <Hakemus>
      <Vuosi>{vuosi}</Vuosi>
      <Kausi>{kausi}</Kausi>
      <Hakemusnumero>{hakemusnumero}</Hakemusnumero>
      {if (lahtokoulu.isDefined) <Lahtokoulu>{lahtokoulu.get}</Lahtokoulu>}
      {if (lahtokoulunnimi.isDefined) <Lahtokoulunnimi>{lahtokoulunnimi.get}</Lahtokoulunnimi>}
      {if (luokka.isDefined) <Luokka>{luokka.get}</Luokka>}
      {if (luokkataso.isDefined) <Luokkataso>{luokkataso.get}</Luokkataso>}
      <Pohjakoulutus>{pohjakoulutus}</Pohjakoulutus>
      {if (todistusvuosi.isDefined) <Todistusvuosi>{todistusvuosi.get}</Todistusvuosi>}
      {if (julkaisulupa.isDefined) <Julkaisulupa>{toBooleanX(julkaisulupa.get)}</Julkaisulupa>}
      {if (yhteisetaineet.isDefined) <Yhteisetaineet>{yhteisetaineet.get}</Yhteisetaineet>}
      {if (lukiontasapisteet.isDefined) <Lukiontasapisteet>{lukiontasapisteet.get}</Lukiontasapisteet>}
      {if (lisapistekoulutus.isDefined) <Lisapistekoulutus>{lisapistekoulutus.get}</Lisapistekoulutus>}
      {if (yleinenkoulumenestys.isDefined) <Yleinenkoulumenestys>{yleinenkoulumenestys.get}</Yleinenkoulumenestys>}
      {if (painotettavataineet.isDefined) <Painotettavataineet>{painotettavataineet.get}</Painotettavataineet>}
      <Hakutoiveet>
        {hakutoiveet.map(_.toXml)}
      </Hakutoiveet>
    </Hakemus>
  }
}

object XMLHakemus {
  def resolvePohjakoulutus(suoritus: Option[VirallinenSuoritus]): String = suoritus match {
    case Some(s) =>
      s.komo match {
        case "ulkomainen" => "0"
        case "peruskoulu" => s.yksilollistaminen match {
          case Ei => "1"
          case Osittain => "2"
          case Alueittain => "3"
          case Kokonaan => "6"
        }
        case "lukio" => "9"
      }
    case None => "7"
  }

  def getRelevantSuoritus(suoritukset:Seq[Suoritus]): Option[VirallinenSuoritus] = {
    suoritukset.collect{case s: VirallinenSuoritus => (s, resolvePohjakoulutus(Some(s)).toInt)}.sortBy(_._2).map(_._1).headOption
  }

  def resolveYear(suoritus: VirallinenSuoritus):Option[String] = suoritus match {
    case VirallinenSuoritus("ulkomainen", _,  _, _, _, _, _, _,  _, _) => None
    case VirallinenSuoritus(_, _, _,date, _, _, _,_,  _, _)  => Some(date.getYear.toString)
  }

  def apply(hakija: Hakija, opiskelutieto: Option[Opiskelija], lahtokoulu: Option[Organisaatio], toiveet: Seq[XMLHakutoive]): XMLHakemus =
    XMLHakemus(vuosi = hakija.hakemus.hakutoiveet.headOption.flatMap(_.hakukohde.koulutukset.headOption.flatMap(_.alkamisvuosi)).getOrElse(""),
      kausi = hakija.hakemus.hakutoiveet.headOption.flatMap(_.hakukohde.koulutukset.headOption.flatMap(_.alkamiskausi.map(_.toString))).getOrElse(""),
      hakemusnumero = hakija.hakemus.hakemusnumero,
      lahtokoulu = lahtokoulu.flatMap(o => o.oppilaitosKoodi),
      lahtokoulunnimi = lahtokoulu.flatMap(o => o.nimi.get("fi")),
      luokka = opiskelutieto.map(_.luokka),
      luokkataso = opiskelutieto.map(_.luokkataso),
      pohjakoulutus = resolvePohjakoulutus(getRelevantSuoritus(hakija.suoritukset)),
      todistusvuosi = getRelevantSuoritus(hakija.suoritukset).flatMap(resolveYear),
      julkaisulupa = Some(hakija.hakemus.julkaisulupa),
      yhteisetaineet = None,
      lukiontasapisteet = None,
      lisapistekoulutus = hakija.hakemus.lisapistekoulutus,
      yleinenkoulumenestys = None,
      painotettavataineet = None,
      hakutoiveet = toiveet)
}

case class XMLHakija(hetu: String, oppijanumero: String, sukunimi: String, etunimet: String, kutsumanimi: Option[String], lahiosoite: String,
                     postinumero: String, postitoimipaikka: String, maa: String, kansalaisuus: String, matkapuhelin: Option[String],
                     muupuhelin: Option[String], sahkoposti: Option[String], kotikunta: Option[String], sukupuoli: String,
                     aidinkieli: String, koulutusmarkkinointilupa: Boolean, hakemus: XMLHakemus) {
  def toXml: Node = {
    <Hakija>
      <Hetu>{hetu}</Hetu>
      <Oppijanumero>{oppijanumero}</Oppijanumero>
      <Sukunimi>{sukunimi}</Sukunimi>
      <Etunimet>{etunimet}</Etunimet>
      {if (kutsumanimi.isDefined) <Kutsumanimi>{kutsumanimi.get}</Kutsumanimi>}
      <Lahiosoite>{lahiosoite}</Lahiosoite>
      <Postinumero>{postinumero}</Postinumero>
      <Postitoimipaikka>{postitoimipaikka}</Postitoimipaikka>
      <Maa>{maa}</Maa>
      <Kansalaisuus>{kansalaisuus}</Kansalaisuus>
      {if (matkapuhelin.isDefined) <Matkapuhelin>{matkapuhelin.get}</Matkapuhelin>}
      {if (muupuhelin.isDefined) <Muupuhelin>{muupuhelin.get}</Muupuhelin>}
      {if (sahkoposti.isDefined) <Sahkoposti>{sahkoposti.get}</Sahkoposti>}
      {if (kotikunta.isDefined) <Kotikunta>{kotikunta.get}</Kotikunta>}
      <Sukupuoli>{sukupuoli}</Sukupuoli>
      <Aidinkieli>{aidinkieli}</Aidinkieli>
    </Hakija>
  }
}

case class JSONHakija(hetu: String, oppijanumero: String, sukunimi: String, etunimet: String, kutsumanimi: Option[String], lahiosoite: String,
                      postinumero: String, postitoimipaikka: String, maa: String, kansalaisuus: String, matkapuhelin: Option[String],
                      muupuhelin: Option[String], sahkoposti: Option[String], kotikunta: Option[String], sukupuoli: String,
                      aidinkieli: String, koulutusmarkkinointilupa: Boolean, kiinnostunutoppisopimuksesta: Boolean, huoltajannimi: Option[String],
                      huoltajanpuhelinnumero: Option[String], huoltajansahkoposti: Option[String], hakemus: XMLHakemus, lisakysymykset: Seq[Lisakysymys])

object XMLHakija {
  val mies = "\\d{6}[-A]\\d{2}[13579].".r
  val nainen = "\\d{6}[-A]\\d{2}[24680].".r
  val valid = "([12])".r

  def resolveSukupuoli(hakija:Hakija):String = (hakija.henkilo.hetu, hakija.henkilo.sukupuoli) match {
    case (mies(), _) => "1"
    case (nainen(), _) => "2"
    case (_, valid(sukupuoli)) => sukupuoli
    case _ => "0"
  }


  import RicherString._

  def apply(hakija: Hakija, hakemus: XMLHakemus): XMLHakija =
    XMLHakija(
      hetu = hetu(hakija.henkilo.hetu, hakija.henkilo.syntymaaika),
      oppijanumero = hakija.henkilo.oppijanumero,
      sukunimi = hakija.henkilo.sukunimi,
      etunimet = hakija.henkilo.etunimet,
      kutsumanimi = hakija.henkilo.kutsumanimi.blankOption,
      lahiosoite = hakija.henkilo.lahiosoite,
      postinumero = hakija.henkilo.postinumero,
      postitoimipaikka = hakija.henkilo.postitoimipaikka,
      maa = hakija.henkilo.maa,
      kansalaisuus = hakija.henkilo.kansalaisuus,
      matkapuhelin = hakija.henkilo.matkapuhelin.blankOption,
      muupuhelin = hakija.henkilo.puhelin.blankOption,
      sahkoposti = hakija.henkilo.sahkoposti.blankOption,
      kotikunta = hakija.henkilo.kotikunta.blankOption,
      sukupuoli = resolveSukupuoli(hakija),
      aidinkieli = hakija.henkilo.asiointiKieli,
      koulutusmarkkinointilupa = hakija.henkilo.markkinointilupa.getOrElse(false),
      hakemus = hakemus
    )

  def hetu(hetu: String, syntymaaika: String): String = hetu match {
    case "" => Try(new SimpleDateFormat("ddMMyyyy").format(new SimpleDateFormat("dd.MM.yyyy").parse(syntymaaika))).getOrElse("")
    case _ => hetu
  }

}

object JSONHakija {
  val mies = "\\d{6}[-A]\\d{2}[13579].".r
  val nainen = "\\d{6}[-A]\\d{2}[24680].".r
  val valid = "([12])".r

  def resolveSukupuoli(hakija:Hakija):String = (hakija.henkilo.hetu, hakija.henkilo.sukupuoli) match {
    case (mies(), _) => "1"
    case (nainen(), _) => "2"
    case (_, valid(sukupuoli)) => sukupuoli
    case _ => "0"
  }


  import RicherString._

  def apply(hakija: Hakija, hakemus: XMLHakemus): JSONHakija =
    JSONHakija(
      hetu = hetu(hakija.henkilo.hetu, hakija.henkilo.syntymaaika),
      oppijanumero = hakija.henkilo.oppijanumero,
      sukunimi = hakija.henkilo.sukunimi,
      etunimet = hakija.henkilo.etunimet,
      kutsumanimi = hakija.henkilo.kutsumanimi.blankOption,
      lahiosoite = hakija.henkilo.lahiosoite,
      postinumero = hakija.henkilo.postinumero,
      postitoimipaikka = hakija.henkilo.postitoimipaikka,
      maa = hakija.henkilo.maa,
      kansalaisuus = hakija.henkilo.kansalaisuus,
      matkapuhelin = hakija.henkilo.matkapuhelin.blankOption,
      muupuhelin = hakija.henkilo.puhelin.blankOption,
      sahkoposti = hakija.henkilo.sahkoposti.blankOption,
      kotikunta = hakija.henkilo.kotikunta.blankOption,
      sukupuoli = resolveSukupuoli(hakija),
      aidinkieli = hakija.henkilo.asiointiKieli,
      koulutusmarkkinointilupa = hakija.henkilo.markkinointilupa.getOrElse(false),
      kiinnostunutoppisopimuksesta = hakija.henkilo.kiinnostunutoppisopimuksesta.getOrElse(false),
      huoltajannimi = hakija.henkilo.huoltajannimi.blankOption,
      huoltajanpuhelinnumero = hakija.henkilo.huoltajanpuhelinnumero.blankOption,
      huoltajansahkoposti = hakija.henkilo.huoltajansahkoposti.blankOption,
      hakemus = hakemus,
      lisakysymykset = hakija.henkilo.lisakysymykset
    )

  def hetu(hetu: String, syntymaaika: String): String = hetu match {
    case "" => Try(new SimpleDateFormat("ddMMyyyy").format(new SimpleDateFormat("dd.MM.yyyy").parse(syntymaaika))).getOrElse("")
    case _ => hetu
  }

}


case class XMLHakijat(hakijat: Seq[XMLHakija]) {
  def toXml: Node = {
    <Hakijat xmlns="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat hakijat.xsd">
      {hakijat.map(_.toXml)}
    </Hakijat>
  }
}

case class JSONHakijat(hakijat: Seq[JSONHakija])

case class HakijaQuery(haku: Option[String], organisaatio: Option[String], hakukohdekoodi: Option[String], hakuehto: Hakuehto.Hakuehto, user: Option[User], version: Int)


object Hakuehto extends Enumeration {
  type Hakuehto = Value
  val Kaikki, Hyvaksytyt, Vastaanottaneet, Hylatyt = Value
}