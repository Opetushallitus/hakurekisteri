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

  override def receive: Receive = {
    case q: HakukohdeQuery =>
      getHakukohde(q) pipeTo sender
    case q: HakukohteenKoulutuksetQuery =>
      getHakukohteenKoulutukset(q) pipeTo sender
  }

  private def getHakukohteenKoulutukset(
    q: HakukohteenKoulutuksetQuery
  ): Future[HakukohteenKoulutukset] = for {
    koulutukset <- getTarjontaHakukohteenKoulutukset(q)
      .flatMap { tarjontaHakukohteenKoulutukset =>
        {
          getKoutaInternalHakukohteenKoulutukset(q)
            .map(koutaInternalHakukohteenKoulutukset =>
              tarjontaHakukohteenKoulutukset.copy(
                koulutukset =
                  tarjontaHakukohteenKoulutukset.koulutukset ++ koutaInternalHakukohteenKoulutukset.koulutukset
              )
            )
        }
      }
      .recoverWith { case _ =>
        getKoutaInternalHakukohteenKoulutukset(q)
      }
  } yield koulutukset

  private def getKoutaInternalHakukohteenKoulutukset(q: HakukohteenKoulutuksetQuery) =
    (koutaInternal.actor ? q).mapTo[HakukohteenKoulutukset]

  private def getTarjontaHakukohteenKoulutukset(q: HakukohteenKoulutuksetQuery) =
    (tarjonta.actor ? HakukohdeOid(q.hakukohdeOid))
      .mapTo[HakukohteenKoulutukset]

  private def getHakukohde(q: HakukohdeQuery): Future[Hakukohde] = hakukohdeQuery(q, tarjonta.actor)
    .recoverWith[Option[Hakukohde]] { case _ =>
      hakukohdeQuery(q, koutaInternal.actor)
    }
    .flatMap(
      _.fold(Future.failed[Hakukohde](new RuntimeException(s"Could not find hakukohde $q.oid")))(
        Future.successful
      )
    )

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
