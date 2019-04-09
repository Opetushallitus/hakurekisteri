package fi.vm.sade.hakurekisteri.integration.henkilo


import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import org.json4s._
import org.json4s.jackson.JsonMethods
import support.TypedActorRef

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

abstract class HenkiloActor(config: Config) extends Actor with ActorLogging {
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(config.integrations.asyncOperationThreadPoolSize, getClass.getSimpleName)

  def receive: Receive
}

class HttpHenkiloActor(virkailijaClient: VirkailijaRestClient, config: Config) extends HenkiloActor(config) {
  private val maxRetries = config.integrations.oppijaNumeroRekisteriConfig.httpClientMaxRetries
  private var savingHenkilo = false
  private var lastUnhandledSaveNext = 0L
  private val saveQueue: mutable.Map[SaveHenkilo, ActorRef] = new mutable.LinkedHashMap[SaveHenkilo, ActorRef]()
  private implicit val formats: Formats = DefaultFormats.lossless

  private object SaveNext

  def parseOid(h: String): String = {
    (JsonMethods.parse(h) \ "oidHenkilo").
      extractOpt[String].
      getOrElse("")
  }

  override def receive: Receive = {
    case s: SaveHenkilo =>
      saveQueue.put(s, sender())
      if (!savingHenkilo)
        self ! SaveNext

    case SaveNext if !savingHenkilo && saveQueue.nonEmpty =>
      savingHenkilo = true
      val (save, actor) = saveQueue.head
      saveQueue.remove(save)
      Future {
        Thread.sleep(100)
      }.flatMap(u => virkailijaClient.postObjectWithCodes[CreateHenkilo, String]("oppijanumerorekisteri-service.s2s.tiedonsiirrot", Seq(200, 201), 0, save.henkilo, false).map(saved => SavedHenkilo(parseOid(saved), save.tunniste)).recoverWith {
        case t: Throwable => Future.successful(HenkiloSaveFailed(save.tunniste, t))
      }).pipeTo(self)(actor)

    case SaveNext if !(!savingHenkilo && saveQueue.nonEmpty) =>
      val currentTs = java.lang.System.currentTimeMillis()
      if ((currentTs - lastUnhandledSaveNext) > 10.seconds.toMillis) {
        lastUnhandledSaveNext = currentTs
        log.debug(s"Received SaveNext while save in progress (savingHenkilo = ${savingHenkilo}) or save queue is empty (saveQueue.isEmpty = ${saveQueue.nonEmpty}). [Logging this message disabled for 10 seconds ]")
      }

    case s: SavedHenkilo =>
      savingHenkilo = false
      sender ! s
      self ! SaveNext

    case t: HenkiloSaveFailed =>
      savingHenkilo = false
      Future.failed(t) pipeTo sender
      self ! SaveNext

  }
}

class MockHenkiloActor(config: Config) extends HenkiloActor(config) {
  override def receive: Receive = {
    case s: SaveHenkilo =>
      throw new UnsupportedOperationException("Not implemented")

    case msg =>
      log.warning(s"not implemented receive(${msg})")
  }
}

object HetuUtil {
  val Hetu = "([0-9]{6}[-A][0-9]{3}[0123456789ABCDEFHJKLMNPRSTUVWXY])".r

  def toSyntymaAika(hetu: String): Option[String] = hetu match {
    case Hetu(h) =>
      val vuosisata = h.charAt(6) match {
        case '-' => "19"
        case 'A' => "20"
      }
      Some(s"$vuosisata${h.substring(4, 6)}-${h.substring(2, 4)}-${h.substring(0, 2)}")
    case _ => None
  }
}

case class Kieli(kieliKoodi: String, kieliTyyppi: Option[String] = None)

case class Kansalaisuus(kansalaisuusKoodi: String)

case class OrganisaatioHenkilo(organisaatioOid: String,
                               organisaatioHenkiloTyyppi: Option[String] = None,
                               voimassaAlkuPvm: Option[String] = None,
                               voimassaLoppuPvm: Option[String] = None,
                               tehtavanimike: Option[String] = None)

case class CreateHenkilo(etunimet: String,
                         kutsumanimi: String,
                         sukunimi: String,
                         hetu: Option[String] = None,
                         oidHenkilo: Option[String] = None,
                         externalIds: Option[Seq[String]] = None,
                         syntymaaika: Option[String] = None,
                         sukupuoli: Option[String] = None,
                         aidinkieli: Option[Kieli] = None,
                         henkiloTyyppi: String)

case class Henkilo(oidHenkilo: String,
                   hetu: Option[String],
                   henkiloTyyppi: String,
                   etunimet: Option[String],
                   kutsumanimi: Option[String],
                   sukunimi: Option[String],
                   aidinkieli: Option[Kieli],
                   kansalaisuus: List[Kansalaisuus],
                   syntymaaika: Option[String],
                   sukupuoli: Option[String],
                   asiointiKieli: Option[Kieli],
                   turvakielto: Option[Boolean])

case class SaveHenkilo(henkilo: CreateHenkilo, tunniste: String)

case class SavedHenkilo(henkiloOid: String, tunniste: String)

case class HenkiloSaveFailed(tunniste: String, t: Throwable) extends Exception(s"henkilo save failed for tunniste $tunniste", t)


case class HenkiloActorRef(actor: ActorRef) extends TypedActorRef
