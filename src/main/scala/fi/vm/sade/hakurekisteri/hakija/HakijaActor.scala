package fi.vm.sade.hakurekisteri.hakija

import akka.actor.Actor
import scala.concurrent.{Future, ExecutionContext}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.henkilo._
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import scala.util.Try
import fi.vm.sade.hakurekisteri.rest.support.Kausi
import fi.vm.sade.hakurekisteri.henkilo.Kansalaisuus
import scala.Some
import fi.vm.sade.hakurekisteri.suoritus.Komoto
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot
import fi.vm.sade.hakurekisteri.henkilo.YhteystiedotRyhma
import org.joda.time.{DateTime, LocalDate}
import akka.pattern.pipe


class HakijaActor(hakupalvelu: Hakupalvelu, organisaatiopalvelu: Organisaatiopalvelu, koodistopalvelu: Koodistopalvelu) extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)

  def receive = {
    case q: HakijaQuery => {
      XMLQuery(q) pipeTo sender
    }
  }

  case class Hakukohde(koulutukset: Set[Komoto], hakukohdekoodi: String)

  case class Hakutoive(hakukohde: Hakukohde, kaksoistutkinto: Boolean)

  case class Hakemus(hakutoiveet: Seq[Hakutoive], hakemusnumero: String)

  case class Hakija(henkilo: Henkilo, suoritukset: Seq[Suoritus], opiskeluhistoria: Seq[Opiskelija], hakemus: Hakemus)

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
    case None => log.debug("no parentOid"); Future(None)
    case Some(oid) => log.debug("parentOid: " + oid); organisaatiopalvelu.get(oid).flatMap(_.map(resolveOppilaitosKoodi).getOrElse(Future(None)))
  }

  @Deprecated // TODO mäppää puuttuvat tiedot
  def getXmlHakutoiveet(hakija: Hakija): Future[Seq[XMLHakutoive]] = {
    log.debug("get xml hakutoiveet for: " + hakija.henkilo.oidHenkilo + ", hakutoiveet size: " + hakija.hakemus.hakutoiveet.size)
    val futures = hakija.hakemus.hakutoiveet.zipWithIndex.map(ht => {
      findOrgData(ht._1.hakukohde.koulutukset.head.tarjoaja).map(option => option.map((t) => {
        val o = t._1
        val k = t._2
        XMLHakutoive(
          hakujno = (ht._2 + 1).toShort,
          oppilaitos = k,
          opetuspiste = o.toimipistekoodi,
          opetuspisteennimi = o.nimi.get("fi").orElse(o.nimi.get("sv")),
          koulutus = ht._1.hakukohde.hakukohdekoodi,
          harkinnanvaraisuusperuste = None,
          urheilijanammatillinenkoulutus = None,
          yhteispisteet = None,
          valinta = None,
          vastaanotto = None,
          lasnaolo = None,
          terveys = None,
          aiempiperuminen = None,
          kaksoistutkinto = Some(ht._1.kaksoistutkinto)
        )
      }))
    }).toSeq
    Future.sequence(futures).map(_.flatten)
  }

  def extractOption(t: (Option[Organisaatio], Option[String])): Option[(Organisaatio, String)] = t._1 match {
    case None => None
    case Some(o) => Some((o, t._2.get))
  }

  def findOrgData(tarjoaja: String): Future[Option[(Organisaatio,String)]] = {
    log.debug("find org data for: " + tarjoaja)
    organisaatiopalvelu.get(tarjoaja).flatMap((o) => findOppilaitoskoodi(o.map(_.oid)).map(k => extractOption(o,k)))
  }

  import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._

  def resolvePohjakoulutus(suoritus: Option[Suoritus]): String = suoritus match {
    case Some(s) => {
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
    }
    case None => "7"
  }

  @Deprecated // TODO mäppää puuttuvat tiedot
  def getXmlHakemus(hakija: Hakija, opiskelutieto: Option[Opiskelija], lahtokoulu: Option[Organisaatio]): Future[Option[XMLHakemus]] = {
    val ht: Future[Seq[XMLHakutoive]] = getXmlHakutoiveet(hakija)
    ht.map(toiveet => {

      Try(hakija.hakemus).map((hakemus: Hakemus) =>
        XMLHakemus(
          vuosi = Try(hakemus.hakutoiveet.head.hakukohde.koulutukset.head.alkamisvuosi).get,
          kausi = if (Try(hakemus.hakutoiveet.head.hakukohde.koulutukset.head.alkamiskausi).get == Kausi.Kevät) "K" else "S",
          hakemusnumero = hakemus.hakemusnumero,
          lahtokoulu = lahtokoulu.flatMap(o => o.oppilaitosKoodi),
          lahtokoulunnimi = lahtokoulu.flatMap(o => o.nimi.get("fi")),
          luokka = opiskelutieto.map(_.luokka),
          luokkataso = opiskelutieto.map(_.luokkataso),
          pohjakoulutus = resolvePohjakoulutus(Try(hakija.suoritukset.head).toOption),
          todistusvuosi = Some("2014"),
          julkaisulupa = Some(false),
          yhteisetaineet = None,
          lukiontasapisteet = None,
          lisapistekoulutus = None,
          yleinenkoulumenestys = None,
          painotettavataineet = None,
          hakutoiveet = toiveet)
      ).toOption
    })
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
      log.debug("map hakemus henkilolle: " + hakija.henkilo.oidHenkilo + ", hakutoiveet size: " + hakija.hakemus.hakutoiveet.size)
      val yhteystiedot: Seq[Yhteystiedot] = hakija.henkilo.yhteystiedotRyhma.getOrElse(("hakemus", "yhteystietotyyppi1"), Seq())
      hakemus.map(hakemus => {
        val maaFuture = getMaakoodi(yhteystiedot.getOrElse("YHTEYSTIETO_MAA", "FIN"))
        maaFuture.flatMap((maa) => {
          val kansalaisuusFuture = getMaakoodi(Try(hakija.henkilo.kansalaisuus.head).map(k => k.kansalaisuusKoodi).recover{case _:Throwable => "FIN"}.get)
          kansalaisuusFuture.map((kansalaisuus) => {
            XMLHakija(
              hakija.henkilo.hetu,
              hakija.henkilo.oidHenkilo,
              hakija.henkilo.sukunimi,
              hakija.henkilo.etunimet,
              Some(hakija.henkilo.kutsumanimi),
              yhteystiedot.getOrElse("YHTEYSTIETO_KATUOSOITE", ""),
              yhteystiedot.getOrElse("YHTEYSTIETO_POSTINUMERO", "00000"),
              maa,
              kansalaisuus,
              yhteystiedot.get("YHTEYSTIETO_MATKAPUHELIN"),
              yhteystiedot.get("YHTEYSTIETO_PUHELINNUMERO"),
              yhteystiedot.get("YHTEYSTIETO_SAHKOPOSTI"),
              yhteystiedot.get("YHTEYSTIETO_KAUPUNKI"),
              if (hakija.henkilo.sukupuoli == "MIES") "1" else "2", hakija.henkilo.asiointiKieli.kieliKoodi,
              hakija.henkilo.markkinointilupa.getOrElse(false),
              hakemus
            )
          })
        })
      }).map(f => f.map(Option(_))).getOrElse(Future.successful(None))
    })
  }

  def getValue(h: Option[Map[String, String]], key: String, default: String = ""): String = {
    h.flatMap(_.get(key)).getOrElse(default)
  }

  def getHakija(hakemus: FullHakemus): Hakija = {
    val lahtokoulu: Option[String] = hakemus.vastauksetMerged.flatMap(_.get("lahtokoulu"))
    val v = hakemus.vastauksetMerged
    log.debug("getting hakija from full hakemus: " + hakemus.oid + ", vastauksetMerged: " + v)
    val hak = Hakija(
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
    log.debug("hakija: " + hak)
    hak
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
      Hakutoive(Hakukohde(koulutukset, hakukohdekoodi), Try(toiveet("preference" + t._1 + "-Koulutus-id-kaksoistutkinto").toBoolean).recover{ case _ => false }.get)
    })
  }

  def selectHakijat(q: HakijaQuery): Future[Seq[Hakija]] = {
    val y: Future[Future[Seq[Hakija]]] = hakupalvelu.find(q).map(hakemukset => {
      val kk: Seq[Future[Option[Hakija]]] = hakemukset.map(sh => hakupalvelu.get(sh.oid, q.user).map((fh: Option[FullHakemus]) => fh.map(getHakija(_))))
      val f: Future[Seq[Hakija]] = Future.sequence(kk).map((s: Seq[Option[Hakija]]) => s.flatten)
      f.onComplete(res => {log.debug("hakijat result: " + res); if (res.isFailure) res.failed.get.printStackTrace()})
      f
    })
    y.flatMap(f => f.map(g => g))
  }

  def XMLQuery(q: HakijaQuery): Future[XMLHakijat] = {
    log.debug("XMLQuery: " + q)
    selectHakijat(q).map(_.map(hakija2XMLHakija)).flatMap(Future.sequence(_).map(hakijat => XMLHakijat(hakijat.flatten)))
  }
}
