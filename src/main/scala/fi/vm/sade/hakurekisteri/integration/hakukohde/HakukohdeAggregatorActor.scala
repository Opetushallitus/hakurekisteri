package fi.vm.sade.hakurekisteri.integration.hakukohde

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{AskableActorRef, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.kouta.KoutaInternalActorRef
import fi.vm.sade.hakurekisteri.integration.tarjonta.{
  HakukohdeOid,
  HakukohteenKoulutukset,
  TarjontaActorRef
}
import support.TypedActorRef

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class HakukohdeAggregatorActor(
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
  val KOUTA_OID_LENGTH: Int = 35

  override def receive: Receive = {
    case q: HakukohdeQuery =>
      getHakukohde(q) pipeTo sender
    case q: HakukohteenKoulutuksetQuery =>
      getHakukohteenKoulutukset(q) pipeTo sender
  }

  private def getHakukohteenKoulutukset(
    q: HakukohteenKoulutuksetQuery
  ): Future[HakukohteenKoulutukset] = {
    q match {
      case q if q.hakukohdeOid.length == KOUTA_OID_LENGTH =>
        getKoutaInternalHakukohteenKoulutukset(q)
      case q => getTarjontaHakukohteenKoulutukset(q)
    }
  }

  private def getKoutaInternalHakukohteenKoulutukset(q: HakukohteenKoulutuksetQuery) =
    (koutaInternal.actor ? q).mapTo[HakukohteenKoulutukset]

  private def getTarjontaHakukohteenKoulutukset(q: HakukohteenKoulutuksetQuery) =
    (tarjonta.actor ? HakukohdeOid(q.hakukohdeOid))
      .mapTo[HakukohteenKoulutukset]

  private def getHakukohde(q: HakukohdeQuery): Future[Hakukohde] = {
    val hakukohde = q match {
      case q if q.oid.length == KOUTA_OID_LENGTH =>
        hakukohdeQuery(q, koutaInternal.actor)
      case q =>
        hakukohdeQuery(q, tarjonta.actor)
    }
    hakukohde.flatMap(
      _.fold(Future.failed[Hakukohde](new RuntimeException(s"Could not find hakukohde $q.oid")))(
        Future.successful
      )
    )
  }

  private def hakukohdeQuery(
    q: HakukohdeQuery,
    actor: AskableActorRef
  ): Future[Option[Hakukohde]] = (actor ? q).mapTo[Option[Hakukohde]]
}

case class HakukohdeAggregatorActorRef(actor: ActorRef) extends TypedActorRef

class MockHakukohdeAggregatorActor(
  tarjonta: TarjontaActorRef,
  koutaInternal: KoutaInternalActorRef,
  config: Config
) extends HakukohdeAggregatorActor(tarjonta, koutaInternal, config)
