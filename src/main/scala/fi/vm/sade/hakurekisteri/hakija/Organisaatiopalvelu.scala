package fi.vm.sade.hakurekisteri.hakija

import com.stackmob.newman._
import com.stackmob.newman.dsl._
import scala.concurrent._
import scala.concurrent.duration._
import java.net.URL
import com.stackmob.newman.response.HttpResponseCode
import org.slf4j.LoggerFactory
import akka.actor.{Cancellable, Actor}
import scala.compat.Platform
import akka.event.Logging
import scala.Some
import scala.util.Try


trait Organisaatiopalvelu {

  def getAll:Future[Seq[String]]
  def get(str: String): Future[Option[Organisaatio]]

}

case class Organisaatio(oid: String, nimi: Map[String, String], toimipistekoodi: Option[String], oppilaitosKoodi: Option[String], parentOid: Option[String])

import akka.pattern.pipe

class OrganisaatioActor(palvelu: Organisaatiopalvelu) extends Actor {


  class Refresh

  private object refresh  extends Refresh

  case class Refetch(oid:String)
  implicit val executionContext: ExecutionContext = context.dispatcher


  private var cache:Map[String,(Long, Future[Option[Organisaatio]])] = Map()


  var cancellable: Option[Cancellable] = None

  override def preStart(): Unit = {
    palvelu.getAll.onSuccess {
      case s:Seq[String] =>
        fetchOrgs(s)
    }
    cancellable = Some(context.system.scheduler.schedule(10.minutes,
      10.minutes,
      self,
      refresh)(context.dispatcher, self))


  }

  def fetchOrgs(s:Seq[String], mf: String => AnyRef = (s) => s) = s.grouped(10).zipWithIndex.foreach((t: (Seq[String], Int)) => {
    t._1 foreach(oid => {context.system.scheduler.scheduleOnce(t._2.second, self, mf(oid))})
  })


  override def postStop(): Unit = {
    cancellable.foreach(_.cancel())

  }



  val timeToLive = 24.hours

  val log = Logging(context.system, this)

  case class Save(oid:String, value: (Long, Future[Option[Organisaatio]]))

  override def receive: Receive = {
    case oid:String => find(oid)._2 pipeTo sender
    case Refetch(oid) => val result = newValue(oid)
                         result._2.onSuccess {case _ => self ! Save(oid, result)}
                         result._2.onFailure {case _ => log.warning("fetching organisation data for %s failed. Trying again".format(oid))
                                                        self ! Refetch(oid)}
    case Save(oid,result) => cache = cache + (oid -> result)
    case refresh:Refresh => Future(fetchOrgs(cache.toSeq.filter(t => t._2._1 < Platform.currentTime).map(_._1), Refetch))
  }


  def find(oid: String): (Long, Future[Option[Organisaatio]]) = {
    Try(cache(oid)).recoverWith{ case e: NoSuchElementException => Try({val result = newValue(oid); cache = cache + (oid -> result); result})}.get
  }

  def newValue(oid: String): (Long, Future[Option[Organisaatio]]) = {
    (Platform.currentTime + timeToLive.toMillis, palvelu.get(oid))
  }
}

class RestOrganisaatiopalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/organisaatio-service")(implicit val ec: ExecutionContext) extends Organisaatiopalvelu {
  val logger = LoggerFactory.getLogger(getClass)

  implicit val httpClient = new ApacheHttpClient()()



  override def getAll: Future[Seq[String]] = {
    val url = new URL(serviceUrl + "/rest/organisaatio")
    logger.info("fetching all organizations: [{}]", url)
    GET(url).apply.map(response =>
    if (response.code == HttpResponseCode.Ok) {
      response.bodyAsCaseClass[List[String]].toOption.getOrElse(Seq())
    } else {
      logger.error("call to organisaatio-service [{}] failed: {}", url, response.code)
      throw new RuntimeException("virhe kutsuttaessa organisaatiopalvelua: %s".format(response.code))
    }

    )

  }

  override def get(str: String): Future[Option[Organisaatio]] = {
    val url = new URL(serviceUrl + "/rest/organisaatio/" + str)
    GET(url).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {
        val organisaatio = response.bodyAsCaseClass[Organisaatio].toOption
        organisaatio
      } else {
        logger.error("call to organisaatio-service [{}] failed: {}", url, response.code)
        throw new RuntimeException("virhe kutsuttaessa organisaatiopalvelua: %s".format(response.code))
      }
    })
  }

}
