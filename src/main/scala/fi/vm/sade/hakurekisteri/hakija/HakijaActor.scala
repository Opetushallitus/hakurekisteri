package fi.vm.sade.hakurekisteri.hakija

import akka.actor.{ActorRef, Actor}
import scala.concurrent.{Future, ExecutionContext}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.henkilo._
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import scala.util.Try
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot
import akka.pattern.{pipe, ask}
import ForkedSeq._
import TupledFuture._
import fi.vm.sade.hakurekisteri.hakija.SijoitteluHakemuksenTila.SijoitteluHakemuksenTila
import fi.vm.sade.hakurekisteri.hakija.SijoitteluValintatuloksenTila._
import scala.util.Failure
import scala.Some
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, User}
import scala.util.Success
import fi.vm.sade.hakurekisteri.suoritus.Komoto
import org.slf4j.LoggerFactory


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

sealed trait Kausi
case class Kevat(vuosi:Int) extends Kausi
case class Syksy(vuosi:Int) extends Kausi


sealed trait Valittu

sealed trait IlmoitusLahetetty extends Valittu

sealed trait VastaanottanutPaikan extends IlmoitusLahetetty {
  val lasna: Seq[Lasnaolo]
}

object Hakutoive{
  val log = LoggerFactory.getLogger(classOf[Hakutoive])

  def resolveLasnaolot(lasna:Boolean)(ht: Hakutoive):Seq[Lasnaolo] = {
    ht.hakukohde.koulutukset.map((komoto) => (lasna, komoto.alkamisvuosi, komoto.alkamiskausi)).map
      {
        case (true, vuosi, Kausi.Syksy) => Try(Lasna(Syksy(vuosi.toInt))).toOption
        case (false, vuosi, Kausi.Syksy) => Try(Poissa(Syksy(vuosi.toInt))).toOption
        case (true, vuosi, Kausi.Kevät) => Try(Lasna(Kevat(vuosi.toInt))).toOption
        case (false, vuosi, Kausi.Kevät) => Try(Poissa(Kevat(vuosi.toInt))).toOption
        case _ => None
      }.flatten.toSeq
  }

  def apply(ht: Hakutoive, hakemus: Option[SijoitteluHakemuksenTila], vastaanotto: Option[SijoitteluValintatuloksenTila]) = (hakemus, vastaanotto) match {
    case (Some(SijoitteluHakemuksenTila.HYVAKSYTTY), None) => Hyvaksytty(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(SijoitteluHakemuksenTila.HYVAKSYTTY), Some(SijoitteluValintatuloksenTila.ILMOITETTU)) => Ilmoitettu(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(SijoitteluHakemuksenTila.HYVAKSYTTY), Some(SijoitteluValintatuloksenTila.VASTAANOTTANUT)) => Vastaanottanut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(SijoitteluHakemuksenTila.HYVAKSYTTY), Some(SijoitteluValintatuloksenTila.VASTAANOTTANUT_LASNA)) => Vastaanottanut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, resolveLasnaolot(true)(ht))
    case (Some(SijoitteluHakemuksenTila.HYVAKSYTTY), Some(SijoitteluValintatuloksenTila.VASTAANOTTANUT_POISSAOLEVA)) => Vastaanottanut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet, resolveLasnaolot(false)(ht))
    case (Some(SijoitteluHakemuksenTila.HYVAKSYTTY), Some(SijoitteluValintatuloksenTila.EI_VASTAANOTETTU_MAARA_AIKANA)) => EiVastaanotettu(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(SijoitteluHakemuksenTila.HYVAKSYTTY), Some(SijoitteluValintatuloksenTila.PERUNUT)) => PerunutValinnan(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(SijoitteluHakemuksenTila.HYVAKSYTTY), Some(SijoitteluValintatuloksenTila.PERUUTETTU)) => PeruutettuValinta(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(SijoitteluHakemuksenTila.VARALLA), _) => Varalla(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(SijoitteluHakemuksenTila.HYLATTY), _) => Hylatty(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(SijoitteluHakemuksenTila.PERUUTETTU), _) => Peruutettu(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(SijoitteluHakemuksenTila.PERUUNTUNUT), _)  => Peruuntunut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (Some(SijoitteluHakemuksenTila.PERUNUT), _) => Perunut(ht.jno, ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys, ht.yhteispisteet)
    case (hakemuksenTila, vastaanotonTila) =>
      if (vastaanotonTila.isDefined) log.warn(s"Unknown combination for hakemus ($hakemuksenTila) and valinta ($vastaanotonTila)")
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



case class Ilmoitettu(jno: Int,hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                      harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean],
                      yhteispisteet: Option[BigDecimal]) extends Hakutoive with IlmoitusLahetetty {
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

case class Hakemus(hakutoiveet: Seq[Hakutoive], hakemusnumero: String, julkaisulupa: Option[Boolean], hakuOid: String)

case class Hakija(henkilo: Henkilo, suoritukset: Seq[Suoritus], opiskeluhistoria: Seq[Opiskelija], hakemus: Hakemus)

class HakijaActor(hakupalvelu: Hakupalvelu, organisaatioActor: ActorRef, koodistopalvelu: Koodistopalvelu, sijoittelupalvelu: ActorRef) extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)

  def receive = {
    case q: HakijaQuery => XMLQuery(q) pipeTo sender
  }

  def resolveOppilaitosKoodi(o: Organisaatio): Future[Option[String]] = o.oppilaitosKoodi match {
    case None => findOppilaitoskoodi(o.parentOid)
    case Some(k) => Future.successful(Some(k))
  }

  def getOrg(oid: String): Future[Option[Organisaatio]] = {
    import scala.concurrent.duration._
    implicit val timeout: akka.util.Timeout = 30.seconds
    Try((organisaatioActor ? oid).mapTo[Option[Organisaatio]]).getOrElse(Future.successful(None))
  }

  val tuntematonOppilaitos = "00000"
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

  def createHakemus(hakija: Hakija)(opiskelija: Option[Opiskelija], org:Option[Organisaatio], ht: Seq[XMLHakutoive]) = XMLHakemus(hakija, opiskelija, org, ht)

  def getXmlHakemus(hakija: Hakija): Future[XMLHakemus] = {
    val (opiskelutieto, lahtokoulu) = getOpiskelijaTiedot(hakija)
    val ht: Future[Seq[XMLHakutoive]] = getXmlHakutoiveet(hakija)
    val data = (opiskelutieto, lahtokoulu, ht).join

    data.tupledMap(createHakemus(hakija))
  }

  def getOpiskelijaTiedot(hakija: Hakija): (Future[Option[Opiskelija]], Future[Option[Organisaatio]]) = hakija.opiskeluhistoria match {
    case opiskelijaTiedot :: _ => (Future.successful(Some(opiskelijaTiedot)), getOrg(opiskelijaTiedot.oppilaitosOid))
    case _ => (Future.successful(None),Future.successful(None))
  }


  def getMaakoodi(koodiArvo: String): Future[String] = koodiArvo.toLowerCase match {
    case "fin" => Future.successful("246")
    case arvo => koodistopalvelu.getRinnasteinenKoodiArvo("maatjavaltiot1_" + arvo, "maatjavaltiot2")
  }


  def hakija2XMLHakija(hakija: Hakija): Future[XMLHakija] = {
    enrich(hakija).tupledMap(data2XmlHakija(hakija))
  }

  def enrich(hakija: Hakija) = {
    val hakemus: Future[XMLHakemus] = getXmlHakemus(hakija)
    val yhteystiedot: Seq[Yhteystiedot] = hakija.henkilo.yhteystiedotRyhma.getOrElse(("hakemus", "yhteystietotyyppi1"), Seq())
    val maakoodi = Try(getMaakoodi(yhteystiedot.getOrElse("YHTEYSTIETO_MAA", "FIN"))).transform(s => Success(s), t => {log.error("%s failed to fetch country".format(hakija));Failure(t)}).get
    val kansalaisuus = Try(getMaakoodi(Try(hakija.henkilo.kansalaisuus.head).map(k => k.kansalaisuusKoodi).getOrElse("FIN"))).transform(s => Success(s), t => {log.error("%s failed to fetch country".format(hakija));Failure(t)}).get

    (hakemus, Future.successful(yhteystiedot), maakoodi, kansalaisuus).join
  }

  def data2XmlHakija(hakija: Hakija)(hakemus: XMLHakemus, yhteystiedot: Seq[Yhteystiedot], kotimaa: String, kansalaisuus: String) =
    XMLHakija(hakija, yhteystiedot, kotimaa, kansalaisuus, hakemus)

  def hakijat2XmlHakijat(hakijat: Seq[Hakija]): Future[Seq[XMLHakija]] = hakijat.map(hakija2XMLHakija).join


  def matchSijoitteluAndHakemus(hakijas: Seq[Hakija])(tulos: SijoitteluTulos): Seq[Hakija] =
    hakijas.map(tila(tulos.hakemus, tulos.valinta)).map(yhteispisteet(tulos.pisteet _))




  def yhteispisteet(pisteet: (String, String) => Option[BigDecimal])(h:Hakija) : Hakija = {
    val toiveet = h.hakemus.hakutoiveet.map((ht) => {
      val oid: String = ht.hakukohde.oid
      val yhteispisteet: Option[BigDecimal] = pisteet(h.hakemus.hakemusnumero, oid)
      ht withPisteet yhteispisteet
    })
    h.copy(hakemus = h.hakemus.copy(hakutoiveet = toiveet))
  }

  def tila(hakemus: (String, String) => Option[SijoitteluHakemuksenTila], valinta: (String, String) => Option[SijoitteluValintatuloksenTila])(h:Hakija): Hakija = {

    val hakemusnumero: String = h.hakemus.hakemusnumero
    h.copy(hakemus =
      h.hakemus.copy(hakutoiveet =
        for (ht <- h.hakemus.hakutoiveet)
          yield Hakutoive(ht, hakemus(hakemusnumero, ht.hakukohde.oid), valinta(hakemusnumero, ht.hakukohde.oid))))
  }



  import scala.concurrent.duration._

  def combine2sijoittelunTulos(user: Option[User])(hakijat: Seq[Hakija]): Future[Seq[Hakija]] = Future.fold(
    hakijat.groupBy(_.hakemus.hakuOid).
      map { case (hakuOid, hakijas) => sijoittelupalvelu.?(SijoitteluQuery(hakuOid))(30.seconds).mapTo[SijoitteluTulos].map(matchSijoitteluAndHakemus(hakijas))}
  )(Seq[Hakija]())(_ ++ _)


  def hakutoiveFilter(predicate: (XMLHakutoive) => Boolean)(xh:XMLHakija):XMLHakija = xh.copy(hakemus = xh.hakemus.copy(hakutoiveet = xh.hakemus.hakutoiveet.filter(predicate)))

  val hakijaWithValittu: (XMLHakija) => XMLHakija = hakutoiveFilter(_.valinta == Some("1")) _

  val hakijaWithVastaanotettu = hakutoiveFilter(_.vastaanotto == Some("3")) _

  def XMLQuery(q: HakijaQuery): Future[XMLHakijat] = q.hakuehto match {
    case Hakuehto.Kaikki => getHakijat(q).map((hakijat) => XMLHakijat(hakijat.filter(_.hakemus.hakutoiveet.size > 0)))
    case Hakuehto.Hyvaksytyt => getHakijat(q).map(_.map(hakijaWithValittu)).map((hakijat) => XMLHakijat(hakijat.filter(_.hakemus.hakutoiveet.size > 0)))
    case Hakuehto.Vastaanottaneet => getHakijat(q).map(_.map(hakijaWithVastaanotettu)).map((hakijat) => XMLHakijat(hakijat.filter(_.hakemus.hakutoiveet.size > 0)))
    case Hakuehto.Hylatyt => for (hakijat <- getHakijat(q)) yield {
        val hylatyt: Set[XMLHakija] =  hakijat.map(hakijaWithValittu).filter(_.hakemus.hakutoiveet == 0).toSet
        XMLHakijat(hakijat.filter(hylatyt contains _))
      }
    case _ => Future.successful(XMLHakijat(Seq()))
  }

  def getHakijat(q: HakijaQuery) = {
    hakupalvelu.getHakijat(q).flatMap(combine2sijoittelunTulos(q.user)).flatMap(hakijat2XmlHakijat)
  }
}
