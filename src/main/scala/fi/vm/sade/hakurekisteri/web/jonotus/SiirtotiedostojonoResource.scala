package fi.vm.sade.hakurekisteri.web.jonotus

import java.lang.Boolean.parseBoolean
import java.util.Date
import java.util.concurrent.{ConcurrentHashMap, ConcurrentMap}


import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.web.kkhakija.{Query, KkHakijaQuery}
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport}
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

case class SiirtotiedostoPyynto(created: Date, personOids: Set[String], uuid: Option[String])

case class Sijoitus(sijoitus: Int)

class SiirtotiedostojonoResource(implicit val security: Security) extends ScalatraServlet
  with ScalateSupport with JValueResult
  with JacksonJsonSupport with SessionSupport
  with AtmosphereSupport with SecuritySupport {

  private val logger = LoggerFactory.getLogger(classOf[SiirtotiedostojonoResource])

  // TODO: ExecutorPool <- Submit jobs
  // Job should handle the broadcast

  private val queue: ConcurrentMap[Query, SiirtotiedostoPyynto] = new ConcurrentHashMap[Query, SiirtotiedostoPyynto]()

  get("/asiakirja/:id") {
    params.get("id") match {
      case Some(id) =>
        logger.info(s"User $currentUser downloaded file $id")
      case _ =>
        logger.error(s"No such file!")
    }
  }

  atmosphere("/") {
    new AtmosphereClient {
      def receive = {
        case Connected =>
          logger.info(s"User connected $currentUser!")
        case Disconnected(disconnector, couldContainErrors) =>
          couldContainErrors.foreach(logger.error("Disconnected with exception!",_))
        case JsonMessage(json) =>
          addToJono(json)
          //send(write(Sijoitus(44)))
        case event =>
          logger.error(s"Unexpected event in 'siirtotiedoston jonotus' $event")
      }
    }
  }
  def addToJono(json: JValue) = {
    val params = json.extract[Map[String,String]]
    val isKK = params.get("kk").exists(parseBoolean)
    val user = currentUser.get
    val personOid = user.username
    if(isKK) {
      val q = KkHakijaQuery(params, currentUser)
      addToJono(q, personOid)
    } else {
      val q = HakijaQuery(params, currentUser, 3)
      addToJono(q, personOid)
    }
    logger.info(s"User $currentUser sent message $json")
  }

  def addPersonOid(p: SiirtotiedostoPyynto, oid: String) = p.copy(personOids = p.personOids + oid)

  def addToJono(q: Query, personOid: String) = {
    Option(queue.get(q)) match {
      case Some(siirtotiedostopyynto) =>
        queue.replace(q, siirtotiedostopyynto, addPersonOid(siirtotiedostopyynto, personOid))
      case None =>
        var newPyynto = SiirtotiedostoPyynto(new Date, Set(personOid), None)
        while(!queue.putIfAbsent(q, newPyynto).equals(newPyynto)) {
          newPyynto = addPersonOid(queue.get(q), personOid)
        }
    }
  }

  override protected implicit def jsonFormats: Formats = HakurekisteriJsonSupport.format

}
