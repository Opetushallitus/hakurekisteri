package fi.vm.sade.hakurekisteri.integration.virta

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable}
import akka.pattern.ask
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.hakemus.{IHakemusService, Trigger}
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.henkilo.IOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.web.integration.virta.Status
import fi.vm.sade.hakurekisteri.web.integration.virta.Status.Status
import org.joda.time.DateTime

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

case class VirtaQuery(oppijanumero: String, hetu: Option[String])
case class KomoNotFoundException(message: String) extends Exception(message)
case class VirtaData(opiskeluOikeudet: Seq[Opiskeluoikeus], suoritukset: Seq[Suoritus])
case class VirtaStatus(
  lastProcessDone: Option[DateTime] = None,
  processing: Option[Boolean] = None,
  queueLength: Long,
  status: Status
)
case class QueryProsessed(q: VirtaQuery)
case class RefreshOppijaFromVirta(oppijaOid: String)
case class RefreshHakuFromVirta(hakuOid: String)

object RescheduleVirtaProcessing
object StartVirtaProcessing
object PrintVirtaStats
object VirtaHealth
object CancelSchedule

class VirtaQueue(
  virtaActor: VirtaActorRef,
  hakemusService: IHakemusService,
  oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
  hakuActor: ActorRef,
  config: Config
) extends Actor
    with ActorLogging {
  implicit val executionContext: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

  val virtaQueue: mutable.Set[VirtaQuery] = mutable.LinkedHashSet()
  private var lastProcessDone: Option[DateTime] = None
  private var processing: Boolean = false
  private var processingStarter: Cancellable = scheduleProcessing
  private val statPrinter: Cancellable =
    context.system.scheduler.schedule(5.minutes, 10.minutes, self, PrintVirtaStats)

  def scheduleProcessing: Cancellable =
    context.system.scheduler.schedule(1.hour, 1.hour, self, StartVirtaProcessing)

  override def postStop(): Unit = {
    processingStarter.cancel()
    statPrinter.cancel()
  }

  def receive: Receive = {

    //this is hit only when preStart (see below) sends a VirtaQuery
    case q: VirtaQuery if !virtaQueue.contains(q) =>
      virtaQueue.add(q)

    case r: RefreshOppijaFromVirta =>
      oppijaNumeroRekisteri
        .getByOids(Set(r.oppijaOid))
        .map(henkilot => {
          val hetu = henkilot.headOption.map(_._2).flatMap(h => h.hetu)
          val q = VirtaQuery(r.oppijaOid, hetu)
          log.info(s"Yhden henkilön rajapinnan Virta-kysely $q")
          if (processing) {
            log.info(
              "Fetching data from Virta for oppija {}, manual refresh. Virtaqueue processing already underway, adding to queue",
              r.oppijaOid
            )
            virtaQueue.add(q)
          } else {
            log.info(
              "Fetching data from Virta for oppija {}, manual refresh. Processing not active, updating right away ",
              r.oppijaOid
            )
            virtaActor.actor ! q
          }
        })

    case r: RefreshHakuFromVirta =>
      val hakuOid = r.hakuOid
      (hakuActor ? GetHaku(hakuOid))(1.hour)
        .mapTo[Haku]
        .map(haku => {
          if (haku.kkHaku) {
            log.info(
              s"Aloitetaan haun $hakuOid hakijoiden tietojen päivittäminen Virta-järjestelmästä."
            )
            hakemusService
              .hakemuksetForHaku(hakuOid, None)
              .map(hakemukset => {
                log.info(
                  s"Päivitetään ${hakemukset.size} henkilön tiedot Virta-järjestelmästä haulle $hakuOid"
                )
                hakemukset.foreach(hakemus =>
                  hakemus.personOid.map(personOid => {
                    val q = VirtaQuery(personOid, hakemus.hetu)
                    log.info(s"Koko haun päivityksen rajapinnan Virta-kysely $q")
                    self ! q
                  })
                )
              })
          } else {
            log.warning(
              s"Haku ${hakuOid} ei ole kk-haku. Ei päivitetä haun hakijoiden tietoja Virta-järjestelmästä."
            )
          }
        })

    case StartVirtaProcessing if !processing =>
      log.info("started to process virta queries")
      if (virtaQueue.nonEmpty) {
        processing = true
        virtaActor.actor ! virtaQueue.head
      } else log.info("no queries to process")

    case QueryProsessed(q) =>
      virtaQueue.remove(q)
      if (virtaQueue.nonEmpty) {
        virtaActor.actor ! virtaQueue.head
      } else {
        log.info(s"all virta queries processed, queue length ${virtaQueue.size}")
        lastProcessDone = Some(new DateTime())
        processing = false
      }

    case PrintVirtaStats => log.info(s"queue length ${virtaQueue.size}")

    case VirtaHealth =>
      sender ! VirtaStatus(lastProcessDone, Some(processing), virtaQueue.size, Status.OK)

    case CancelSchedule =>
      processingStarter.cancel()
      virtaQueue.clear()
      log.info(s"cancelled scheduled processing")

    case RescheduleVirtaProcessing =>
      processingStarter = scheduleProcessing
      log.info(s"restarted scheduled processing")
  }

  override def preStart(): Unit = {
    val trigger: Trigger = Trigger((oid, hetu, hakuOid, personOidsWithAliases) =>
      if (!isTilapainenHetu(hetu))
        (hakuActor ? GetHaku(hakuOid))(1.hour)
          .mapTo[Haku]
          .map(haku => haku.kkHaku)
          .recoverWith { case t: HakuNotFoundException =>
            Future.successful(true)
          }
          .map(isKkHaku => if (isKkHaku) self ! VirtaQuery(oid, Some(hetu)))
    )
    hakemusService.addTrigger(trigger)
    super.preStart()
  }

  val tilapainenHetu = "\\d{6}[+-AB]9\\d{2}[0123456789ABCDEFHJKLMNPRSTUVWXY]"
  def isTilapainenHetu(hetu: String): Boolean = hetu.matches(tilapainenHetu)
}
