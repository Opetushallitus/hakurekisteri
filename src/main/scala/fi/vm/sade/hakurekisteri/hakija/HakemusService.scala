package fi.vm.sade.hakurekisteri.hakija

import scala.concurrent.Future
import fi.vm.sade.hakurekisteri.storage.{ResourceActor, Identified, ResourceService}
import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, Query, User}
import java.net.{URL, URLEncoder}
import scala.util.Try
import com.stackmob.newman.dsl._
import scala.Some
import com.stackmob.newman.response.{HttpResponse, HttpResponseCode}
import akka.event.Logging
import com.stackmob.newman.ApacheHttpClient
import fi.vm.sade.hakurekisteri.healthcheck.{Health, Hakemukset}
import akka.actor.ActorRef

trait HakemusService extends ResourceService[FullHakemus, String] with JournaledRepository[FullHakemus, String] {



  def filterField[F](field: Option[F], fieldExctractor: (FullHakemus) => F)(hakemus:FullHakemus) = field match {

    case Some(acceptedValue) =>   acceptedValue == fieldExctractor(hakemus)
    case None => true
  }


  def someField[F](field: Option[F], fieldExctractor: (FullHakemus) => Seq[F])(hakemus:FullHakemus) = field match {

    case Some(acceptedValue) =>   fieldExctractor(hakemus).contains(acceptedValue)
    case None => true
  }


  override val matcher: PartialFunction[Query[FullHakemus], (FullHakemus with Identified[String]) => Boolean] = {

    case HakemusQuery(haku, organisaatio, hakukohdekoodi) =>
      (hakemus) =>
        filterField(haku, _.applicationSystemId)(hakemus)  &&
          someField(
            organisaatio,
            _.answers.
              getOrElse(Map()).
              getOrElse("hakutoiveet", Map()).
              filterKeys((k) => k.contains("Opetuspiste-id-parents")).
              flatMap(_._2.split(",")).toSeq)(hakemus) &&
          someField(
            hakukohdekoodi,
            _.answers.
              getOrElse(Map()).
              getOrElse("hakutoiveet", Map()).
              filterKeys((k) => k.contains("Koulutus-id-aoIdentifier")).values.toSeq)(hakemus)


  }

  override def identify(o: FullHakemus): FullHakemus with Identified[String] = FullHakemus.identify(o)


}

case class HakemusQuery(haku: Option[String], organisaatio: Option[String], hakukohdekoodi: Option[String]) extends Query[FullHakemus]

object HakemusQuery {

  def apply(hq:HakijaQuery):HakemusQuery = HakemusQuery(hq.haku, hq.organisaatio, hq.hakukohdekoodi)

}


class HakemusJournal extends InMemJournal[FullHakemus, String] {


  override def addModification(delta:Delta[FullHakemus, String]): Unit =  {
  }
}


class HakemusActor(serviceAccessUrl:String,  serviceUrl: String = "https://itest-virkailija.oph.ware.fi/haku-app", maxApplications: Int = 2000, user: Option[String], password:Option[String],  override val journal: Journal[FullHakemus, String] = new HakemusJournal()) extends ResourceActor[FullHakemus, String] with HakemusService with HakurekisteriJsonSupport {

  import scala.concurrent.duration._

  implicit val httpClient = new ApacheHttpClient(socketTimeout = 60.seconds.toMillis.toInt)()

  var healthCheck: Option[ActorRef] = None

  val logger = Logging(context.system, this)


  override def receive: Receive = super.receive.orElse({
    case ReloadHaku(haku) => getHakemukset(HakijaQuery(Some(haku), None, None, Hakuehto.Kaikki, None)) map ((hs) => {
      logger.debug(s"found ${hs} applications")
    })
    case Health(actor) => healthCheck = Some(actor)
  })





  def getHakemukset(q: HakijaQuery): Future[Int] = {

    def getUrl(page: Int = 0): URL = {
      new URL(serviceUrl + "/applications/listfull?" + getQueryParams(q, page))
    }
    val user = q.user

    val responseFuture: Future[Option[List[FullHakemus]]] = restRequest[List[FullHakemus]](user, getUrl())

    def getAll(cur: Int)(res: Option[List[FullHakemus]]):Future[Option[Int]] = res match {
      case None                                   => Future.successful(None)
      case Some(l) if l.length < maxApplications  =>
        for (actor <- healthCheck)
          actor ! Hakemukset(cur + l.length)
        handleNew(l)
        Future.successful(Some(cur + l.length))
      case Some(l)                                =>
        for (actor <- healthCheck)
          actor ! Hakemukset(cur + l.length)
        handleNew(l)
        restRequest[List[FullHakemus]](user, getUrl((cur / maxApplications) + 1)).flatMap(getAll(cur + l.length))
    }


    responseFuture.
      flatMap(getAll(0)).
      map(_.getOrElse(0))


  }

  def handleNew(hakemukset: List[FullHakemus]):Unit = {
    hakemukset.foreach(self.tell(_, ActorRef.noSender))

  }


  def restRequest[A <: AnyRef](user: Option[User], url: URL)(implicit mf : Manifest[A]): Future[Option[A]] = {
    getProxyTicket.flatMap((ticket) => {
      logger.debug("calling haku-app [url={}, ticket={}]", url, ticket)

      GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(response => {
        if (response.code == HttpResponseCode.Ok) {
          val hakemusHaku: Option[A] = readBody[A](response)
          logger.debug("got response for url: [{}]", url)

          hakemusHaku
        } else {
          logger.error("call to haku-app [url={}, ticket={}] failed: {}", url, ticket, response.code)

          throw new RuntimeException("virhe kutsuttaessa hakupalvelua: %s".format(response.code))
        }
      })
    })
  }

  def urlencode(s: String): String = URLEncoder.encode(s, "UTF-8")

  def getQueryParams(q: HakijaQuery, page: Int = 0): String = {
    val params: Seq[String] = Seq(
      Some("orgSearchExpanded=true"), Some("checkAllApplications=false"),
      Some(s"start=${page * maxApplications}"), Some(s"rows=$maxApplications"),
      q.haku.map(s => s"asId=${urlencode(s)}"),
      q.organisaatio.map(s => s"lopoid=${urlencode(s)}"),
      q.hakukohdekoodi.map(s => s"aoid=${urlencode(s)}")
    ).flatten

    Try((for(i <- params; p <- List("&", i)) yield p).tail.reduce(_ + _)).getOrElse("")
  }

  def getProxyTicket: Future[String] = (user, password) match {
    case (Some(u), Some(p)) =>
      POST(new URL(s"$serviceAccessUrl/accessTicket")).
        addHeaders("Content-Type" -> "application/x-www-form-urlencoded").
        setBodyString(s"client_id=${URLEncoder.encode(u, "UTF8")}&client_secret=${URLEncoder.encode(p, "UTF8")}&service_url=${URLEncoder.encode(serviceUrl, "UTF8")}").
        apply.map((response) => {
          val st = response.bodyString.trim
          if (TicketValidator.isValidSt(st)) st
          else throw InvalidServiceTicketException(st)
        })
    case _ => Future.successful("")
  }

  def find(q: HakijaQuery): Future[Seq[ListHakemus]] = {
    val url = new URL(serviceUrl + "/applications/list/fullName/asc?" + getQueryParams(q))
    val user = q.user
    restRequest[HakemusHaku](user, url).map(_.map(_.results).getOrElse(Seq()))
  }

  def readBody[A <: AnyRef: Manifest](response: HttpResponse): Option[A] = {
    import org.json4s.jackson.Serialization.read
    val rawResult = Try(read[A](response.bodyString))

    if (rawResult.isFailure) logger.warning("Failed to deserialize", rawResult.failed.get)

    val result = rawResult.toOption
    result
  }


}

case class ReloadHaku(haku:String)
