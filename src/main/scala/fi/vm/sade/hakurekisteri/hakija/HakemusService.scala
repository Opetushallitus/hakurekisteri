package fi.vm.sade.hakurekisteri.hakija

import akka.actor.{Cancellable, Actor}
import scala.concurrent.{ExecutionContext, Future}
import scala.compat.Platform
import fi.vm.sade.hakurekisteri.storage.{ResourceActor, Identified, ResourceService}
import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.rest.support.{User, Query}
import java.util.UUID
import fi.vm.sade.hakurekisteri.hakija.HakemusQuery
import scala.Some
import fi.vm.sade.hakurekisteri.hakija.FullHakemus
import java.net.{URL, URLEncoder}
import scala.util.Try
import com.stackmob.newman.dsl._
import fi.vm.sade.hakurekisteri.hakija.HakemusQuery
import fi.vm.sade.hakurekisteri.hakija.HakemusHaku
import scala.Some
import fi.vm.sade.hakurekisteri.hakija.ListHakemus
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import com.stackmob.newman.response.HttpResponseCode
import akka.event.slf4j.Logger
import akka.event.Logging
import com.stackmob.newman.ApacheHttpClient

trait HakemusService extends ResourceService[FullHakemus] with JournaledRepository[FullHakemus] {


  override def loadJournal(time: Option[Long]): Unit = {
    snapShot = Map()
    reverseSnapShot = Map()
    super.loadJournal()

  }

  def filterField[F](field: Option[F], fieldExctractor: (FullHakemus) => F)(hakemus:FullHakemus) = field match {

    case Some(acceptedValue) =>   acceptedValue == fieldExctractor(hakemus)
    case None => true
  }


  def someField[F](field: Option[F], fieldExctractor: (FullHakemus) => Seq[F])(hakemus:FullHakemus) = field match {

    case Some(acceptedValue) =>   fieldExctractor(hakemus).contains(acceptedValue)
    case None => true
  }


  override val matcher: PartialFunction[Query[FullHakemus], (FullHakemus with Identified) => Boolean] = {

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

  override def identify(o: FullHakemus): FullHakemus with Identified = FullHakemus.identify(o)


}

case class HakemusQuery(haku: Option[String], organisaatio: Option[String], hakukohdekoodi: Option[String]) extends Query[FullHakemus]

object HakemusQuery {

  def apply(hq:HakijaQuery):HakemusQuery = HakemusQuery(hq.haku, hq.organisaatio, hq.hakukohdekoodi)

}

class HakemusJournal extends InMemJournal[FullHakemus] {
  def change(hakemukset: Seq[FullHakemus]) {
    deltas = hakemukset.map(FullHakemus.identify(_)).map(Updated[FullHakemus](_))
  }

}


class HakemusActor(serviceAccessUrl:String,  serviceUrl: String = "https://itest-virkailija.oph.ware.fi/haku-app", maxApplications: Int = 2000, user: Option[String], password:Option[String], override val journal: HakemusJournal = new HakemusJournal()) extends ResourceActor[FullHakemus] with HakemusService  {

  import scala.concurrent.duration._

  implicit val httpClient = new ApacheHttpClient(socketTimeout = 60.seconds.toMillis.toInt)()

  def change(hakemukset: Seq[FullHakemus]) = journal.change(hakemukset)






  val logger = Logging(context.system, this)

  import akka.pattern._

  override def receive: Receive = super.receive.orElse({
    case ReloadHaku(haku) => getHakemukset(HakijaQuery(Some(haku), None, None, Hakuehto.Kaikki, None)) map ((hs) => {
      logger.debug(s"found ${hs.length} applications")
      NewHakemukset(hs)}) pipeTo self
    case NewHakemukset(hakemukset) =>
      logger.debug(s"reloaded ${hakemukset.length} applications")
      change(hakemukset)

      loadJournal()
      logger.debug(s"cache now has ${listAll().length} applications")
  })

  case class NewHakemukset(hakemukset:Seq[FullHakemus])

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
        apply.map((response) => response.bodyString.trim)
    case _ => Future.successful("")
  }

  def find(q: HakijaQuery): Future[Seq[ListHakemus]] = {
    val url = new URL(serviceUrl + "/applications/list/fullName/asc?" + getQueryParams(q))
    val user = q.user
    restRequest[HakemusHaku](user, url).map(_.map(_.results).getOrElse(Seq()))
  }

  def restRequest[A <: AnyRef](user: Option[User], url: URL)(implicit mf : Manifest[A]): Future[Option[A]] = {
    getProxyTicket.flatMap((ticket) => {
      logger.debug("calling haku-app [url={}, ticket={}]", url, ticket)

      GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(response => {
        if (response.code == HttpResponseCode.Ok) {
          val hakemusHaku = response.bodyAsCaseClass[A].toOption
          logger.debug("got response for url: [{}]", url)

          hakemusHaku
        } else {
          logger.error("call to haku-app [url={}, ticket={}] failed: {}", url, ticket, response.code)

          throw new RuntimeException("virhe kutsuttaessa hakupalvelua: %s".format(response.code))
        }
      })
    })
  }

  def getHakemukset(q: HakijaQuery): Future[Seq[FullHakemus]] = {

    def getUrl(page: Int = 0): URL = {
      new URL(serviceUrl + "/applications/listfull?" + getQueryParams(q, page))
    }
    val user = q.user

    val responseFuture: Future[Option[List[FullHakemus]]] = restRequest[List[FullHakemus]](user, getUrl())

    def getAll(cur: List[FullHakemus])(res: Option[List[FullHakemus]]):Future[Option[List[FullHakemus]]] = res match {
      case None                                   => Future.successful(None)
      case Some(l) if l.length < maxApplications  => Future.successful(Some(cur ++ l))
      case Some(l)                                => restRequest[List[FullHakemus]](user, getUrl((cur.length / maxApplications) + 1)).flatMap(getAll(cur ++ l))
    }


    responseFuture.
      flatMap(getAll(List())).
      map(_.getOrElse(Seq()))


  }



}

case class ReloadHaku(haku:String)
