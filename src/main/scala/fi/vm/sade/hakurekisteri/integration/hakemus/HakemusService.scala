package fi.vm.sade.hakurekisteri.integration.hakemus

import java.net.URLEncoder

import akka.actor.{Actor, Props, ActorRef}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.hakija.{Hakuehto, HakijaQuery}
import fi.vm.sade.hakurekisteri.healthcheck.{RefreshingResource, Hakemukset, Health}
import fi.vm.sade.hakurekisteri.kkhakija.KkHakijaQuery
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, Query}
import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.storage.{InMemQueryingResourceService, Identified, ResourceActor}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}

trait HakemusService extends InMemQueryingResourceService[FullHakemus, String] with JournaledRepository[FullHakemus, String] {

  var hakukohdeIndex: Map[String, Seq[FullHakemus with Identified[String]]] = Option(hakukohdeIndex).getOrElse(Map())
  var hakijaIndex: Map[String, Seq[FullHakemus with Identified[String]]] = Option(hakijaIndex).getOrElse(Map())

  override val emptyQuery: PartialFunction[Query[FullHakemus], Boolean] = {
    case HakemusQuery(None, None, None, None) => true
  }

  def getKohteet(hakemus: FullHakemus with Identified[String]): Option[Set[String]] = {
    for (
      answers <- hakemus.answers;
      toiveet <- answers.hakutoiveet
    ) yield {toiveet.filter {
      case (avain, arvo) if avain.endsWith("Koulutus-id") && !arvo.isEmpty => true
      case _ => false
    }.values.toSet}
  }

  def addNew(hakemus: FullHakemus with Identified[String]) = {
    hakukohdeIndex = Option(hakukohdeIndex).getOrElse(Map())
    hakijaIndex = Option(hakijaIndex).getOrElse(Map())
    for(
      kohde <- getKohteet(hakemus).getOrElse(Set())
    )  hakukohdeIndex = hakukohdeIndex  + (kohde -> (hakemus +: hakukohdeIndex.getOrElse(kohde, Seq())))
    for (
      hakija <- hakemus.personOid
    ) hakijaIndex = hakijaIndex + (hakija -> (hakemus +: hakijaIndex.getOrElse(hakija, Seq())))

  }

  override def index(old: Option[FullHakemus with Identified[String]], current: Option[FullHakemus with Identified[String]]) {
    def removeOld(hakemus: FullHakemus with Identified[String]) = {
      hakukohdeIndex = Option(hakukohdeIndex).getOrElse(Map())
      hakijaIndex = Option(hakijaIndex).getOrElse(Map())
      for (
        kohde <- getKohteet(hakemus).getOrElse(Set())
      ) hakukohdeIndex = hakukohdeIndex.get(kohde).
        map(_.filter((a) => a != hakemus || a.id != hakemus.id)).
        map((ns: Seq[FullHakemus with Identified[String]]) => hakukohdeIndex + (kohde -> ns)).getOrElse(hakukohdeIndex)

      for (
        hakija <- hakemus.personOid
      ) hakijaIndex = hakijaIndex.get(hakija).
        map(_.filter((a) => a != hakemus || a.id != hakemus.id)).
        map((ns: Seq[FullHakemus with Identified[String]]) => hakijaIndex + (hakija -> ns)).getOrElse(hakijaIndex)


    }

    old.foreach(removeOld)
    current.foreach(addNew)
  }

  override val optimize:PartialFunction[Query[FullHakemus], Future[Seq[FullHakemus with Identified[String]]]] = {
    case HakemusQuery(_, None, _, Some(kohde)) =>
      Future.successful(hakukohdeIndex.getOrElse(kohde, Seq()))
    case HakemusQuery(haku, organisaatio, kohdekoodi, Some(kohde)) =>
      val filtered = hakukohdeIndex.getOrElse(kohde, Seq())
      executeQuery(filtered)(HakemusQuery(haku, organisaatio, kohdekoodi, Some(kohde)))
    case HenkiloHakijaQuery(henkilo) => Future.successful(hakijaIndex.getOrElse(henkilo, Seq()))

  }

  def filterField[F](field: Option[F], fieldExctractor: (FullHakemus) => F)(hakemus:FullHakemus) = field match {
    case Some(acceptedValue) =>   acceptedValue == fieldExctractor(hakemus)
    case None => true
  }

  def someField[F](field: Option[F], fieldExctractor: (FullHakemus) => Seq[F])(hakemus:FullHakemus) = field match {
    case Some(acceptedValue) =>   fieldExctractor(hakemus).contains(acceptedValue)
    case None => true
  }

  override val matcher: PartialFunction[Query[FullHakemus], (FullHakemus with Identified[String]) => Boolean] = {
    case HakemusQuery(haku, organisaatio, hakukohdekoodi, hakukohde) =>
      (hakemus) =>
        filterField(haku, _.applicationSystemId)(hakemus) &&
          someField(
            organisaatio,
            _.answers.flatMap(_.hakutoiveet).getOrElse(Map()).
              filterKeys((k) => k.contains("Opetuspiste-id-parents")).
              flatMap(_._2.split(",")).toSeq)(hakemus) &&
          someField(
            hakukohdekoodi,
            _.answers.flatMap(_.hakutoiveet).getOrElse(Map()).
              filterKeys((k) => k.contains("Koulutus-id-aoIdentifier")).values.toSeq)(hakemus) &&
          someField(
            hakukohde,
            _.answers.flatMap(_.hakutoiveet).getOrElse(Map()).
              filterKeys((k) => k.contains("Koulutus-id")).values.toSeq)(hakemus)

    case HenkiloHakijaQuery(henkilo) =>
      (hakemus) => hakemus.personOid.exists(_ == henkilo)
  }

}

case class HakemusQuery(haku: Option[String], organisaatio: Option[String], hakukohdekoodi: Option[String], hakukohde: Option[String] = None) extends Query[FullHakemus]

case class HenkiloHakijaQuery(henkilo: String) extends Query[FullHakemus]

object HakemusQuery {
  import org.scalatra.util.RicherString._

  def apply(params: Map[String,String]): HakemusQuery = HakemusQuery(
    haku = params.get("haku").flatMap(_.blankOption),
    organisaatio = params.get("organisaatio").flatMap(_.blankOption),
    None,
    hakukohde = params.get("hakukohde").flatMap(_.blankOption)
    )
  def apply(hq: HakijaQuery): HakemusQuery = HakemusQuery(hq.haku, hq.organisaatio, hq.hakukohdekoodi)
  def apply(hq: KkHakijaQuery): HakemusQuery = HakemusQuery(hq.haku, hq.organisaatio, None, hq.hakukohde)
}

case class Trigger(newApplicant: (FullHakemus) => Unit)

object Trigger {
  def apply(oidHetu: (String, String) => Unit): Trigger = Trigger(_ match {
    case FullHakemus(_, Some(personOid), _, Some(answers), _, _) =>
      for (
        henkilo <- answers.henkilotiedot;
        hetu <- henkilo.Henkilotunnus
      ) oidHetu(personOid, hetu)

    case _ =>
  })
}

class HakemusJournal extends InMemJournal[FullHakemus, String] {
  override def addModification(delta:Delta[FullHakemus, String]): Unit = {
  }
}

class HakemusActor(hakemusClient: VirkailijaRestClient,
                   maxApplications: Int = 2000,
                   override val journal: Journal[FullHakemus, String] = new HakemusJournal()
                   ) extends ResourceActor[FullHakemus, String] with HakemusService with HakurekisteriJsonSupport {
  var healthCheck: Option[ActorRef] = None
  override val logger = Logging(context.system, this)

  var hakijaTrigger:Seq[ActorRef] = Seq()

  override def receive: Receive = super.receive.orElse({
    case ReloadHaku(haku) => getHakemukset(HakijaQuery(haku = Some(haku), organisaatio = None, hakukohdekoodi = None, hakuehto = Hakuehto.Kaikki, user = None)) onComplete {
      case Success(hs) =>  logger.debug(s"found $hs applications")
      case Failure(ex) => logger.error(ex, s"failed fetching Hakemukset for $haku")
    }
    case Health(actor) => healthCheck = Some(actor)
    case Trigger(trig) => hakijaTrigger = context.actorOf(Props(new HakijaTrigger(trig))) +: hakijaTrigger
  })

  def getHakemukset(q: HakijaQuery): Future[Int] = {
    def getUri(page: Int = 0): String = {
      "/applications/listfull?" + getQueryParams(q, page)
    }

    val responseFuture: Future[List[FullHakemus]] = restRequest[List[FullHakemus]](getUri())

    def getAll(cur: Int)(res: List[FullHakemus]): Future[Option[Int]] = res match {
      case l if l.isEmpty => Future.successful(None)
      case l if l.length < maxApplications =>
        for (actor <- healthCheck)
          actor ! Hakemukset(q.haku.getOrElse("unknown"), RefreshingResource(cur + l.length))
        handleNew(l)
        Future.successful(Some(cur + l.length))
      case l =>
        for (actor <- healthCheck)
          actor ! Hakemukset(q.haku.getOrElse("unknown"), RefreshingResource(cur + l.length, reloading = true))
        handleNew(l)
        log.debug(s"requesting $maxApplications new Hakemukset for ${q.haku.getOrElse("not specified")} current count $cur")
        restRequest[List[FullHakemus]](getUri((cur / maxApplications) + 1)).flatMap(getAll(cur + l.length))
    }

    responseFuture.
      flatMap(getAll(0)).
      map(_.getOrElse(0))
  }

  def handleNew(hakemukset: List[FullHakemus]) {

    for (
      hakemus: FullHakemus <- hakemukset
    ) {
      self.!(hakemus)(ActorRef.noSender)
      hakijaTrigger foreach (_ ! hakemus)
    }


  }

  def restRequest[A <: AnyRef](uri: String)(implicit mf : Manifest[A]): Future[A] = hakemusClient.readObject[A](uri, 200)

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
}

case class ReloadHaku(haku: String)

class HakijaTrigger(newApplicant: (FullHakemus) => Unit) extends Actor {

  override def receive: Actor.Receive = {
    case f:FullHakemus => newApplicant(f)
  }
}

case class HakemusConfig(serviceConf: ServiceConfig, maxApplications: Int)



