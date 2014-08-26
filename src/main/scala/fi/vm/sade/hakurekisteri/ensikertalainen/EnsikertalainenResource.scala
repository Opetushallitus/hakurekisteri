package fi.vm.sade.hakurekisteri.ensikertalainen

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask
import _root_.akka.util.Timeout
import fi.vm.sade.generic.common.HetuUtils
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.integration.PreconditionFailedException
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloResponse
import fi.vm.sade.hakurekisteri.integration.tarjonta.{TarjontaClient, Komo}
import fi.vm.sade.hakurekisteri.integration.virta.VirtaQuery
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{OpiskeluoikeusQuery, Opiskeluoikeus}
import fi.vm.sade.hakurekisteri.rest.support.{IncidentReporting, SpringSecuritySupport, HakurekisteriJsonSupport}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus}
import org.joda.time.LocalDate
import org.scalatra.swagger.{SwaggerEngine, Swagger}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._

case class Ensikertalainen(ensikertalainen: Boolean)

case class HetuNotFoundException(message: String) extends Exception(message)

class EnsikertalainenResource(suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef, virtaActor: ActorRef, henkiloActor: ActorRef, tarjontaClient: TarjontaClient)
                             (implicit val sw: Swagger, system: ActorSystem) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with EnsikertalainenSwaggerApi with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport with IncidentReporting {

  override protected def applicationDescription: String = "Korkeakouluopintojen ensikertalaisuuden kyselyrajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 15.seconds
  val kesa2014: LocalDate = new LocalDate(2014, 6, 30)

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }

  case class ParamMissingException(message: String) extends IllegalArgumentException(message)

  get("/", operation(query)) {
    try {
      val henkiloOid = params("henkilo")
      val ensikertalainen = onkoEnsikertalainen(henkiloOid)
      new AsyncResult() {
        override implicit def timeout: Duration = 20.seconds
        override val is = ensikertalainen.map((b) => Ensikertalainen(b))
      }
    } catch {
      case t: NoSuchElementException => throw ParamMissingException("parameter henkilo missing")
    }
  }

  incident {
    case t: ParamMissingException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: HetuNotFoundException => (id) => BadRequest(IncidentReport(id, "error validating hetu"))
    case t: PreconditionFailedException => (id) => InternalServerError(IncidentReport(id, "backend service failed"))
  }

  def getHetu(henkilo: String): Future[String] = (henkiloActor ? henkilo).mapTo[HenkiloResponse].map(_.hetu match {
    case Some(hetu) if HetuUtils.isHetuValid(hetu) => hetu
    case Some(hetu) => logger.error(s"hetu $hetu not valid for oid $henkilo"); throw HetuNotFoundException(s"hetu not valid for oid $henkilo")
    case None => throw HetuNotFoundException(s"hetu not found with oid $henkilo")
  })

  def getKkTutkinnot(henkiloOid: String): Future[Seq[Suoritus]] = {
    for (
      suoritukset <- getSuoritukset(henkiloOid);
      tupled <- findKomos(suoritukset)
    ) yield tupled collect {
      case (Some(komo), suoritus) if komo.isKorkeakoulututkinto => suoritus
    }
  }

  def findKomos(suoritukset: Seq[Suoritus]): Future[Seq[(Option[Komo], Suoritus)]] = {
    Future.sequence(for (
      suoritus <- suoritukset
    ) yield getKomo(suoritus.komo).map((_, suoritus)))
  }

  def getKomo(oid: String): Future[Option[Komo]] = tarjontaClient.getKomo(oid)

  def getSuoritukset(henkiloOid: String): Future[Seq[Suoritus]] = {
    (suoritusActor ? SuoritusQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Suoritus]]
  }

  def getKkOpiskeluoikeudet2014KesaJalkeen(henkiloOid: String): Future[Seq[Opiskeluoikeus]] = {
    for (
      opiskeluoikeudet <- (opiskeluoikeusActor ? OpiskeluoikeusQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Opiskeluoikeus]]
    ) yield opiskeluoikeudet.filter(_.alkuPaiva.isAfter(kesa2014))
  }

  def onkoEnsikertalainen(henkiloOid: String): Future[Boolean] = {
    val tutkinnot: Future[Seq[Suoritus]] = getKkTutkinnot(henkiloOid)
    val opiskeluoikeudet: Future[Seq[Opiskeluoikeus]] = getKkOpiskeluoikeudet2014KesaJalkeen(henkiloOid)

    anyHasElements(tutkinnot, opiskeluoikeudet).flatMap (
      if (_) Future.successful(false)
      else checkEnsikertalainenFromVirta(henkiloOid)
    )
  }

  def checkEnsikertalainenFromVirta(henkiloOid: String): Future[Boolean] = {
    val x: Future[(Seq[Opiskeluoikeus], Seq[Suoritus])] = getHetu(henkiloOid).flatMap((hetu) => (virtaActor ? VirtaQuery(Some(henkiloOid), Some(hetu))).mapTo[(Seq[Opiskeluoikeus], Seq[Suoritus])])
    for ((oikeudet, tutkinnot) <- x) yield {
      saveVirtaResult(oikeudet, tutkinnot)
      oikeudet.isEmpty && tutkinnot.isEmpty
    }
  }

  def saveVirtaResult(oikeudet: Seq[Opiskeluoikeus], tutkinnot: Seq[Suoritus]): Unit = {
    // TODO
  }

  def anyHasElements(futures: Future[Seq[_]]*): Future[Boolean] = Future.find(futures){!_.isEmpty}.map{
    case None => false
    case Some(_) => true
  }
}

