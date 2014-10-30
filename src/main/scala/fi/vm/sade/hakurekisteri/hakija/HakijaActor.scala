package fi.vm.sade.hakurekisteri.hakija

import akka.actor.{ActorLogging, ActorRef, Actor}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.Hakupalvelu
import fi.vm.sade.hakurekisteri.integration.koodisto.GetRinnasteinenKoodiArvoQuery
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila
import fi.vm.sade.hakurekisteri.integration.valintatulos._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila._
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import scala.util.Try
import akka.pattern.{pipe, ask}
import ForkedSeq._
import TupledFuture._
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.suoritus.Komoto


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

  private def resolveLasnaolot(lasna: Boolean)(ht: Hakutoive): Seq[Lasnaolo] = ht.hakukohde.koulutukset.map((komoto) => (lasna, komoto.alkamisvuosi, komoto.alkamiskausi)).map {
    case (true, vuosi, Kausi.Syksy) => Try(Lasna(Syksy(vuosi.toInt))).toOption
    case (false, vuosi, Kausi.Syksy) => Try(Poissa(Syksy(vuosi.toInt))).toOption
    case (true, vuosi, Kausi.Kevät) => Try(Lasna(Kevat(vuosi.toInt))).toOption
    case (false, vuosi, Kausi.Kevät) => Try(Poissa(Kevat(vuosi.toInt))).toOption
    case _ => None
  }.flatten.toSeq

  import Valintatila.isHyvaksytty
  import Vastaanottotila.isVastaanottanut

  def apply(ht: Hakutoive, valinta: Option[Valintatila], vastaanotto: Option[Vastaanottotila]) = (valinta, vastaanotto) match {
    case (Some(v), Some(Vastaanottotila.KESKEN)) if isHyvaksytty(v) => Hyvaksytty(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(v), Some(vt)) if isHyvaksytty(v) && isVastaanottanut(vt) => Vastaanottanut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(v), Some(Vastaanottotila.EI_VASTAANOTETTU_MAARA_AIKANA)) if isHyvaksytty(v) => EiVastaanotettu(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(v), Some(Vastaanottotila.PERUNUT)) if isHyvaksytty(v) => PerunutValinnan(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(v), Some(Vastaanottotila.PERUUTETTU)) if isHyvaksytty(v) => PeruutettuValinta(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(Valintatila.VARALLA), _) => Varalla(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(Valintatila.HYLATTY), _) => Hylatty(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(Valintatila.PERUUTETTU), _) => Peruutettu(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(Valintatila.PERUUNTUNUT), _)  => Peruuntunut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(Valintatila.PERUNUT), _) => Perunut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (_, _) =>
      Toive(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
  }
}

case class Toive(jno: Int, hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                 harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                 yhteispisteet: Option[BigDecimal] = None) extends Hakutoive {
  override def withPisteet(pisteet: Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Hyvaksytty(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal]) extends Hakutoive with Valittu {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class PerunutValinnan(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal]) extends Hakutoive with Valittu {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class PeruutettuValinta(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                           harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                           yhteispisteet: Option[BigDecimal]) extends Hakutoive with Valittu {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class EiVastaanotettu(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal]) extends Hakutoive with IlmoitusLahetetty {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class Vastaanottanut(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal], lasna: Seq[Lasnaolo] = Seq()) extends Hakutoive with VastaanottanutPaikan {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}


case class Varalla(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal]) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Hylatty(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal]) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Perunut(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                   yhteispisteet: Option[BigDecimal]) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Peruutettu(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal]) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Peruuntunut(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                       harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                       yhteispisteet: Option[BigDecimal]) extends Hakutoive {
  override def withPisteet(pisteet:Option[BigDecimal]) = this.copy(yhteispisteet = pisteet)
}

case class Hakemus(hakutoiveet: Seq[Hakutoive], hakemusnumero: String, julkaisulupa: Boolean, hakuOid: String, lisapistekoulutus: Option[String])

case class Hakija(henkilo: Henkilo, suoritukset: Seq[Suoritus], opiskeluhistoria: Seq[Opiskelija], hakemus: Hakemus)

class HakijaActor(hakupalvelu: Hakupalvelu, organisaatioActor: ActorRef, koodistoActor: ActorRef, valintaTulosActor: ActorRef) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher
  implicit val defaultTimeout: Timeout = 120.seconds
  val tuntematonOppilaitos = "00000"

  def receive = {
    case q: HakijaQuery => XMLQuery(q) pipeTo sender
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


  def hakija2XMLHakija(hakija: Hakija): Future[XMLHakija] = {
    getXmlHakemus(hakija).map(data2XmlHakija(hakija))
  }

  def data2XmlHakija(hakija: Hakija)(hakemus: XMLHakemus) =
    XMLHakija(hakija, hakemus)

  def hakijat2XmlHakijat(hakijat: Seq[Hakija]): Future[Seq[XMLHakija]] =
    hakijat.map(hakija2XMLHakija).join

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


  def hakutoiveFilter(predicate: (XMLHakutoive) => Boolean)(xh:XMLHakija):XMLHakija = xh.copy(hakemus = xh.hakemus.copy(hakutoiveet = xh.hakemus.hakutoiveet.filter(predicate)))

  val hakijaWithValittu: (XMLHakija) => XMLHakija = hakutoiveFilter(_.valinta == Some("1"))

  val hakijaWithVastaanotettu = hakutoiveFilter(_.vastaanotto == Some("3")) _

  def XMLQuery(q: HakijaQuery): Future[XMLHakijat] = q.hakuehto match {
    case Hakuehto.Kaikki => getHakijat(q).map((hakijat) => XMLHakijat(hakijat.filter(_.hakemus.hakutoiveet.size > 0)))
    case Hakuehto.Hyvaksytyt => getHakijat(q).map(_.map(hakijaWithValittu)).map((hakijat) => XMLHakijat(hakijat.filter(_.hakemus.hakutoiveet.size > 0)))
    case Hakuehto.Vastaanottaneet => getHakijat(q).map(_.map(hakijaWithVastaanotettu)).map((hakijat) => XMLHakijat(hakijat.filter(_.hakemus.hakutoiveet.size > 0)))
    case Hakuehto.Hylatyt => for (hakijat <- getHakijat(q)) yield {
        val hylatyt: Set[XMLHakija] =  hakijat.map(hakijaWithValittu).filter(_.hakemus.hakutoiveet == 0).toSet
        XMLHakijat(hakijat.filter(hylatyt.contains))
      }
    case _ => Future.successful(XMLHakijat(Seq()))
  }

  def enrichHakijat(hakijat: Seq[Hakija]): Future[Seq[Hakija]] = Future.sequence(for {
    hakija <- hakijat
  } yield for {
    kansalaisuus <- getMaakoodi(hakija.henkilo.kansalaisuus)
    maa <- getMaakoodi(hakija.henkilo.maa)
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
        matkapuhelin = h.matkapuhelin,
        puhelin = h.puhelin,
        sahkoposti = h.sahkoposti,
        kotikunta = h.kotikunta,
        kansalaisuus = kansalaisuus,
        asiointiKieli = h.asiointiKieli,
        eiSuomalaistaHetua = h.eiSuomalaistaHetua,
        markkinointilupa = h.markkinointilupa
      ),
      suoritukset = hakija.suoritukset,
      opiskeluhistoria = hakija.opiskeluhistoria,
      hakemus = hakija.hakemus
    )
  })

  def getHakijat(q: HakijaQuery) = {
    hakupalvelu.getHakijat(q).flatMap(enrichHakijat).flatMap(combine2sijoittelunTulos(q.user)).flatMap(hakijat2XmlHakijat)
  }
}
