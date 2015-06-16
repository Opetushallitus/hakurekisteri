package fi.vm.sade.hakurekisteri.integration.hakemus

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.{Actor, ActorRef, Props}
import akka.event.Logging
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.hakija.{HakijaQuery, Hakuehto}
import fi.vm.sade.hakurekisteri.healthcheck.{Hakemukset, Health, RefreshingResource}
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, Query}
import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.storage.{Identified, InMemQueryingResourceService, ResourceActor}

import scala.compat.Platform
import scala.concurrent.Future
import scala.util.Try

trait HakemusService extends InMemQueryingResourceService[FullHakemus, String] with JournaledRepository[FullHakemus, String] {
  var hakukohdeIndex: Map[String, Seq[FullHakemus with Identified[String]]] = Option(hakukohdeIndex).getOrElse(Map())
  var hakijaIndex: Map[String, Seq[FullHakemus with Identified[String]]] = Option(hakijaIndex).getOrElse(Map())
  var hakuIndex: Map[String, Seq[FullHakemus with Identified[String]]] = Option(hakuIndex).getOrElse(Map())

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
    hakuIndex = Option(hakuIndex).getOrElse(Map())

    for (
      kohde <- getKohteet(hakemus).getOrElse(Set())
    ) hakukohdeIndex = hakukohdeIndex  + (kohde -> (hakemus +: hakukohdeIndex.getOrElse(kohde, Seq())))

    for (
      hakija <- hakemus.personOid
    ) hakijaIndex = hakijaIndex + (hakija -> (hakemus +: hakijaIndex.getOrElse(hakija, Seq())))

    val haku = hakemus.applicationSystemId
    hakuIndex = hakuIndex + (haku -> (hakemus +: hakuIndex.getOrElse(haku, Seq())))
  }

  override def index(old: Option[FullHakemus with Identified[String]], current: Option[FullHakemus with Identified[String]]) {
    def removeOld(hakemus: FullHakemus with Identified[String]) = {
      hakukohdeIndex = Option(hakukohdeIndex).getOrElse(Map())
      hakijaIndex = Option(hakijaIndex).getOrElse(Map())
      hakuIndex = Option(hakuIndex).getOrElse(Map())

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

      val haku = hakemus.applicationSystemId
      for (
        indeksoidut <- hakuIndex.get(haku)
      ) hakuIndex = hakuIndex + (haku -> indeksoidut.filter(_.id != hakemus.id))
    }

    old.foreach(removeOld)
    current.foreach(addNew)
  }

  override val optimize: PartialFunction[Query[FullHakemus], Future[Seq[FullHakemus with Identified[String]]]] = {
    case HakemusQuery(Some(haku), None, None, None) => Future {
      hakuIndex.getOrElse(haku, Seq())
    }

    case HakemusQuery(Some(haku), organisaatio, kohdekoodi, kohde) =>
      val filtered = hakuIndex.getOrElse(haku, Seq())
      executeQuery(filtered)(HakemusQuery(Some(haku), organisaatio, kohdekoodi, kohde))

    case HakemusQuery(None, None, None, Some(kohde)) => Future {
      hakukohdeIndex.getOrElse(kohde, Seq())
    }

    case HakemusQuery(haku, organisaatio, kohdekoodi, Some(kohde)) =>
      val filtered = hakukohdeIndex.getOrElse(kohde, Seq())
      executeQuery(filtered)(HakemusQuery(haku, organisaatio, kohdekoodi, Some(kohde)))

    case HenkiloHakijaQuery(henkilo) => Future {
      hakijaIndex.getOrElse(henkilo, Seq())
    }

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
      (hakemus) => hakemus.personOid.contains(henkilo)
  }
}

case class HakemusQuery(haku: Option[String], organisaatio: Option[String], hakukohdekoodi: Option[String], hakukohde: Option[String] = None) extends Query[FullHakemus]

case class HenkiloHakijaQuery(henkilo: String) extends Query[FullHakemus]

object HakemusQuery {

  def apply(hq: HakijaQuery): HakemusQuery = HakemusQuery(hq.haku, hq.organisaatio, hq.hakukohdekoodi)
}

case class Trigger(f: (FullHakemus) => Unit)

object Trigger {
  def apply(f: (String, String, String) => Unit): Trigger = Trigger(_ match {
    case FullHakemus(_, Some(personOid), hakuOid, Some(answers), _, _) =>
      for (
        henkilo <- answers.henkilotiedot;
        hetu <- henkilo.Henkilotunnus
      ) f(personOid, hetu, hakuOid)

    case _ =>
  })
}

class HakemusJournal extends InMemJournal[FullHakemus, String] {
  override def addModification(delta:Delta[FullHakemus, String]): Unit = {
  }
}

import scala.concurrent.duration._

case class ReloadingDone(haku: String, startTime: Option[Long])

class HakemusActor(hakemusClient: VirkailijaRestClient,
                   maxApplications: Int = 2000,
                   override val journal: Journal[FullHakemus, String] = new HakemusJournal()
                   ) extends ResourceActor[FullHakemus, String] with HakemusService with HakurekisteriJsonSupport {
  var healthCheck: Option[ActorRef] = None
  override val logger = Logging(context.system, this)
  var hakijaTrigger: Seq[ActorRef] = Seq()
  var reloading = false
  var initialLoadingDone = false
  var hakuCursors: Map[String, String] = Map()
  var reloadRequests: Set[ReloadHaku] = Set()
  val cursorFormat = "yyyyMMddHHmm"
  val resetCursors = context.system.scheduler.schedule(7.days, 7.days, self, ResetCursors)

  override def postStop(): Unit = {
    resetCursors.cancel()
    super.postStop()
  }

  object ResetCursors
  
  private def initialBlocking: Receive = {
    case q: Query[_] if !initialLoadingDone =>
      Future.failed(HakemuksetNotYetLoadedException()) pipeTo sender
  }

  private def nextRequest() {
    if (reloadRequests.nonEmpty) {
      val r = reloadRequests.head
      reloadRequests = reloadRequests.filterNot(_ == r)
      self ! r
    }
  }

  override def receive: Receive = initialBlocking orElse super.receive orElse {
    case ResetCursors if !reloading =>
      hakuCursors = Map()

    case ResetCursors if reloading =>
      context.system.scheduler.scheduleOnce(1.minute, self, ResetCursors)

    case BatchReload(haut) =>
      reloadRequests = reloadRequests ++ haut
      if (!reloading)
        nextRequest()
      
    case r: ReloadHaku if reloading =>
      reloadRequests = reloadRequests + r
      
    case ReloadHaku(haku) if !reloading =>
      val startTime = Platform.currentTime
      val cursor: Option[String] = hakuCursors.get(haku)
      reloading = true
      logger.debug(s"fetching hakemukset for haku $haku")
      getHakemukset(HakijaQuery(haku = Some(haku), organisaatio = None, hakukohdekoodi = None, hakuehto = Hakuehto.Kaikki, user = None), cursor).map(i => {
        logger.debug(s"found $i applications in $haku")
        ReloadingDone(haku, Some(startTime))
      }).recover {
        case t: Throwable =>
          logger.error(t, s"failed fetching Hakemukset for $haku, retrying soon")
          self ! ReloadHaku(haku)
          ReloadingDone(haku, None)
      } pipeTo self

    case ReloadingDone(haku, startTime) =>
      reloading = false
      if (startTime.isDefined)
        hakuCursors = hakuCursors + (haku -> new SimpleDateFormat(cursorFormat).format(new Date(startTime.get - (5 * 60 * 1000))))
      if (reloadRequests.isEmpty && !initialLoadingDone) {
        initialLoadingDone = true
        log.info("initial loading done")
      }
      nextRequest()

    case Health(actor) => healthCheck = Some(actor)

    case Trigger(trig) => hakijaTrigger = context.actorOf(Props(new HakijaTrigger(trig))) +: hakijaTrigger
  }

  def getHakemukset(q: HakijaQuery, cursor: Option[String]): Future[Int] = {
    def getUri(page: Int = 0): String = {
      "/applications/listfull?" + getQueryParams(q, page, cursor)
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

  def waitABit = Future { Thread.sleep(5) }

  def handleNew(hakemukset: List[FullHakemus]) {
    for (
      hakemus: FullHakemus <- hakemukset
    ) {
      waitABit.foreach(u => {
        self.!(hakemus)(ActorRef.noSender)
        hakijaTrigger foreach (_ ! hakemus)
      })
    }
  }

  def restRequest[A <: AnyRef](uri: String)(implicit mf: Manifest[A]): Future[A] = hakemusClient.readObject[A](uri, 200, 2)

  def urlencode(s: String): String = URLEncoder.encode(s, "UTF-8")

  def getQueryParams(q: HakijaQuery, page: Int, cursor: Option[String]): String = {
    val params: Seq[String] = Seq(
      cursor.map(c => s"updatedAfter=$c"),
      Some(s"start=${page * maxApplications}"),
      Some(s"rows=$maxApplications"),
      q.haku.map(s => s"asId=${urlencode(s)}"),
      q.organisaatio.map(s => s"lopoid=${urlencode(s)}"),
      q.hakukohdekoodi.map(s => s"aoid=${urlencode(s)}")
    ).flatten

    Try((for(i <- params; p <- List("&", i)) yield p).tail.reduce(_ + _)).getOrElse("")
  }
}

class MockHakemusActor() extends HakemusActor(hakemusClient = null) {
  override def receive: Receive = {
    case HenkiloHakijaQuery(oid) => {
      sender ! Seq(new FullHakemus(oid = oid, personOid = Some(oid), "1.2.3", Some(HakemusAnswers(Some(HakemusHenkilotiedot(Henkilotunnus = Some(oid))))), Some("ACTIVE"), Seq()))
    }

    case msg =>
      log.warning(s"not implemented receive(${msg})")
  }
}

case class ReloadHaku(haku: String)

case class BatchReload(haut: Set[ReloadHaku])

case class HakemuksetNotYetLoadedException() extends Exception("hakemukset not yet loaded")

class HakijaTrigger(newApplicant: (FullHakemus) => Unit) extends Actor {
  override def receive: Actor.Receive = {
    case f:FullHakemus => newApplicant(f)
  }
}

case class HakemusConfig(serviceConf: ServiceConfig, maxApplications: Int)



