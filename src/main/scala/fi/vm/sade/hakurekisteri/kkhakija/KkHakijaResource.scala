package fi.vm.sade.hakurekisteri.kkhakija

import java.text.{ParseException, SimpleDateFormat}
import java.util.{Calendar, Date}

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.hakija.Hakuehto
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.haku.{Haku, GetHaku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{HakukohteenKoulutukset, HakukohdeOid, TarjontaException, Hakukohteenkoulutus}
import fi.vm.sade.hakurekisteri.rest.support.{Query, User, SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra.swagger.Swagger
import org.scalatra.{AsyncResult, InternalServerError, CorsSupport, FutureSupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.util.RicherString._

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Try

case class KkHakijaQuery(oppijanumero: Option[String], haku: Option[String], organisaatio: Option[String], hakukohde: Option[String], hakuehto: Hakuehto.Hakuehto, user: Option[User])

object KkHakijaQuery {
  def apply(params: Map[String,String], currentUser: Option[User]): KkHakijaQuery = new KkHakijaQuery(
    oppijanumero = params.get("oppijanumero").flatMap(_.blankOption),
    haku = params.get("haku").flatMap(_.blankOption),
    organisaatio = params.get("organisaatio").flatMap(_.blankOption),
    hakukohde = params.get("hakukohde").flatMap(_.blankOption),
    hakuehto = Try(Hakuehto.withName(params("hakuehto"))).recover{ case _ => Hakuehto.Kaikki }.get,
    user = currentUser
  )
}

case class InvalidSyntymaaikaException(m: String) extends Exception(m)

case class Ilmoittautuminen(kausi: String, // 2014S
                            tila: Int) // tilat (1 = läsnä; 2 = poissa; 3 = poissa, ei kuluta opintoaikaa; 4 = puuttuu)

case class Hakemus(haku: String,
                   hakuVuosi: Int,
                   hakuKausi: String,
                   hakemusnumero: String,
                   organisaatio: String,
                   hakukohde: String,
                   hakukohdeKkId: Option[String],
                   avoinVayla: Option[Boolean],
                   valinnanTila: Option[String],
                   vastaanottotieto: Option[String],
                   ilmoittautumiset: Seq[Ilmoittautuminen],
                   pohjakoulutus: Seq[String],
                   julkaisulupa: Boolean,
                   hakukohteenKoulutukset: Seq[Hakukohteenkoulutus])

case class Hakija(hetu: String,
                  oppijanumero: String,
                  sukunimi: String,
                  etunimet: String,
                  kutsumanimi: String,
                  lahiosoite: String,
                  postinumero: String,
                  postitoimipaikka: String,
                  maa: String,
                  kansalaisuus: String,
                  matkapuhelin: Option[String],
                  puhelin: Option[String],
                  sahkoposti: Option[String],
                  kotikunta: String,
                  sukupuoli: String,
                  aidinkieli: String,
                  asiointikieli: String,
                  koulusivistyskieli: String,
                  koulutusmarkkinointilupa: Option[Boolean],
                  kkKelpoisuusTarkastettava: Boolean,
                  onYlioppilas: Boolean,
                  hakemukset: Seq[Hakemus])

class KkHakijaResource(hakemukset: ActorRef, tarjonta: ActorRef, haut: ActorRef)(implicit system: ActorSystem, sw: Swagger)
    extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport {

  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 120.seconds

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }

  get("/") {
    val q = KkHakijaQuery(params, currentUser)
    logger.info("Query: " + q)

    new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds
      val is = getKkHakijat(q)
    }
  }

  incident {
    case t: TarjontaException => (id) => InternalServerError(IncidentReport(id, s"error with tarjonta: $t"))
    case t: HakuNotFoundException => (id) => InternalServerError(IncidentReport(id, s"error: $t"))
    case t: InvalidSyntymaaikaException => (id) => InternalServerError(IncidentReport(id, s"error: $t"))
  }

  def getKkHakijat(q: KkHakijaQuery): Future[Seq[Hakija]] = {
    val hakemusQuery: Query[FullHakemus] with Product with Serializable = q.oppijanumero match {
      case Some(o) => HenkiloHakijaQuery(o)
      case None => HakemusQuery(q)
    }

    for (
      fullHakemukset: Seq[FullHakemus] <- (hakemukset ? hakemusQuery).mapTo[Seq[FullHakemus]];
      hakijat <- getKkHakijat(fullHakemukset)
    ) yield hakijat
  }

  def getPohjakoulutukset(k: Koulutustausta): Seq[String] = {
    Map(
      "yo" -> k.pohjakoulutus_yo,
      "am" -> k.pohjakoulutus_am,
      "amt" -> k.pohjakoulutus_amt,
      "kk" -> k.pohjakoulutus_kk,
      "ulk" -> k.pohjakoulutus_ulk,
      "avoin" -> k.pohjakoulutus_avoin,
      "muu" -> k.pohjakoulutus_muu
    ).filter(t => t._2 == "true").keys.toSeq
  }

  val Pattern = "preference(\\d+)-Koulutus-id".r

  def getHakemukset(hakemus: FullHakemus): Future[Seq[Hakemus]] =
    Future.sequence((for {
      answers: HakemusAnswers <- hakemus.answers
      hakutoiveet: Map[String, String] <- answers.hakutoiveet
      lisatiedot: Lisatiedot <- answers.lisatiedot
      koulutustausta: Koulutustausta <- answers.koulutustausta
    } yield hakutoiveet.keys.collect {
      case Pattern(jno) if hakutoiveet(s"preference$jno-Koulutus-id") != "" =>
        val hakukohdeOid = hakutoiveet(s"preference$jno-Koulutus-id")
        for {
          hakukohteenkoulutukset: HakukohteenKoulutukset <- (tarjonta ? HakukohdeOid(hakukohdeOid)).mapTo[HakukohteenKoulutukset]
          haku <- (haut ? GetHaku(hakemus.applicationSystemId)).mapTo[Haku]
        } yield Hakemus(haku = hakemus.applicationSystemId,
            hakuVuosi = haku.vuosi,
            hakuKausi = haku.kausi,
            hakemusnumero = hakemus.oid,
            organisaatio = hakutoiveet(s"preference$jno-Opetuspiste-id"),
            hakukohde = hakutoiveet(s"preference$jno-Koulutus-id"),
            hakukohdeKkId = hakukohteenkoulutukset.ulkoinenTunniste,
            avoinVayla = None, // TODO valinnoista?
            valinnanTila = None, // TODO valinnoista
            vastaanottotieto = None, // TODO valinnoista
            ilmoittautumiset = Seq(), // TODO valinnoista
            pohjakoulutus = getPohjakoulutukset(koulutustausta),
            julkaisulupa = lisatiedot.lupaJulkaisu.exists(_ == "true"),
            hakukohteenKoulutukset = hakukohteenkoulutukset.koulutukset)
    }.toSeq).getOrElse(Seq()))

  def getHakukohdeOids(hakutoiveet: Map[String, String]): Seq[String] = {
    hakutoiveet.filter((t) => t._1.endsWith("Koulutus-id") && t._2 != "").map((t) => t._2).toSeq
  }

  def toKkSyntymaaika(d: Date): String = {
    val c = Calendar.getInstance()
    c.setTime(d)
    new SimpleDateFormat("ddMMyy").format(d) + (c.get(Calendar.YEAR) match {
      case y if y >= 2000 => "A"
      case y if y >= 1900 && y < 2000 => "-"
      case _ => ""
    })
  }

  def getHetu(hetu: Option[String], syntymaaika: Option[String], hakemusnumero: String): String = hetu match {
    case Some(h) => h
    case None => syntymaaika match {
      case Some(s) =>
        try {
          toKkSyntymaaika(new SimpleDateFormat("dd.MM.yyyy").parse(s))
        } catch {
          case t: ParseException => throw InvalidSyntymaaikaException(s"could not parse syntymäaika $s in hakemus $hakemusnumero")
        }
      case None => throw InvalidSyntymaaikaException(s"syntymäaika and hetu missing from hakemus $hakemusnumero")
    }
  }

  def getKkHakija(hakemus: FullHakemus): Future[Hakija] = {
    (for {
      answers: HakemusAnswers <- hakemus.answers
    } yield for {
        henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
        hakutoiveet: Map[String, String] <- answers.hakutoiveet
        lisatiedot: Lisatiedot <- answers.lisatiedot
      } yield for {
          hakemukset <- getHakemukset(hakemus)
        } yield Hakija(hetu = getHetu(henkilotiedot.Henkilotunnus, henkilotiedot.syntymaaika, hakemus.oid),
            oppijanumero = hakemus.personOid.get,
            sukunimi = henkilotiedot.Sukunimi.get,
            etunimet = henkilotiedot.Etunimet.get,
            kutsumanimi = henkilotiedot.Kutsumanimi.get,
            lahiosoite = henkilotiedot.lahiosoite.get,
            postinumero = henkilotiedot.Postinumero.get,
            postitoimipaikka = "", // TODO postinumerokoodistosta
            maa = henkilotiedot.asuinmaa.get,
            kansalaisuus = henkilotiedot.kansalaisuus.get,
            matkapuhelin = henkilotiedot.matkapuhelinnumero1,
            puhelin = None,
            sahkoposti = henkilotiedot.Sähköposti,
            kotikunta = henkilotiedot.kotikunta.get,
            sukupuoli = henkilotiedot.sukupuoli.get,
            aidinkieli = henkilotiedot.aidinkieli.get,
            asiointikieli = henkilotiedot.aidinkieli.get, // FIXME konvertoi arvoiksi 1=fi, 2=sv, 3=en, 9=muu
            koulusivistyskieli = henkilotiedot.aidinkieli.get,
            koulutusmarkkinointilupa = lisatiedot.lupaMarkkinointi.map(_ == "true"),
            kkKelpoisuusTarkastettava = true, // FIXME
            onYlioppilas = false, // FIXME
            hakemukset = hakemukset)).get.get // FIXME
  }

  def getKkHakijat(hakemukset: Seq[FullHakemus]): Future[Seq[Hakija]] = Future.sequence(hakemukset.map(getKkHakija))
}
