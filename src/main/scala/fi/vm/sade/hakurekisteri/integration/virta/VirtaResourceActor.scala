package fi.vm.sade.hakurekisteri.integration.virta

import akka.actor.Status.Failure
import akka.actor.{Actor, ActorLogging, ActorRef}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import org.joda.time.LocalDate
import support.TypedActorRef

import scala.concurrent.{ExecutionContext, Future}

class VirtaResourceActor(virtaClient: VirtaClient, config: Config) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = ExecutorUtil.createExecutor(config.integrations.asyncOperationThreadPoolSize, getClass.getSimpleName)

  import akka.pattern.pipe

  override def receive: Receive = {
    case q: VirtaQuery =>
      getOpiskelijanTiedot(q.oppijanumero, q.hetu) pipeTo sender

    case Failure(t: VirtaValidationError) =>
      log.warning(s"virta validation error: $t")

    case Failure(t: Throwable) =>
      log.error(t, "error occurred in virta query")
  }

  def getOpiskelijanTiedot(oppijanumero: String, hetu: Option[String]): Future[VirtaResult] = {
    virtaClient.getOpiskelijanTiedot(oppijanumero = oppijanumero, hetu = hetu).map(_.getOrElse(VirtaResult(oppijanumero)))
  }

}

class MockVirtaResourceActor extends Actor {
  override def receive: Receive = {
    case q: VirtaQuery if q.hetu.contains("123456-789") || q.oppijanumero.contains("123456-789") =>
      //noinspection ScalaStyle
      sender ! VirtaResult(
        q.oppijanumero,
        Seq(
          VirtaOpiskeluoikeus(LocalDate.now().minusYears(5), Some(LocalDate.now().minusYears(1)), "01915", Seq("655301"), "FI"),
          VirtaOpiskeluoikeus(LocalDate.now(), Some(LocalDate.now().plusYears(1)), "01915", Seq("751301"), "FI")
        ),
        Seq(),
        Seq(
          VirtaOpintosuoritus(LocalDate.now(), Some("Inssimatikka 1"), None, Some(5.0), Some("5"), Some("Viisiportainen"), "01915", Some("2")),
          VirtaOpintosuoritus(LocalDate.now(), Some("Inssimatikka 2"), None, Some(5.0), Some("5"), Some("Viisiportainen"), "01915", Some("2")),
          VirtaOpintosuoritus(LocalDate.now(), Some("Tietotekniikan kandi"), Some("655301"), Some(5.0), Some("5"), Some("Viisiportainen"), "01915", Some("1")),
          VirtaOpintosuoritus(LocalDate.now(), Some("Inssifyssa 1"), None, Some(5.0), Some("4"), Some("Viisiportainen"), "01915", Some("2")),
          VirtaOpintosuoritus(LocalDate.now(), Some("Inssifyssa 2"), None, Some(5.0), Some("5"), Some("Viisiportainen"), "01915", Some("2")),
          VirtaOpintosuoritus(LocalDate.now(), Some("Foobar kurssi"), None, Some(5.0), Some("2"), Some("Viisiportainen"), "01915", Some("2"))
        )
      )
    case q: VirtaQuery =>
      sender ! VirtaResult(q.oppijanumero)
  }
}

case class VirtaResourceActorRef(actor: ActorRef) extends TypedActorRef
