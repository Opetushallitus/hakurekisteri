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


case class Hakukohde(koulutukset: Set[Komoto], hakukohdekoodi: String)

case class Hakutoive(hakukohde: Hakukohde, kaksoistutkinto: Boolean)

case class Hakemus(hakutoiveet: Seq[Hakutoive], hakemusnumero: String)

case class Hakija(henkilo: Henkilo, suoritukset: Seq[Suoritus], opiskeluhistoria: Seq[Opiskelija], hakemus: Hakemus)

class HakijaActor(hakupalvelu: Hakupalvelu, organisaatiopalvelu: Organisaatiopalvelu, koodistopalvelu: Koodistopalvelu) extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)

  class ForkedSeq[A](forked: Seq[Future[A]]) {
    def join() = Future.sequence(forked)
  }

  implicit def Seq2Forked[A](s:Seq[Future[A]]): ForkedSeq[A] = new ForkedSeq(s)

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

  def getValue(h: Option[Map[String, String]], key: String, default: String = ""): String = {
    h.flatMap(_.get(key)).getOrElse(default)
  }

  def getHakija(hakemus: FullHakemus): Hakija = {
    val lahtokoulu: Option[String] = hakemus.vastauksetMerged.flatMap(_.get("lahtokoulu"))
    val v = hakemus.vastauksetMerged
    Hakija(
      Henkilo(
        yhteystiedotRyhma = Seq(YhteystiedotRyhma(0, "yhteystietotyyppi1", "hakemus", true, Seq(
          Yhteystiedot(0, "YHTEYSTIETO_KATUOSOITE", getValue(v, "lahiosoite")),
          Yhteystiedot(1, "YHTEYSTIETO_POSTINUMERO", getValue(v, "Postinumero")),
          Yhteystiedot(2, "YHTEYSTIETO_MAA", getValue(v, "asuinmaa")),
          Yhteystiedot(3, "YHTEYSTIETO_MATKAPUHELIN", getValue(v, "matkapuhelinnumero1")),
          Yhteystiedot(4, "YHTEYSTIETO_SAHKOPOSTI", getValue(v, "Sähköposti")),
          Yhteystiedot(5, "YHTEYSTIETO_KAUPUNKI", getValue(v, "kotikunta"))
        ))),
        yksiloity = false,
        sukunimi = getValue(v, "Sukunimi"),
        etunimet = getValue(v, "Etunimet"),
        kutsumanimi = getValue(v, "Kutsumanimi"),
        kielisyys = Seq(),
        yksilointitieto = None,
        henkiloTyyppi = "OPPIJA",
        oidHenkilo = hakemus.personOid.getOrElse(""),
        duplicate = false,
        oppijanumero = hakemus.personOid.getOrElse(""),
        kayttajatiedot = None,
        kansalaisuus = Seq(Kansalaisuus(getValue(v, "kansalaisuus"))),
        passinnumero = "",
        asiointiKieli = Kieli("FI", "FI"),
        passivoitu = false,
        eiSuomalaistaHetua = getValue(v, "onkoSinullaSuomalainenHetu", "false").toBoolean,
        sukupuoli = getValue(v, "sukupuoli"),
        hetu = getValue(v, "Henkilotunnus"),
        syntymaaika = getValue(v, "syntymaaika"),
        turvakielto = false,
        markkinointilupa = Some(getValue(v, "lupaMarkkinointi", "false").toBoolean)
      ),
      Seq(Suoritus(
        komo = "peruskoulu",
        myontaja = lahtokoulu.getOrElse(""),
        tila = "KESKEN",
        valmistuminen = LocalDate.now,
        henkiloOid = hakemus.personOid.getOrElse(""),
        yksilollistaminen = Ei,
        suoritusKieli = getValue(v, "perusopetuksen_kieli", "FI")
      )),
      lahtokoulu match {
        case Some(oid) => Seq(Opiskelija(
          oppilaitosOid = lahtokoulu.get,
          henkiloOid = hakemus.personOid.getOrElse(""),
          luokkataso = getValue(v, "luokkataso"),
          luokka = getValue(v, "lahtoluokka"),
          alkuPaiva = DateTime.now.minus(org.joda.time.Duration.standardDays(1)),
          loppuPaiva = None
        ))
        case _ => Seq()
      },
      v.map(toiveet => {
        val hakutoiveet = convertToiveet(toiveet)
        Hakemus(hakutoiveet, hakemus.oid)
      }).getOrElse(Hakemus(Seq(), hakemus.oid))
    )
  }

  def convertToiveet(toiveet: Map[String, String]): Seq[Hakutoive] = {
    val Pattern = "preference(\\d+)-Opetuspiste-id".r
    val notEmpty = "(.+)".r
    val opetusPisteet: Seq[(Short, String)] = toiveet.collect {
      case (Pattern(n), notEmpty(opetusPisteId)) => (n.toShort, opetusPisteId)
    }.toSeq

    opetusPisteet.sortBy(_._1).map((t) => {
      val koulutukset = Set(Komoto("", "", t._2, "2014", Kausi.Syksy))
      val hakukohdekoodi = toiveet("preference" + t._1 + "-Koulutus-id-aoIdentifier")
      Hakutoive(Hakukohde(koulutukset, hakukohdekoodi), Try(toiveet("preference" + t._1 + "-Koulutus-id-kaksoistutkinto").toBoolean).getOrElse(false))
    })
  }

  def fetchHakija(oid: String)(implicit user: Option[User]): Future[Option[Hakija]] = {
    hakupalvelu.get(oid, user).map(_.map(getHakija(_)))
  }

  def selectHakijat(q: HakijaQuery): Future[Seq[Hakija]] = {
    hakupalvelu.find(q).flatMap(hakemukset => {
      implicit val user:Option[User] = q.user
      hakemukset.
        map(_.oid).
        map(fetchHakija).
        join.
        map(_.flatten)
    })
  }

  def XMLQuery(q: HakijaQuery): Future[XMLHakijat] = q.hakuehto match {
    case Hakuehto.Kaikki => selectHakijat(q).map(_.map(hakija2XMLHakija)).flatMap(Future.sequence(_).map(hakijat => XMLHakijat(hakijat.flatten)))
    // TODO Hakuehto.Hyväksytyt & Hakuehto.Vastaanottaneet
    case _ => Future(XMLHakijat(Seq()))
  }
}
