package fi.vm.sade.hakurekisteri.web.jonotus

import java.io.OutputStream
import java.lang.Boolean.parseBoolean
import java.util.Date
import java.util.concurrent._
import java.util.function.BiFunction


import com.google.common.cache.{RemovalNotification, RemovalListener, CacheLoader, CacheBuilder}
import fi.vm.sade.hakurekisteri.hakija.XMLHakijat
import fi.vm.sade.hakurekisteri.rest.support.{User, HakurekisteriJsonSupport}
import fi.vm.sade.hakurekisteri.web.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.web.kkhakija.{Query, KkHakijaQuery}
import fi.vm.sade.hakurekisteri.web.rest.support.{DownloadSupport, ExcelSupport, Security, SecuritySupport}
import org.scalatra.SessionSupport
import org.scalatra._
import org.scalatra.atmosphere._
import org.slf4j.LoggerFactory
import scalate.ScalateSupport
import org.scalatra.json.{JValueResult, JacksonJsonSupport}
import scala.concurrent._
import ExecutionContext.Implicits.global
import org.json4s.jackson.Serialization.{write}
import org.json4s._

import scala.util.Try

class SiirtotiedostojonoResource(jono: Siirtotiedostojono)(implicit val security: Security) extends ScalatraServlet
  with ScalateSupport with JValueResult
  with JacksonJsonSupport with SessionSupport
  with AtmosphereSupport with SecuritySupport {

  private val logger = LoggerFactory.getLogger(classOf[SiirtotiedostojonoResource])

  atmosphere("/") {
    new AtmosphereClient {
      def receive = {
        case Connected =>
          logger.info(s"User connected $currentUser!")
        case Disconnected(disconnector, couldContainErrors) =>
          handle(None,uuid, currentUser) match {
            case LoggedInUser(id, personOid) =>
              logger.error(s"User $personOid disconnected!")
            case _ =>
              logger.error("Got no usable information from disconnected user!")
          }
          couldContainErrors.foreach(logger.error("Disconnected with exception!",_))
        case JsonMessage(json) =>
          handle(Some(json), uuid, currentUser) match {
            case QueryWithExistingAsiakirja(id, personOid, query) =>
              send(write(Valmis(jono.queryToShortUrl(query))))
              logger.info(s"User $currentUser requested existing asiakirja with $query")
            case AsiakirjaQuery(id, personOid, query) =>
              jono.addToJono(query, personOid)
              send(write(Sijoitus(jono.positionInQueue(query))))
              logger.info(s"User $currentUser requested asiakirja with $query")
            case RequestSijoitusQuery(id, personOid, query) =>
              val position = jono.positionInQueue(query)
              send(write(Sijoitus(position)))
              logger.info(s"User $currentUser in position $position")
            case _ =>
              logger.error(s"Unexpected query from user $currentUser")
          }
        case event =>
          logger.error(s"Unexpected event in 'siirtotiedoston jonotus' $event")
      }
    }
  }
  private def handle(json: Option[JValue], uuid: String, user: Option[User]): Event = {
    currentUser match {
      case Some(user) =>
        val personOid = user.username
        val params = json.map(_.extract[Map[String,String]]).getOrElse(Map[String,String]())
        toQuery(params,user) match {
          case Some(query) =>
            val isAlreadyCreated = jono.isExistingAsiakirja(query)
            if(isAlreadyCreated) {
              QueryWithExistingAsiakirja(uuid, personOid, query)
            } else {
              val isSijoitusQuery = params.get("position").exists(parseBoolean)
              if(isSijoitusQuery) {
                RequestSijoitusQuery(uuid, personOid, query)
              } else {
                AsiakirjaQuery(uuid, personOid, query)
              }
            }
          case None =>
            LoggedInUser(uuid, personOid)
        }
      case None =>
        AnonymousUser(uuid)
    }
  }

  private def toQuery(params: Map[String,String], u: User): Option[Query] = {
    def act = {
      val isKK = params.get("kk").exists(parseBoolean)
      if(isKK) {
        KkHakijaQuery(params, Option(u))
      } else {
        HakijaQuery(params, Option(u), 3)
      }
    }
    Try(act).toOption
  }





  override protected implicit def jsonFormats: Formats = HakurekisteriJsonSupport.format

}

case class SiirtotiedostoPyynto(created: Date, personOids: Set[String])
case class Sijoitus(sijoitus: Int)
case class Valmis(asiakirjaId: String)

trait Event {
  val id: String
}
trait UserEvent extends Event {
  val personOid: String
}
case class LoggedInUser(id: String, personOid: String) extends UserEvent
case class QueryWithExistingAsiakirja(id: String, personOid: String, q: Query) extends UserEvent
case class RequestSijoitusQuery(id: String, personOid: String, q: Query) extends UserEvent
case class AsiakirjaQuery(id: String, personOid: String, q: Query) extends UserEvent
case class AnonymousUser(id: String) extends Event
