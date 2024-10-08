package fi.vm.sade.hakurekisteri.integration.haku

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.pipe
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.kouta.KoutaInternalActorRef
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActorRef
import support.TypedActorRef

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

object GetHautQuery

class HakuAggregatorActor(
  tarjonta: TarjontaActorRef,
  koutaInternal: KoutaInternalActorRef,
  config: Config
) extends Actor
    with ActorLogging {
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )
  implicit val timeout: Timeout = Timeout(60.seconds)

  override def receive: Receive = { case GetHautQuery =>
    getHaut pipeTo sender
  }

  def getHaut: Future[RestHakuResult] = for {
    tarjontaHaut <- (tarjonta.actor ? GetHautQuery).map(asRestHakuResult)
    koutaInternalHaut <- (koutaInternal.actor ? GetHautQuery).map(asRestHakuResult)
  } yield RestHakuResult(result = tarjontaHaut.result ++ koutaInternalHaut.result)

  def asRestHakuResult(result: Any): RestHakuResult = result match {
    case x: RestHakuResult => x
  }
}

case class HakuAggregatorActorRef(actor: ActorRef) extends TypedActorRef

class MockHakuAggregatorActor(
  tarjonta: TarjontaActorRef,
  koutaInternal: KoutaInternalActorRef,
  config: Config
) extends HakuAggregatorActor(tarjonta, koutaInternal, config) {}
