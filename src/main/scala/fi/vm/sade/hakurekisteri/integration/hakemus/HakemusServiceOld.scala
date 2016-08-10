package fi.vm.sade.hakurekisteri.integration.hakemus

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{Cancellable, Actor, ActorRef, Props}
import akka.event.Logging
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.hakija.{HakijaQuery, Hakuehto}
import fi.vm.sade.hakurekisteri.healthcheck.{Hakemukset, Health, RefreshingResource}
import fi.vm.sade.hakurekisteri.integration.haku.Haku
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, Query}
import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.storage.{Identified, InMemQueryingResourceService, ResourceActor}
import fi.vm.sade.hakurekisteri.tools.DurationHelper
import fi.vm.sade.hakurekisteri.tools.DurationHelper.atTime
import org.joda.time.{LocalDateTime, LocalTime}

import scala.compat.Platform
import scala.concurrent.Future
import scala.util.Try

trait HakemusServiceOld extends InMemQueryingResourceService[FullHakemus, String] with JournaledRepository[FullHakemus, String] {
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
    case HakemusQuery(Some(haku), None, None, None) =>
      Future { hakuIndex.getOrElse(haku, Seq()) }

    case HakemusQuery(Some(haku), organisaatio, kohdekoodi, None) =>
      Future {
        hakuIndex.getOrElse(haku, Seq())
      } flatMap(filtered => executeQuery(filtered)(HakemusQuery(None, organisaatio, kohdekoodi, None)))

    case HakemusQuery(None, None, None, Some(kohde)) =>
      Future { hakukohdeIndex.getOrElse(kohde, Seq()) }

    case HakemusQuery(haku, organisaatio, kohdekoodi, Some(kohde)) =>
      Future {
        hakukohdeIndex.getOrElse(kohde, Seq())
      } flatMap(filtered => executeQuery(filtered)(HakemusQuery(haku, organisaatio, kohdekoodi, None)))

    case HenkiloHakijaQuery(henkilo) =>
      Future { hakijaIndex.getOrElse(henkilo, Seq()) }

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

case class RefreshingDone(startTime: Option[Long])

class HakemusActor(hakemusClient: VirkailijaRestClient,
                   pageSize: Int = 2000,
                   nextPageDelay: Int = 10000,
                   override val journal: Journal[FullHakemus, String] = new HakemusJournal()
                   ) extends ResourceActor[FullHakemus, String] with HakemusServiceOld with HakurekisteriJsonSupport {
  var healthCheck: Option[ActorRef] = None
  override val logger = Logging(context.system, this)
  var hakijaTrigger: Seq[ActorRef] = Seq()
  var reloading = false
  var initialLoadingDone = false
  var hakuCursor: Option[String] = None
  var aktiivisetHaut: Set[Haku] = Set()
  val cursorFormat = "yyyyMMddHHmm"
  val resetCursor = context.system.scheduler.schedule(atTime(hour = 4, minute = 15), 1.day, self, ResetCursor)
  var retryResetCursor: Option[Cancellable] = None
  var retryRefresh: Option[Cancellable] = None

  override def postStop(): Unit = {
    resetCursor.cancel()
    retryResetCursor.foreach(_.cancel())
    retryRefresh.foreach(_.cancel())
    super.postStop()
  }

  object ResetCursor

  private def initialBlocking: Receive = {
    case q: Query[_] if !initialLoadingDone =>
      Future.failed(HakemuksetNotYetLoadedException()) pipeTo sender
  }
  
  private def earliestHakuAlku: Long = aktiivisetHaut.toList.sortBy(_.aika.alku).headOption.map(_.aika.alku.getMillis).getOrElse(0)

  private def toCursor(time: Option[Long] = None): String = time match {
    case Some(t) => new SimpleDateFormat(cursorFormat).format(new Date(t - (5 * 60 * 1000)))
    case None => new SimpleDateFormat(cursorFormat).format(new Date(earliestHakuAlku - (5 * 60 * 1000)))
  }

  override def receive: Receive = initialBlocking orElse super.receive orElse {
    case ResetCursor if !reloading =>
      hakuCursor = None

    case ResetCursor if reloading =>
      retryResetCursor.foreach(_.cancel())
      retryResetCursor = Some(context.system.scheduler.scheduleOnce(1.minute, self, ResetCursor))

    case AktiivisetHaut(haut) =>
      aktiivisetHaut = haut
      self ! RefreshHakemukset

    case RefreshHakemukset if !reloading =>
      val startTime = Platform.currentTime
      val cursor: String = hakuCursor.getOrElse(toCursor())
      reloading = true
      logger.info(s"fetching hakemukset since $cursor")
      getHakemukset(HakijaQuery(haku = None, organisaatio = None, hakukohdekoodi = None, hakuehto = Hakuehto.Kaikki, user = None, version = 1), cursor).map(i => {
        logger.debug(s"found $i applications")
        RefreshingDone(Some(startTime))
      }).recover {
        case t: Throwable =>
          logger.error(t, s"failed fetching hakemukset, retrying soon")
          RefreshingDone(None)
      } pipeTo self

    case RefreshingDone(startTime) =>
      reloading = false
      if (startTime.isDefined) {
        hakuCursor = Some(toCursor(startTime))
        if (!initialLoadingDone) {
          initialLoadingDone = true
          log.info("initial loading done")
        }
      } else {
        retryRefresh.foreach(_.cancel())
        retryRefresh = Some(context.system.scheduler.scheduleOnce(1.minute, self, RefreshHakemukset))
      }


    case Health(actor) => healthCheck = Some(actor)

    case Trigger(trig) => hakijaTrigger = context.actorOf(Props(new HakijaTrigger(trig))) +: hakijaTrigger
  }

  private def getHakemukset(q: HakijaQuery, cursor: String): Future[Int] = {
    val hakuOidit = aktiivisetHaut.map(_.oid)

    val firstPage: Future[List[FullHakemus]] = restRequest[List[FullHakemus]]("haku-app.listfull", getQueryParams(q, 0, cursor))

    def throttle = Future {
      if (initialLoadingDone) Thread.sleep(nextPageDelay)
    }

    def loadNextPage(cur: Int, hakemukset: List[FullHakemus])(u: Unit): Future[Option[Int]] =
      restRequest[List[FullHakemus]]("haku-app.listfull", getQueryParams(q, (cur / pageSize) + 1, cursor)).flatMap(getAll(cur + hakemukset.length))

    def getAll(cur: Int)(pageResult: List[FullHakemus]): Future[Option[Int]] = pageResult match {
      case hakemukset if hakemukset.isEmpty => Future.successful(None)
      case hakemukset if hakemukset.length < pageSize =>
        for (actor <- healthCheck)
          actor ! Hakemukset(q.haku.getOrElse("all"), RefreshingResource(cur + hakemukset.length))
        save(hakemukset, hakuOidit)
        Future.successful(Some(cur + hakemukset.length))
      case hakemukset =>
        for (actor <- healthCheck)
          actor ! Hakemukset(q.haku.getOrElse("all"), RefreshingResource(cur + hakemukset.length, reloading = true))
        save(hakemukset, hakuOidit)
        throttle.
          flatMap(loadNextPage(cur, hakemukset))
    }

    firstPage.
      flatMap(getAll(0)).
      map(_.getOrElse(0))
  }

  private def isCurrent(haut: Set[String])(hakemus: FullHakemus): Boolean = haut.contains(hakemus.applicationSystemId)

  private def save(hakemukset: List[FullHakemus], hakuOids: Set[String]) {
    hakemukset.withFilter(isCurrent(hakuOids)).foreach(hakemus => {
      self.!(hakemus)(ActorRef.noSender)
      hakijaTrigger foreach (_.!(hakemus)(ActorRef.noSender))
    })
  }

  private def restRequest[A <: AnyRef](uri: String, args: AnyRef*)(implicit mf: Manifest[A]): Future[A] =
    hakemusClient.readObject[A](uri, args:_*)( 200, 2)

  private def urlencode(s: String): String = URLEncoder.encode(s, "UTF-8")

  private def getQueryParams(q: HakijaQuery, page: Int, cursor: String) = {
    Map("updatedAfter" -> cursor,
    "start" -> page * pageSize,
    "rows" -> pageSize,
    "asId" -> q.haku,
    "lopoid" -> q.organisaatio,
    "aoid" -> q.hakukohdekoodi
    )
  }
}

class MockHakemusActor() extends HakemusActor(hakemusClient = null) {
  override def receive: Receive = {
    case HenkiloHakijaQuery(oid) =>
      sender ! Seq(new FullHakemus(oid = oid, personOid = Some(oid), "1.2.3", Some(HakemusAnswers(Some(HakemusHenkilotiedot(Henkilotunnus = Some(oid))))), Some("ACTIVE"), Seq()))

    case q: HakemusQuery =>
      sender ! Seq()

    case msg =>
      log.warning(s"not implemented receive($msg)")
  }
}

object RefreshHakemukset

case class AktiivisetHaut(haut: Set[Haku])

case class HakemuksetNotYetLoadedException() extends Exception("hakemukset not yet loaded")

class HakijaTrigger(newApplicant: (FullHakemus) => Unit) extends Actor {
  override def receive: Actor.Receive = {
    case f:FullHakemus => newApplicant(f)
  }
}

case class HakemusConfig(serviceConf: ServiceConfig, maxApplications: Int)



