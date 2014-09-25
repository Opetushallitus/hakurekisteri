package fi.vm.sade.hakurekisteri.kkhakija

import akka.actor.{ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.hakija.{Hakuehto, HakijaQ}
import fi.vm.sade.hakurekisteri.hakija.Hakuehto.Hakuehto
import fi.vm.sade.hakurekisteri.rest.support.{User, SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra.swagger.Swagger
import org.scalatra.{AsyncResult, InternalServerError, CorsSupport, FutureSupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.util.RicherString._

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Try

class KkHakijaResource()(implicit system: ActorSystem, sw: Swagger)
    extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport {

  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 60.seconds

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
      val is = Future.successful(Seq[Hakija]())
    }
  }

  incident {
    case t: Throwable => (id) => InternalServerError(IncidentReport(id, "kk hakija error")) // TODO kerro virhetilanteesta
  }

  case class KkHakijaQuery(oppijanumero: Option[String],
                           override val haku: Option[String],
                           override val organisaatio: Option[String],
                           override val hakukohdekoodi: Option[String],
                           override val hakuehto: Hakuehto,
                           override val user: Option[User]) extends HakijaQ

  object KkHakijaQuery {
    def apply(params: Map[String,String], user: Option[User]): KkHakijaQuery = KkHakijaQuery(
      params.get("oppijanumero").flatMap(_.blankOption),
      params.get("haku").flatMap(_.blankOption),
      params.get("organisaatio").flatMap(_.blankOption),
      params.get("hakukohdekoodi").flatMap(_.blankOption),
      Try(Hakuehto.withName(params("hakuehto"))).recover{ case _ => Hakuehto.Kaikki }.get,
      user
    )
  }

  case class Hakukohteenkoulutus(
    komoOid: String,
    tkKoulutuskoodi: String,
    kkKoulutusId: Option[String]
  )
  case class Ilmoittautuminen(
    kausi: String,
    tila: Int // tilat (1 = läsnä; 2 = poissa; 3 = poissa, ei kuluta opintoaikaa; 4 = puuttuu)
  )
  case class Hakemus(
    haku: String,
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
    pohjakoulutus: String,
    julkaisulupa: Boolean,
    hakukohteenKoulutukset: Seq[Hakukohteenkoulutus]
  )
  case class Hakija(
    hetu: Option[String],
    oppijanumero: String,
    sukunimi: String,
    etunimet: String,
    kutsumanimi: Option[String],
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
    koulutusmarkkinointilupa: Boolean,
    kkKelpoisuusTarkastettava: Boolean,
    onYlioppilas: Boolean,
    hakemukset: Seq[Hakemus]
  )
}

