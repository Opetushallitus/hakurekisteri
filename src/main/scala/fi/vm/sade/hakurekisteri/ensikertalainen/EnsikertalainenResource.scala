package fi.vm.sade.hakurekisteri.ensikertalainen

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask
import _root_.akka.util.Timeout
import fi.vm.sade.generic.common.HetuUtils
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.integration.PreconditionFailedException
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloResponse
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, Komo}
import fi.vm.sade.hakurekisteri.integration.virta.VirtaQuery
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{OpiskeluoikeusQuery, Opiskeluoikeus}
import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, HakurekisteriJsonSupport}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus}
import org.joda.time.LocalDate
import org.scalatra.swagger.{SwaggerEngine, Swagger}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._

case class Ensikertalainen(ensikertalainen: Boolean)
case class HetuNotFoundException(message: String) extends Exception(message)
case class ParamMissingException(message: String) extends IllegalArgumentException(message)

class EnsikertalainenResource(suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef, virtaActor: ActorRef, henkiloActor: ActorRef, tarjontaActor: ActorRef)
                             (implicit val sw: Swagger, system: ActorSystem) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with EnsikertalainenSwaggerApi with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport {

  override protected def applicationDescription: String = "Korkeakouluhakujen kiintiöiden ensikertalaisuuden kyselyrajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 15.seconds
  val kesa2014: LocalDate = new LocalDate(2014, 6, 30)

  before() {
    contentType = formats("json")
  }

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

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
      case (komo, suoritus) if komo.isKorkeakoulututkinto => suoritus
    }
  }

  def findKomos(suoritukset: Seq[Suoritus]): Future[Seq[(Komo, Suoritus)]] = {
    Future.sequence(for (
      suoritus <- suoritukset
    ) yield (tarjontaActor ? GetKomoQuery(suoritus.komo))(10.seconds).mapTo[Option[Komo]].map((_, suoritus)).collect {
      case (Some(komo), suoritus) => (komo, suoritus)
    })
  }

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

    tutkinnot.foreach(t => logger.debug(s"tutkinnot: $t"))
    opiskeluoikeudet.foreach(o => logger.debug(s"opiskeluoikeudet: $o"))

    anySequenceHasElements(tutkinnot, opiskeluoikeudet).flatMap (
      if (_) {
        logger.debug(s"has tutkinto or opiskeluoikeus")
        Future.successful(false)
      } else checkEnsikertalainenFromVirta(henkiloOid)
    )
  }

  def checkEnsikertalainenFromVirta(henkiloOid: String): Future[Boolean] = {
    val virtaResult: Future[(Seq[Opiskeluoikeus], Seq[Suoritus])] = getHetu(henkiloOid).flatMap((hetu) => (virtaActor ? VirtaQuery(Some(henkiloOid), Some(hetu)))(10.seconds).mapTo[(Seq[Opiskeluoikeus], Seq[Suoritus])])
    for ((opiskeluoikeudet, suoritukset) <- virtaResult) yield {
      val filteredOpiskeluoikeudet = opiskeluoikeudet.filter(_.alkuPaiva.isAfter(kesa2014))
      saveVirtaResult(filteredOpiskeluoikeudet, suoritukset)
      logger.debug(s"checked from virta: opiskeluoikeudet.isEmpty ${filteredOpiskeluoikeudet.isEmpty}, suoritukset.isEmpty ${suoritukset.isEmpty}")
      filteredOpiskeluoikeudet.isEmpty && suoritukset.isEmpty
    }
  }

  def saveVirtaResult(opiskeluoikeudet: Seq[Opiskeluoikeus], suoritukset: Seq[Suoritus]) {
    logger.debug(s"saving virta result: opiskeluoikeudet size ${opiskeluoikeudet.size}, suoritukset size ${suoritukset.size}")
    opiskeluoikeudet.foreach(opiskeluoikeusActor ! _)
    suoritukset.foreach(suoritusActor ! _)
  }

  def anySequenceHasElements(futures: Future[Seq[_]]*): Future[Boolean] = Future.find(futures){!_.isEmpty}.map{
    case None => false
    case Some(_) => true
  }
}

