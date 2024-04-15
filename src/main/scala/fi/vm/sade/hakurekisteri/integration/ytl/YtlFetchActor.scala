package fi.vm.sade.hakurekisteri.integration.ytl

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.hakemus.{HetuPersonOid, IHakemusService}
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  Henkilo,
  IOppijaNumeroRekisteri,
  PersonOidsWithAliases
}
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import scalaz.concurrent.Task
import support.TypedActorRef

import java.time.LocalDate
import scala.concurrent.duration._
import java.util.{Date, UUID}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class YtlSyncHaku(hakuOid: String, tunniste: String)

case class YtlSyncAllHaut(tunniste: String)
case class YtlSyncAllHautNightly(tunniste: String)
case class YtlSyncSingle(personOid: String, tunniste: String)
case class ActiveKkHakuOids(hakuOids: Set[String])
case class YtlFetchActorRef(actor: ActorRef) extends TypedActorRef

class YtlFetchActor(
  properties: OphProperties,
  ytlHttpClient: YtlHttpFetch,
  hakemusService: IHakemusService,
  oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
  ytlKokelasPersister: KokelasPersister,
  config: Config
) extends Actor
    with ActorLogging {

  val activeKKHakuOids = new AtomicReference[Set[String]](Set.empty)

  //val lastSyncStart = new AtomicReference[Option[LocalDate]](None)
  val lastSyncStart = new AtomicReference[Long](0)
  val minIntervalBetween = 1000 * 60 * 60 * 22 //At least 22 hours between nightly syncs

  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

  def setAktiivisetKKHaut(hakuOids: Set[String]): Unit = activeKKHakuOids.set(hakuOids)
  override def receive: Receive = {
    case ah: YtlSyncAllHautNightly =>
      val now = System.currentTimeMillis()
      val tunniste = ah.tunniste + "_" + now
      val lss = lastSyncStart.get()
      val timeToStartNewSync = (lss + minIntervalBetween) < now
      if (timeToStartNewSync) {
        log.info(s"Starting nightly sync for all hakus. Previous run was $lss")
        lastSyncStart.set(now)
        val resultF = syncAllOneHakuAtATime(tunniste)
        resultF.onComplete {
          case Success(_) =>
            log.info(s"($tunniste) Nightly sync for all hakus success!")
          case Failure(t) =>
            log.error(t, s"($tunniste) Nightly sync for all hakus failed...")
        }
      } else {
        log.warning(s"Not starting nightly sync for all hakus as the previous run was on $lss")
      }
    case ah: YtlSyncAllHaut =>
      val tunniste = ah.tunniste
      val resultF = syncAllOneHakuAtATime(tunniste)
      resultF.onComplete {
        case Success(_) =>
          log.info(s"($tunniste) Manual sync for all hakus success!")
        case Failure(t) =>
          log.error(t, s"($tunniste) Manual cync for all hakus failed...")
      }
      sender ! tunniste
    case s: YtlSyncHaku =>
      val tunniste = s.tunniste
      val resultF = fetchAndHandleHakemuksetForSingleHakuF(hakuOid = s.hakuOid, s.tunniste)
      resultF.onComplete {
        case Success(_) =>
          log.info(s"($tunniste) Manual sync for haku ${s.hakuOid} success!")
        case Failure(t) =>
          log.error(t, s"($tunniste) Manual cync for haku ${s.hakuOid} failed...")
      }
      log.info(s"Ytl-sync käynnistetty haulle ${s.hakuOid} tunnisteella $tunniste")
      resultF pipeTo sender
    case s: YtlSyncSingle =>
      val tunniste = s.tunniste
      val resultF = syncSingle(s.personOid)
      resultF.onComplete {
        case Success(_) =>
          log.info(s"($tunniste) Manual sync for person ${s.personOid} success!")
        case Failure(t) =>
          log.error(t, s"($tunniste) Manual cync for person ${s.personOid} failed...")
      }
      log.info(s"Ytl-sync käynnistetty haulle ${s.personOid} tunnisteella $tunniste")
      resultF pipeTo sender
    case a: ActiveKkHakuOids =>
      setAktiivisetKKHaut(a.hakuOids)
      log.info(s"Asetettiin ${a.hakuOids.size} aktiivista ytl-hakua (${activeKKHakuOids.get()})")
      sender ! "ok"
  }

  case class YtlFetchStatus(hasErrors: Boolean, hasEnded: Boolean, tunniste: String)

  def syncAllOneHakuAtATime(
    tunniste: String
  ): Future[Any] = {

    val hakuOidsRaw = activeKKHakuOids.get()
    val hakuOids = hakuOidsRaw.filter(_.length <= 35) //Only ever process kouta-hakus
    val groupUuid = tunniste

    log.info(
      s"($groupUuid) Starting sync all one haku at a time for ${hakuOids.size} kouta-hakus!"
    )
    val results = hakuOids
      .foldLeft(Future.successful(List[(String, Option[Throwable])]())) {
        case (accResults: Future[List[(String, Option[Throwable])]], hakuOid) =>
          accResults.flatMap(rs => {
            try {
              val resultForSingleHaku: Future[Option[Throwable]] =
                fetchAndHandleHakemuksetForSingleHakuF(hakuOid, groupUuid)
                  .recoverWith { case t: Throwable =>
                    log.error(t, s"($groupUuid) Handling hakemukset failed for haku $hakuOid:")
                    Future.successful(Some(t))
                  }
              resultForSingleHaku.map(errorOpt => {
                log.info(
                  s"($groupUuid) Result for single haku $hakuOid, error: ${errorOpt.map(_.getMessage)}"
                )
                (hakuOid, errorOpt) :: rs
              })
            } catch {
              case t: Throwable =>
                log.error(t, s"($groupUuid) Jotain meni vikaan haun $hakuOid käsittelyssä")
                Future.successful(Some(t)).map(errorOpt => (hakuOid, errorOpt) :: rs)
            }
          })
      }

    results.onComplete {
      case Success(res: Seq[(String, Option[Throwable])]) =>
        val failed = res.filter(r => r._2.isDefined)
        val failedHakuOids = failed.map(_._1)
        failed.foreach(f => {
          log.error(f._2.get, s"($groupUuid) YTL Sync failed for haku ${f._1}:")
        })
        log.info(
          s"($groupUuid) Sync all one haku at a time finished. Failed hakus: $failedHakuOids."
        )
      //AtomicStatus.updateHasFailures(failedHakuOids.nonEmpty, hasEnded = true)
      case Failure(t: Throwable) =>
        log.error(t, s"($groupUuid) Sync all one haku at a time went very wrong somehow: ")
      //AtomicStatus.updateHasFailures(hasFailures = true, hasEnded = true)
    }
    results
  }

  def syncSingle(personOid: String): Future[Boolean] = {
    val henkiloForOid = oppijaNumeroRekisteri.getByOids(Set(personOid)).map(_.get(personOid))
    henkiloForOid.flatMap(henkilo => {
      if (henkilo.isEmpty) {
        val errorStr = s"Henkilo not found from onr for oid $personOid"
        log.error(errorStr)
        Future.failed(new RuntimeException(errorStr))
      } else {
        log.info(s"Found Henkilo for personOid $personOid, fetching aliases and syncing.")
        oppijaNumeroRekisteri
          .enrichWithAliases(Set(personOid))
          .flatMap(aliases => {
            syncHenkiloWithYtl(henkilo.get, aliases)
          })
      }
    })
  }

  def syncHenkiloWithYtl(
    henkilo: Henkilo,
    personOidsWithAliases: PersonOidsWithAliases
  ): Future[Boolean] = {
    if (henkilo.hetu.isEmpty) {
      log.warning(s"Henkilo $henkilo does not have ssn. Cannot sync with YTL.")
      Future.failed(
        new RuntimeException(s"Henkilo $henkilo does not have ssn. Cannot sync with YTL.")
      )
    } else {
      log.info(s"Syncronizing henkilo ${henkilo.oidHenkilo} with YTL")
      ytlHttpClient.fetchOne(YtlHetuPostData(henkilo.hetu.get, henkilo.kaikkiHetut)) match {
        case None =>
          val noData = s"No YTL data for henkilo ${henkilo.oidHenkilo}"
          log.debug(noData)
          Future.failed(new RuntimeException(noData))
        case Some((_, student)) =>
          log.info(
            s"Found YTL data for henkilo ${henkilo.oidHenkilo}. Converting and persisting..."
          )
          val kokelas = StudentToKokelas.convert(henkilo.oidHenkilo, student)
          val persistKokelasStatus = ytlKokelasPersister.persistSingle(
            KokelasWithPersonAliases(kokelas, personOidsWithAliases)
          )
          try {
            Await.result(
              persistKokelasStatus,
              config.ytlSyncTimeout.duration + 10.seconds
            )
            Future.successful(true)
          } catch {
            case e: Throwable =>
              Future.failed(new RuntimeException(s"Persist kokelas ${kokelas.oid} failed", e))
          }
      }
    }
  }
  private def fetchAndHandleHakemuksetForSingleHakuF(
    hakuOid: String,
    tunniste: String
  ): Future[Option[Throwable]] = {
    //val hasErrors = new AtomicBoolean(false)
    val errors = new AtomicReference[List[Throwable]]()
    val hasEnded = new AtomicBoolean(false)

    try {
      log.info(
        s"($tunniste) About to fetch hakemukses and possible additional hetus for persons in haku $hakuOid"
      )
      hakemusService
        .hetuAndPersonOidForHakuLite(hakuOid)
        .flatMap(persons => {
          if (persons.isEmpty) {
            log.info(s"($tunniste) Ei löydetty henkilöitä haulle $hakuOid")
            Future.successful(None)
          } else {
            log.info(
              s"($tunniste) Got ${persons.size} persons for haku $hakuOid from hakemukses. Fetching masterhenkilos!"
            )

            //Tässä on map hakemuksenHenkilöoid -> henkilö, joka sisältää sekä masterOidin sekä hetun
            val futureHenkilosWithHetus: Future[Map[String, Henkilo]] = oppijaNumeroRekisteri
              .fetchHenkilotInBatches(persons.map(_.personOid).toSet)
              .map(_.filter(_._2.hetu.isDefined))

            val hetuToMasterOidF = futureHenkilosWithHetus
              .map(_.values)
              .map(_.toSet)
              .map((henkilot: Set[Henkilo]) => {
                val hetuToMasterOid = henkilot
                  .flatMap(henkilo =>
                    (List(henkilo.hetu.get) ++ henkilo.kaikkiHetut.getOrElse(List.empty))
                      .map(hetu => hetu -> henkilo.oidHenkilo)
                  )
                  .toMap
                log.info(
                  s"($tunniste) Muodostettiin ${hetuToMasterOid.size} hetu+masteroid-paria ${henkilot.size} henkilölle"
                )
                hetuToMasterOid
              })

            val futureHetuToAllHetus = futureHenkilosWithHetus
              .map(
                _.map((person: (String, Henkilo)) => person._2.hetu.get -> person._2.kaikkiHetut)
              )

            val personsGrouped: Iterator[Set[HetuPersonOid]] = persons.toSet.grouped(10000)

            val futurePersonOidsWithAliases = Future
              .sequence(
                personsGrouped
                  .map(ps => oppijaNumeroRekisteri.enrichWithAliases(ps.map(_.personOid)))
              )
              .map(result =>
                result.reduce((a, b) =>
                  PersonOidsWithAliases(
                    a.henkiloOids ++ b.henkiloOids,
                    a.aliasesByPersonOids ++ b.aliasesByPersonOids
                  )
                )
              )

            val result: Future[Unit] = for {
              allHetusToPersonOids: Map[String, String] <- hetuToMasterOidF
              hetuToAllHetus <- futureHetuToAllHetus
              personOidsWithAliases <- futurePersonOidsWithAliases
            } yield {
              log.info(
                s"($tunniste) Hetus (${allHetusToPersonOids.size} total) and aliases fetched for haku $hakuOid. Will fetch YTL data shortly!"
              )
              val count: Int = Math
                .ceil(hetuToAllHetus.keys.toList.size.toDouble / ytlHttpClient.chunkSize.toDouble)
                .toInt

              val futures: Iterator[Future[Unit]] = ytlHttpClient
                .fetch(tunniste, hetuToAllHetus.toSeq.map(h => YtlHetuPostData(h._1, h._2)))
                .zipWithIndex
                .map {
                  case (Left(t: Throwable), index) =>
                    log
                      .error(
                        t,
                        s"($tunniste) failed to fetch YTL data for index $index / haku $hakuOid: ${t.getMessage}"
                      )
                    errors.updateAndGet(previousErrors => t :: previousErrors)
                    Future.failed(t)
                  case (Right((zip, students)), index) =>
                    try {
                      log.info(
                        s"($tunniste) Fetch succeeded on YTL data batch ${index + 1}/$count for haku $hakuOid!"
                      )

                      val kokelaksetToPersist =
                        getKokelaksetToPersist(students.toSeq, allHetusToPersonOids, tunniste)
                      persistKokelaksetInBatches(kokelaksetToPersist, personOidsWithAliases)
                        .andThen {
                          case Success(_) =>
                            log.info(
                              s"($tunniste) Finished persisting YTL data batch ${index + 1}/$count for haku $hakuOid! All kokelakset succeeded! hasErrors: ${errors
                                .get()
                                .nonEmpty}, hasEnded ${hasEnded.get()}"
                            )
                          case Failure(t: Throwable) =>
                            log.error(
                              t,
                              s"($tunniste) Failed to persist all kokelas on YTL data batch ${index + 1}/$count for haku $hakuOid"
                            )
                            errors.updateAndGet(previousErrors => t :: previousErrors)
                        }
                    } finally {
                      log.info(
                        s"($tunniste) Closing zip file on YTL data batch ${index + 1}/$count for haku $hakuOid"
                      )
                      IOUtils.closeQuietly(zip)
                    }
                }

              Future.sequence(futures.toSeq).onComplete { _ =>
                log
                  .info(
                    s"($tunniste) Completed YTL syncAll for haku $hakuOid with hasErrors=${errors.get().nonEmpty}"
                  )
              }
            }
            result.map(r => {
              log.info(s"($tunniste) $r Future finished, returning none")
              val throwablesEncountered: List[Throwable] = errors.get()
              throwablesEncountered.foreach(t => {
                log.error(t, s"($tunniste) Error while ytl-syncing haku $hakuOid")
              })
              throwablesEncountered.headOption
            })
          }
        })

    } catch {
      case t: Throwable =>
        log.error(t, s"($tunniste) Fetching YTL data failed for haku $hakuOid!")
        Future.successful(Some(t))
    }
  }

  private def getKokelaksetToPersist(
    students: Seq[Student],
    hetuToPersonOid: Map[String, String],
    tunniste: String
  ): Seq[Kokelas] = {

    students.flatMap(student =>
      hetuToPersonOid.get(student.ssn) match {
        case Some(personOid) =>
          Try(StudentToKokelas.convert(personOid, student)) match {
            case Success(candidate) => Some(candidate)
            case Failure(exception) =>
              log.error(
                exception,
                s"($tunniste) Skipping student with SSN = ${student.ssn} because ${exception.getMessage}"
              )
              None
          }
        case None =>
          log.error(
            s"($tunniste) Skipping student as SSN (${student.ssn}) didnt match any person OID"
          )
          None
      }
    )
  }

  private def persistKokelaksetInBatches(
    kokelaksetToPersist: Seq[Kokelas],
    personOidsWithAliases: PersonOidsWithAliases
  ): Future[Unit] = {
    SequentialBatchExecutor.runInBatches(kokelaksetToPersist.iterator, config.ytlSyncParallelism)(
      kokelas => {
        ytlKokelasPersister.persistSingle(
          KokelasWithPersonAliases(kokelas, personOidsWithAliases.intersect(Set(kokelas.oid)))
        )
      }
    )
  }

}
