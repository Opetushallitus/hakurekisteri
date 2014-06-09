package fi.vm.sade.hakurekisteri.hakija

import akka.actor.{ActorRef, Actor}
import scala.concurrent.{Future, ExecutionContext}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.henkilo._
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import scala.util.{Failure, Success, Try}
import scala.Some
import fi.vm.sade.hakurekisteri.suoritus.Komoto
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot
import akka.pattern.{pipe, ask}
import ForkedSeq._
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import TupledFuture._
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.hakija.SijoitteluHakemuksenTila.SijoitteluHakemuksenTila
import fi.vm.sade.hakurekisteri.hakija.SijoitteluValintatuloksenTila.SijoitteluValintatuloksenTila


case class Hakukohde(koulutukset: Set[Komoto], hakukohdekoodi: String, oid: String)

sealed class Hakutoive(val hakukohde: Hakukohde, val kaksoistutkinto: Option[Boolean], val urheilijanammatillinenkoulutus: Option[Boolean],
                       val harkinnanvaraisuusperuste: Option[String], val aiempiperuminen: Option[Boolean], val terveys: Option[Boolean])

object Hakutoive{

  def apply(ht:Hakutoive, tila: Option[SijoitteluHakemuksenTila]) = tila match {

    case Some(SijoitteluHakemuksenTila.HYVAKSYTTY) => Valittu(ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys)
    case Some(SijoitteluHakemuksenTila.VARALLA) => Varalla(ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys)

    case _ => Toive(ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys)
  }

  def unapply(ht: Hakutoive): Option[(Hakukohde, Option[Boolean],  Option[Boolean], Option[String],  Option[Boolean],  Option[Boolean])] =
    Some((ht.hakukohde, ht.kaksoistutkinto, ht.urheilijanammatillinenkoulutus, ht.harkinnanvaraisuusperuste, ht.aiempiperuminen, ht.terveys))

}


class Toive(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean],  urheilijanammatillinenkoulutus: Option[Boolean],
                   harkinnanvaraisuusperuste: Option[String],   aiempiperuminen: Option[Boolean],  terveys: Option[Boolean])
  extends Hakutoive(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
                    harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean])

object Toive {

  def apply(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
            harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean]) = new Toive(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
    harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean])

  def unapply(ht: Toive): Option[(Hakukohde, Option[Boolean],  Option[Boolean], Option[String],  Option[Boolean],  Option[Boolean])] = Hakutoive.unapply(ht)

}


class Valittu(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean],  urheilijanammatillinenkoulutus: Option[Boolean],
            harkinnanvaraisuusperuste: Option[String],   aiempiperuminen: Option[Boolean],  terveys: Option[Boolean])
  extends Hakutoive(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
    harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean])

object Valittu {

  def apply(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
            harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean]) = new Valittu(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
    harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean])

  def unapply(ht: Valittu): Option[(Hakukohde, Option[Boolean],  Option[Boolean], Option[String],  Option[Boolean],  Option[Boolean])] = Hakutoive.unapply(ht)

}


class Varalla(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean],  urheilijanammatillinenkoulutus: Option[Boolean],
harkinnanvaraisuusperuste: Option[String],   aiempiperuminen: Option[Boolean],  terveys: Option[Boolean])
extends Hakutoive(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean])

object Varalla {

  def apply(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
            harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean]) = new Varalla(hakukohde: Hakukohde, kaksoistutkinto: Option[Boolean], urheilijanammatillinenkoulutus: Option[Boolean],
    harkinnanvaraisuusperuste: Option[String], aiempiperuminen: Option[Boolean], terveys: Option[Boolean])

  def unapply(ht: Varalla): Option[(Hakukohde, Option[Boolean],  Option[Boolean], Option[String],  Option[Boolean],  Option[Boolean])] = Hakutoive.unapply(ht)

}



case class Hakemus(hakutoiveet: Seq[Hakutoive], hakemusnumero: String, julkaisulupa: Option[Boolean], hakuOid: String)

case class Hakija(henkilo: Henkilo, suoritukset: Seq[Suoritus], opiskeluhistoria: Seq[Opiskelija], hakemus: Hakemus)

class HakijaActor(hakupalvelu: Hakupalvelu, organisaatioActor: ActorRef, koodistopalvelu: Koodistopalvelu, sijoittelupalvelu: Sijoittelupalvelu) extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)


  def receive = {
    case q: HakijaQuery => XMLQuery(q) pipeTo sender
  }



  def resolveOppilaitosKoodi(o:Organisaatio): Future[Option[String]] =  o.oppilaitosKoodi match {
    case None => findOppilaitoskoodi(o.parentOid)
    case Some(k) => Future(Some(k))
  }


  def getOrg(oid: String): Future[Option[Organisaatio]] = {
    import scala.concurrent.duration._
    implicit val timeout: akka.util.Timeout = 30.seconds
    Try((organisaatioActor ? oid).mapTo[Option[Organisaatio]]).getOrElse(Future.successful(None))
  }

  def findOppilaitoskoodi(parentOid: Option[String]): Future[Option[String]] = parentOid match {
    case None => Future(None)
    case Some(oid) => getOrg(oid).flatMap(_.map(resolveOppilaitosKoodi).getOrElse(Future(None)))

  }

  def hakutoive2XMLHakutoive(ht: Hakutoive, jno:Int): Future[Option[XMLHakutoive]] =  {
   for(
      orgData: Option[(Organisaatio, String)] <- findOrgData(ht.hakukohde.koulutukset.head.tarjoaja)
    ) yield
     for ((org: Organisaatio, oppilaitos: String) <- orgData)
      yield XMLHakutoive(ht,jno,org,oppilaitos)
  }

  @Deprecated // TODO mäppää puuttuvat tiedot
  def getXmlHakutoiveet(hakija: Hakija): Future[Seq[XMLHakutoive]] = {
    val futureToiveet = for ((ht, jno) <- hakija.hakemus.hakutoiveet.zipWithIndex)  yield hakutoive2XMLHakutoive(ht, jno)
    futureToiveet.join.map(_.flatten)
  }

  def extractOption(t: (Option[Organisaatio], Option[String])): Option[(Organisaatio, String)] = t._1 match {
    case None => None
    case Some(o) => Some((o, t._2.get))
  }

  def findOrgData(tarjoaja: String): Future[Option[(Organisaatio,String)]] = {
    getOrg(tarjoaja).flatMap((o) => findOppilaitoskoodi(o.map(_.oid)).map(k => extractOption(o, k)))
  }


  def createHakemus(hakija: Hakija)(opiskelija: Option[Opiskelija], org:Option[Organisaatio], ht: Seq[XMLHakutoive]) = XMLHakemus(hakija,opiskelija, org, ht)


  @Deprecated // TODO mäppää puuttuvat tiedot
  def getXmlHakemus(hakija: Hakija): Future[XMLHakemus] = {

    val (opiskelutieto, lahtokoulu) = getOpiskelijaTiedot(hakija)
    val ht: Future[Seq[XMLHakutoive]] = getXmlHakutoiveet(hakija)
    val data = (opiskelutieto,lahtokoulu,ht).join

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

  def hakijat2XmlHakijat(hakijat: Seq[Hakija]): Future[XMLHakijat] = hakijat.map(hakija2XMLHakija).join.map(XMLHakijat)

  def matchSijoitteluAndHakemus(shakijas: Seq[SijoitteluHakija], hakijas: Seq[Hakija]): Seq[Hakija] = {
    val sijoittelu: Map[String, Map[String, SijoitteluHakemuksenTila]] = shakijas.groupBy(_.hakemusOid).collect{ case (Some(s), hs) => (s,hs)}.map{
      case (hakemus, sijoittelu) => (hakemus, sijoittelu.map(
        _.hakutoiveet.flatMap((ht: SijoitteluHakutoive) => ht.hakutoiveenValintatapajonot.flatMap((vtj) => ht.hakukohdeOid.map((_, vtj.tila)))) ).flatten.collect{case (a, Some(s)) => (a,s)}.toMap)}
    /*val valinta: Map[String, Map[String, Option[SijoitteluValintatuloksenTila]]] = hakijas.groupBy(_.hakemusOid).collect{ case (Some(s), hs) => (s,hs)}.map{
      case (hakemus, sijoittelu) => (hakemus, sijoittelu.map(_.hakutoiveet.flatMap((ht: SijoitteluHakutoive) => ht.hakutoiveenValintatapajonot.flatMap((vtj) => ht.hakukohdeOid.map((_, vtj.vastaanottotieto)))) ).flatten.toMap)}*/

    hakijas.map(tila(sijoittelu))



  }

  def tila(sijoittelu: Map[String, Map[String, SijoitteluHakemuksenTila]] )(h:Hakija): Hakija = {
    val toiveet = h.hakemus.hakutoiveet.map((ht) => Hakutoive(ht, sijoittelu.getOrElse(h.hakemus.hakemusnumero, Map()).get(ht.hakukohde.oid)))
    h.copy(hakemus = h.hakemus.copy(hakutoiveet = toiveet))
  }

  def handleSijoittelu(hakijas: Seq[Hakija])(sp: Option[SijoitteluPagination]): Seq[Hakija] = sp match {
    case None  => Seq()
    case Some(s) if s.results.isEmpty => Seq()
    case Some(s) if s.results.isDefined => matchSijoitteluAndHakemus(s.results.get, hakijas)
  }

  def combine2sijoittelunTulos(hakijat: Seq[Hakija])(user: Option[User]): Future[Seq[Hakija]] = {
    val hakemuksetByHakuOids: Map[String, Seq[Hakija]] = hakijat.groupBy(_.hakemus.hakuOid) // hakuOid -> lista hakijoista

    Future.sequence(hakemuksetByHakuOids.map((t: (String, Seq[Hakija])) => {
      sijoittelupalvelu.getSijoitteluTila(t._1, user).map(handleSijoittelu(t._2))
    })).map(_.reduce(_ ++ _))

  }

  def XMLQuery(q: HakijaQuery): Future[XMLHakijat] = q.hakuehto match {
    case Hakuehto.Kaikki => hakupalvelu.getHakijat(q).flatMap(hakijat2XmlHakijat)
    case Hakuehto.Hyväksytyt => hakupalvelu.getHakijat(q).flatMap(combine2sijoittelunTulos(_)(q.user)).flatMap(hakijat2XmlHakijat)
    // TODO Hakuehto.Vastaanottaneet
    case _ => Future.successful(XMLHakijat(Seq()))
  }
}
