package fi.vm.sade.hakurekisteri.hakija

import akka.actor.Actor
import scala.concurrent.{Future, ExecutionContext}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.henkilo._
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import scala.util.Try
import fi.vm.sade.hakurekisteri.rest.support.{User, Kausi}
import fi.vm.sade.hakurekisteri.henkilo.Kansalaisuus
import scala.Some
import fi.vm.sade.hakurekisteri.suoritus.Komoto
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot
import fi.vm.sade.hakurekisteri.henkilo.YhteystiedotRyhma
import org.joda.time.{DateTime, LocalDate}
import akka.pattern.pipe
import ForkedSeq._


case class Hakukohde(koulutukset: Set[Komoto], hakukohdekoodi: String)

case class Hakutoive(hakukohde: Hakukohde, kaksoistutkinto: Boolean)

case class Hakemus(hakutoiveet: Seq[Hakutoive], hakemusnumero: String)

case class Hakija(henkilo: Henkilo, suoritukset: Seq[Suoritus], opiskeluhistoria: Seq[Opiskelija], hakemus: Hakemus)

class HakijaActor(hakupalvelu: Hakupalvelu, organisaatiopalvelu: Organisaatiopalvelu, koodistopalvelu: Koodistopalvelu) extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)


  def receive = {
    case q: HakijaQuery => {
      XMLQuery(q) pipeTo sender
    }
  }

  implicit def yhteystietoryhmatToMap(yhteystiedot: Seq[YhteystiedotRyhma]): Map[(String, String), Seq[Yhteystiedot]] = {
    yhteystiedot.map((y) => (y.ryhmaAlkuperaTieto, y.ryhmaKuvaus) -> y.yhteystiedot).toMap
  }

  implicit def yhteystiedotToMap(yhteystiedot: Seq[Yhteystiedot]): Map[String, String] = {
    yhteystiedot.map((y) => y.yhteystietoTyyppi -> y.yhteystietoArvo).toMap
  }

  def resolveOppilaitosKoodi(o:Organisaatio): Future[Option[String]] =  o.oppilaitosKoodi match {
    case None => findOppilaitoskoodi(o.parentOid)
    case Some(k) => Future(Some(k))
  }

  def findOppilaitoskoodi(parentOid: Option[String]): Future[Option[String]] = parentOid match {
    case None => Future(None)
    case Some(oid) => organisaatiopalvelu.get(oid).flatMap(_.map(resolveOppilaitosKoodi).getOrElse(Future(None)))
  }

  def hakutoive2XMLHakutoive(ht: Hakutoive, jno:Int): Future[Option[XMLHakutoive]] =  {
    findOrgData(ht.hakukohde.koulutukset.head.tarjoaja).map(_.map((XMLHakutoive(ht, jno) _).tupled))
  }

  @Deprecated // TODO mäppää puuttuvat tiedot
  def getXmlHakutoiveet(hakija: Hakija): Future[Seq[XMLHakutoive]] = {
    hakija.hakemus.hakutoiveet.
      zipWithIndex.
      map((hakutoive2XMLHakutoive _).tupled).
      toSeq.
      join.
      map(_.flatten)
  }

  def extractOption(t: (Option[Organisaatio], Option[String])): Option[(Organisaatio, String)] = t._1 match {
    case None => None
    case Some(o) => Some((o, t._2.get))
  }

  def findOrgData(tarjoaja: String): Future[Option[(Organisaatio,String)]] = {
    organisaatiopalvelu.get(tarjoaja).flatMap((o) => findOppilaitoskoodi(o.map(_.oid)).map(k => extractOption(o, k)))
  }

  import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._

  @Deprecated // TODO mäppää puuttuvat tiedot
  def getXmlHakemus(hakija: Hakija, opiskelutieto: Option[Opiskelija], lahtokoulu: Option[Organisaatio]): Future[Option[XMLHakemus]] = {
    val ht: Future[Seq[XMLHakutoive]] = getXmlHakutoiveet(hakija)
    ht.map(toiveet => Try(hakija.hakemus).map((hakemus: Hakemus) => XMLHakemus(hakija, hakemus, opiskelutieto, lahtokoulu, toiveet)).toOption)
  }

  def getXmlHakemus(hakija: Hakija): Future[Option[XMLHakemus]] = {
    hakija.opiskeluhistoria.size match {
      case 0 => getXmlHakemus(hakija, None, None)
      case _ => organisaatiopalvelu.get(hakija.opiskeluhistoria.head.oppilaitosOid).flatMap((o: Option[Organisaatio]) => getXmlHakemus(hakija, Some(hakija.opiskeluhistoria.head), o))
    }
  }

  def getMaakoodi(koodiArvo: String): Future[String] = {
    koodistopalvelu.getRinnasteinenKoodiArvo("maatjavaltiot1_" + koodiArvo.toLowerCase, "maatjavaltiot2")
  }

  @Deprecated // TODO ratkaise kaksoiskansalaisuus
  def hakija2XMLHakija(hakija: Hakija): Future[Option[XMLHakija]] = {
    getXmlHakemus(hakija).flatMap((hakemus) => {
      val yhteystiedot: Seq[Yhteystiedot] = hakija.henkilo.yhteystiedotRyhma.getOrElse(("hakemus", "yhteystietotyyppi1"), Seq())
      hakemus.
        map(hakemus => getMaakoodi(yhteystiedot.getOrElse("YHTEYSTIETO_MAA", "FIN")).
          flatMap((maa) => getMaakoodi(Try(hakija.henkilo.kansalaisuus.head).map(k => k.kansalaisuusKoodi).getOrElse("FIN")).
            map((kansalaisuus) => XMLHakija(hakija, yhteystiedot, maa, kansalaisuus, hakemus)))).
        map(f => f.map(Option(_))).
        getOrElse(Future.successful(None))
    })
  }

  def XMLQuery(q: HakijaQuery): Future[XMLHakijat] = q.hakuehto match {
    case Hakuehto.Kaikki => hakupalvelu.getHakijat(q).map(_.map(hakija2XMLHakija)).flatMap(Future.sequence(_).map(hakijat => XMLHakijat(hakijat.flatten)))
    // TODO Hakuehto.Hyväksytyt & Hakuehto.Vastaanottaneet
    case _ => Future(XMLHakijat(Seq()))
  }
}
