package fi.vm.sade.hakurekisteri.web.rekisteritiedot

import fi.vm.sade.hakurekisteri.rest.support.{User, SpringSecuritySupport, HakurekisteriJsonSupport, Registers}
import _root_.akka.actor.{ActorRef, ActorSystem}
import org.scalatra.swagger.{SwaggerEngine, Swagger}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.oppija.{Todistus, Oppija, OppijaFetcher}
import fi.vm.sade.hakurekisteri.web.oppija.OppijaSwaggerApi
import org.scalatra.json.JacksonJsonSupport
import fi.vm.sade.hakurekisteri.web.rest.support._
import scala.concurrent.{Future, ExecutionContext}
import _root_.akka.util.Timeout
import _root_.akka.event.{Logging, LoggingAdapter}
import scala.compat.Platform
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusQuery
import scala.Some
import fi.vm.sade.hakurekisteri.integration.hakemus.HenkiloHakijaQuery
import fi.vm.sade.hakurekisteri.integration.hakemus.FullHakemus
import fi.vm.sade.hakurekisteri.integration.virta.VirtaConnectionErrorException
import fi.vm.sade.hakurekisteri.web.rest.support.UserNotAuthorized
import fi.vm.sade.hakurekisteri.suoritus.{AllForMatchinHenkiloSuoritusQuery, VirallinenSuoritus, SuoritusQuery, Suoritus}
import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.ensikertalainen.{EnsikertalainenQuery, Ensikertalainen}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{OpiskeluoikeusQuery, Opiskeluoikeus}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaQuery, Opiskelija}
import org.scalatra.{InternalServerError, CorsSupport, FutureSupport, AsyncResult}
import org.joda.time.DateTime
import fi.vm.sade.hakurekisteri.Config
import org.scalatra.commands.ModelValidation
import scalaz.{Failure, Success, NonEmptyList, Validation}
import java.util
import fi.vm.sade.hakurekisteri.web.validation.{SimpleValidatable, Validatable, ScalaValidator}
import collection.JavaConversions._


class RekisteritiedotResource(val rekisterit: Registers)
                    (implicit val system: ActorSystem, sw: Swagger)
  extends HakuJaValintarekisteriStack with TiedotFetcher with RekisteritiedotSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport with QueryLogging {

  override protected def applicationDescription: String = "Oppijan tietojen koosterajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 500.seconds
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  val valid = new hakurekisteri.api.HakurekisteriValidator() with ScalaValidator

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }

  def getUser: User = {
    currentUser match {
      case Some(u) => u
      case None => throw UserNotAuthorized(s"anonymous access not allowed")
    }
  }

  get("/", operation(query)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val q = queryForParams(params)

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val tiedotFuture = fetchTiedot(q)

      logQuery(q, t0, tiedotFuture)

      val is = tiedotFuture
    }
  }



  import org.scalatra.util.RicherString._

  def queryForParams(params: Map[String,String]): RekisteriQuery = RekisteriQuery(
    oppilaitosOid = params.get("oppilaitosOid").flatMap(_.blankOption),
    vuosi = params.get("vuosi").flatMap(_.blankOption)
  )


  get("/:oid", operation(read)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val q = HenkiloHakijaQuery(params("oid"))

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val tiedotFuture = fetchTiedot(params("oid"))
      logQuery(q, t0, tiedotFuture)

      val is = tiedotFuture
    }

  }


  get("/light") {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val q = queryForParams(params)

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val tiedotFuture = fetchTiedot(q)

      logQuery(q, t0, tiedotFuture)

      val is = tiedotFuture.map(for (
       oppija: Oppija <- _
      ) yield LightWeightTiedot(oppija.oppijanumero, oppija.opiskelu.map(_.luokka).mkString(", ").blankOption, hasArvosanat(oppija.suoritukset)))

      val tarkastetut = Set(Config.perusopetusKomoOid, Config.lisaopetusKomoOid, Config.lukioKomoOid)


      def hasArvosanat(todistukset:Seq[Todistus]): Boolean = {
        implicit val v: Validatable[Todistus] = SimpleValidatable((t) => ValidatedTodistus(t.suoritus, t.arvosanat))
        !todistukset.exists{
          case Todistus(s: VirallinenSuoritus, arvosanat) if tarkastetut.contains(s.komo) && arvosanat.isEmpty => true
          case t:Todistus => valid.validateData(t).isFailure
          case default => false
        }
      }
    }




  }



  incident {
    case t: VirtaConnectionErrorException => (id) => InternalServerError(IncidentReport(id, "virta error"))
  }
}

trait TiedotFetcher {

  val rekisterit: Registers

  protected implicit def executor: ExecutionContext
  implicit val defaultTimeout: Timeout

  def fetchTodistuksetFor(query: RekisteriQuery)(implicit user: User):Future[Seq[Todistus]] = for (
    suoritukset <- fetchSuoritukset(query);
    todistukset <- fetchTodistukset(suoritukset)
  ) yield todistukset



  def fetchTiedot(q: RekisteriQuery)(implicit user: User): Future[Seq[Oppija]] = {
    for (
      suoritukset <- fetchSuoritukset(q);
      opiskelijat <- fetchOpiskelu(q);
      crossed <- crossCheck(opiskelijat, suoritukset)
    ) yield crossed.toSeq
  }

  def fetchTiedot(oid: String)(implicit user: User): Future[Oppija] = {
    for (
      todistukset <- fetchTodistukset(oid);
      opiskeluhistoria <- fetchOpiskelu(oid);
      opintoOikeudet <- fetchOpiskeluoikeudet(oid)
    ) yield Oppija(oid, opiskeluhistoria, todistukset, opintoOikeudet, None)
  }


  def crossCheck(opiskelijat: Seq[Opiskelija], todistukset: Seq[Suoritus with Identified[UUID]])(implicit user: User): Future[Seq[Oppija]] = {
    val opiskelijatiedot = opiskelijat.groupBy(_.henkiloOid)
    val suorittajat = todistukset.groupBy(_.henkiloOid)
    val found = (for (
      (suorittaja, suoritukset) <- suorittajat
    ) yield fetchTodistukset(suoritukset).map(Oppija(suorittaja, opiskelijatiedot.getOrElse(suorittaja, Seq()), _, Seq(), None))) ++
    (for (
      (opiskelija, historia) <- opiskelijatiedot   if !suorittajat.contains(opiskelija)
    ) yield fetchTodistukset(opiskelija).map(Oppija(opiskelija, historia, _, Seq(), None)))


    Future.sequence(found.toSeq)
  }


  def fetchTodistukset(henkilo: String)(implicit user: User): Future[Seq[Todistus]] = for (
    suoritukset <- fetchSuoritukset(henkilo);
    todistukset <- fetchTodistukset(suoritukset)
  ) yield todistukset

  import akka.pattern.ask

  def fetchTodistukset(suoritukset: Seq[Suoritus with Identified[UUID]])(implicit user: User):Future[Seq[Todistus]] = Future.sequence(
    for (
      suoritus <- suoritukset
    ) yield for (
      arvosanat <- (rekisterit.arvosanaRekisteri ? AuthorizedQuery(ArvosanaQuery(suoritus = Some(suoritus.id)), user)).mapTo[Seq[Arvosana]]
    ) yield Todistus(suoritus, arvosanat))




  def fetchOpiskeluoikeudet(henkiloOid: String)(implicit user: User): Future[Seq[Opiskeluoikeus]] = {
    (rekisterit.opiskeluoikeusRekisteri ? AuthorizedQuery(OpiskeluoikeusQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Opiskeluoikeus]]
  }

  def fetchOpiskelu(henkiloOid: String)(implicit user: User): Future[Seq[Opiskelija]] = {
    (rekisterit.opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Opiskelija]]
  }

  def fetchOpiskelu(q: RekisteriQuery)(implicit user: User): Future[Seq[Opiskelija]] = {
    (rekisterit.opiskelijaRekisteri ? AuthorizedQuery(OpiskelijaQuery(oppilaitosOid = q.oppilaitosOid, vuosi = q.vuosi), user)).mapTo[Seq[Opiskelija]]
  }

  def fetchSuoritukset(henkiloOid: String)(implicit user: User): Future[Seq[Suoritus with Identified[UUID]]] = {
    (rekisterit.suoritusRekisteri ? AuthorizedQuery(SuoritusQuery(henkilo = Some(henkiloOid)), user)).mapTo[Seq[Suoritus with Identified[UUID]]]
  }

  def fetchSuoritukset(q: RekisteriQuery)(implicit user: User): Future[Seq[Suoritus with Identified[UUID]]] = {
    (rekisterit.suoritusRekisteri ? AuthorizedQuery(AllForMatchinHenkiloSuoritusQuery(myontaja = q.oppilaitosOid, vuosi = q.vuosi), user)).mapTo[Seq[Suoritus with Identified[UUID]]]
  }

}

case class RekisteriQuery(oppilaitosOid: Option[String], vuosi: Option[String])


case class LightWeightTiedot(henkilo: String, luokka: Option[String], arvosanat: Boolean)

case class ValidatedTodistus(suoritus: Suoritus, arvosanas: java.util.List[Arvosana], suppressed: java.util.List[String] = Nil)