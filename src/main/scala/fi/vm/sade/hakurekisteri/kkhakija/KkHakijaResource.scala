package fi.vm.sade.hakurekisteri.kkhakija

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.hakija.Hakuehto
import fi.vm.sade.hakurekisteri.integration.hakemus._
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

class KkHakijaResource(hakemukset: ActorRef, tarjonta: ActorRef)(implicit system: ActorSystem, sw: Swagger)
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

  val Pattern = "preference(\\d+)-Koulutus-id".r

  def getHakemukset(hakemus: FullHakemus): Future[Seq[Hakemus]] =
    Future.sequence((for {
      answers: HakemusAnswers <- hakemus.answers
      hakutoiveet: Map[String, String] <- answers.hakutoiveet
      lisatiedot: Lisatiedot <- answers.lisatiedot
    } yield hakutoiveet.keys.collect {
      case Pattern(jno) if hakutoiveet(s"preference$jno-Koulutus-id") != "" =>
        val hakukohdeOid = hakutoiveet(s"preference$jno-Koulutus-id")
        for {
          hakukohteenkoulutukset: Seq[Hakukohteenkoulutus] <- (tarjonta ? HakukohdeOid(hakukohdeOid)).mapTo[HakukohteenKoulutukset].map(_.koulutukset)
        } yield Hakemus(haku = hakemus.applicationSystemId,
            hakuVuosi = None, // TODO hausta
            hakuKausi = None, // TODO hausta
            hakemusnumero = hakemus.oid,
            organisaatio = hakutoiveet(s"preference$jno-Opetuspiste-id"),
            hakukohde = hakutoiveet(s"preference$jno-Koulutus-id"),
            hakukohdeKkId = None, // TODO tarjonnasta
            avoinVayla = None, // TODO valinnoista?
            valinnanTila = None, // TODO valinnoista
            vastaanottotieto = None, // TODO valinnoista
            ilmoittautumiset = Seq(), // TODO valinnoista
            pohjakoulutus = "", // TODO arvot?
            julkaisulupa = lisatiedot.lupaJulkaisu.exists(_ == "true"),
            hakukohteenKoulutukset = hakukohteenkoulutukset)
    }.toSeq).getOrElse(Seq()))

  def getHakukohdeOids(hakutoiveet: Map[String, String]): Seq[String] = {
    hakutoiveet.filter((t) => t._1.endsWith("Koulutus-id") && t._2 != "").map((t) => t._2).toSeq
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
        } yield Hakija(hetu = henkilotiedot.Henkilotunnus,
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
            asiointikieli = henkilotiedot.aidinkieli.get, // FIXME konvertoi arvoiksi 1, 2, 3
            koulusivistyskieli = henkilotiedot.aidinkieli.get,
            koulutusmarkkinointilupa = lisatiedot.lupaMarkkinointi.map(_ == "true"),
            kkKelpoisuusTarkastettava = true, // FIXME
            onYlioppilas = false, // FIXME
            hakemukset = hakemukset)).get.get // FIXME
  }

  def getKkHakijat(hakemukset: Seq[FullHakemus]): Future[Seq[Hakija]] = Future.sequence(hakemukset.map(getKkHakija))

  case class Ilmoittautuminen(kausi: String, // 2014S
                              tila: Int) // tilat (1 = läsnä; 2 = poissa; 3 = poissa, ei kuluta opintoaikaa; 4 = puuttuu)
  case class Hakemus(haku: String,
                     hakuVuosi: Option[Int],
                     hakuKausi: Option[String],
                     hakemusnumero: String,
                     organisaatio: String,
                     hakukohde: String,
                     hakukohdeKkId: Option[String],
                     avoinVayla: Option[Boolean],
                     valinnanTila: Option[String],
                     vastaanottotieto: Option[String],
                     ilmoittautumiset: Seq[Ilmoittautuminen],
                     pohjakoulutus: String,
                     julkaisulupa: Boolean,
                     hakukohteenKoulutukset: Seq[Hakukohteenkoulutus])
  case class Hakija(hetu: Option[String],
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

}

