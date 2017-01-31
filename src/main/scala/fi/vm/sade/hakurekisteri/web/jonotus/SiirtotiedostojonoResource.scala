package fi.vm.sade.hakurekisteri.web.jonotus

import java.lang.Boolean.parseBoolean

import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.web.kkhakija.{KkHakijaQuery, Query}
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat.ApiFormat
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.json4s._
import org.json4s.jackson.Serialization.write
import org.scalatra.{SessionSupport, _}
import org.scalatra.json.{JValueResult, JacksonJsonSupport}
import org.slf4j.LoggerFactory

import scala.util.Try

class SiirtotiedostojonoResource(jono: Siirtotiedostojono)(implicit val security: Security) extends ScalatraServlet
  with JValueResult
  with JacksonJsonSupport with SessionSupport with SecuritySupport {

  private val logger = LoggerFactory.getLogger(classOf[SiirtotiedostojonoResource])

  post("/") {

    toEvent(readJsonFromBody(request.body), currentUser) match {
      case QueryWithExistingAsiakirja(personOid, query) =>
        val shortId = jono.queryToShortId(query)
        logger.debug(s"User $currentUser requested existing asiakirja with $query")
        halt(status=200, body=write(Valmis(shortId)))
      case RequestSijoitusQuery(personOid, query, position, isNew) =>
        if(isNew) {
          logger.debug(s"User $currentUser requested asiakirja with $query")
        } else {
          logger.debug(s"User $currentUser in position $position")
        }
        halt(status=200, body=write(Sijoitus(position)))
      case AnonymousUser() =>
        halt(status=401, body="Anonymous user not authorized!")
      case _ =>
        logger.error(s"Unexpected query from user $currentUser")
        halt(status=400, body="Query is not valid!")
    }
  }

  private def toEvent(json: JValue, user: Option[User]): Event = {
    currentUser match {
      case Some(user) =>
        val personOid = user.username
        implicit val formats = DefaultFormats
        val extracted = json.extract[Map[String,JValue]].filter(_._2 != JNull)
        val params = extracted.mapValues(_.values.toString)
        toQuery(params,user) match {
          case Some(query) =>
            def incSijoitus(s: Int) = s + 1
            val position = Option(jono.positionInQueue(query))
              .filter(_ != -1)
              .map(incSijoitus)

            position match {
              case Some(pos) =>
                RequestSijoitusQuery(personOid, query, pos, false)
              case None =>
                val isAlreadyCreated = jono.isExistingAsiakirja(query)
                if(isAlreadyCreated) {
                  QueryWithExistingAsiakirja(personOid, query)
                } else {
                  RequestSijoitusQuery(personOid, query, incSijoitus(jono.addToJono(query, personOid)), true)
                }
            }
          case None =>
            LoggedInUser(personOid)
        }
      case None =>
        AnonymousUser()
    }
  }

  private def toQuery(params: Map[String,String], u: User): Option[QueryAndFormat] = {
    def act = {
      val isKK = params.get("kk").exists(parseBoolean)
      val tyyppi = Try(ApiFormat.withName(params("tyyppi"))).getOrElse(ApiFormat.Json)
      if(isKK) {
        QueryAndFormat(KkHakijaQuery(params, Option(u)),tyyppi)
      } else {
        QueryAndFormat(HakijaQuery(params, Option(u), 3),tyyppi)
      }
    }
    Try(act).toOption
  }

  override protected implicit def jsonFormats: Formats = HakurekisteriJsonSupport.format


}

case class QueryAndFormat(query: Query, format: ApiFormat)
case class Sijoitus(sijoitus: Int)
case class Valmis(asiakirjaId: String)
case class Ping()

trait UserEvent extends Event {
  val personOid: String
}
case class LoggedInUser(personOid: String) extends UserEvent
case class QueryWithExistingAsiakirja(personOid: String, q: QueryAndFormat) extends UserEvent
case class RequestSijoitusQuery(personOid: String, q: QueryAndFormat, position: Int, isNew: Boolean) extends UserEvent
case class AnonymousUser() extends Event
