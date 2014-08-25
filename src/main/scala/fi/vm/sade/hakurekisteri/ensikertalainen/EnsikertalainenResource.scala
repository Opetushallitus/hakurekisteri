package fi.vm.sade.hakurekisteri.ensikertalainen

import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import fi.vm.sade.generic.common.HetuUtils
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloResponse
import fi.vm.sade.hakurekisteri.integration.virta.VirtaQuery
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, HakurekisteriJsonSupport}
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import org.scalatra.swagger.{SwaggerEngine, Swagger}
import org.scalatra.{AsyncResult, CorsSupport, FutureSupport}
import org.scalatra.json.JacksonJsonSupport

import scala.concurrent.{Future, ExecutionContext}

case class Ensikertalainen(ensikertalainen: Boolean)

class EnsikertalainenResource(suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef, virtaActor: ActorRef, henkiloActor: ActorRef)(implicit val sw: Swagger, system: ActorSystem) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with EnsikertalainenSwaggerApi with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport {
  override protected def applicationDescription: String = "Korkeakouluopintojen ensikertalaisuuden kyselyrajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  get("/", operation(query)) {
    val henkiloOid = params("henkilo")
    val ensikertalainen = onkoEnsikertalainen(henkiloOid)
    new AsyncResult() {
      override val is = ensikertalainen
    }
  }

  error {
    case t: Throwable =>
      logger.error("error in service", t)
      response.sendError(500, t.getMessage)
  }

  import akka.pattern.ask


  case class HetuNotFoundException(message: String) extends Exception(message)

  import scala.concurrent.duration._

  implicit val defaultTimeout: Timeout = 299.seconds



  def getHetu(henkilo: String): Future[String] = (henkiloActor ? henkilo).mapTo[HenkiloResponse].map(_.hetu match {
    case Some(hetu) if HetuUtils.isHetuValid(hetu) => hetu
    case Some(hetu) => throw HetuNotFoundException(s"hetu $hetu not valid for oid $henkilo")
    case None => throw HetuNotFoundException(s"hetu not found with oid $henkilo")
  })

  def getKkTutkinnot(henkiloOid: String): Future[Seq[Suoritus]] = ???

  def getKkOpiskeluoikeudet2014KesaJalkeen(henkiloOid: String): Future[Seq[Opiskeluoikeus]] = ???

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

  def saveVirtaResult(oikeudet:Seq[Opiskeluoikeus], tutkinnot: Seq[Suoritus]) {}

  def anyHasElements(futures: Future[Seq[_]]*):Future[Boolean] = Future.find(futures){!_.isEmpty}.map{
    case None => false
    case Some(_) => true
  }
}
