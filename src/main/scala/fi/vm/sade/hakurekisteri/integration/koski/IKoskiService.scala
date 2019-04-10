package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.Scheduler

import scala.concurrent.Future
import scala.concurrent.duration._

trait IKoskiService {
  def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit
  def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit
  def updateAktiivisetHaut(): () => Unit
  def updateHenkilotForHaku(hakuOid: String, params: KoskiSuoritusHakuParams): Future[Unit]
  def updateHenkilot(oppijaOids: Set[String], params: KoskiSuoritusHakuParams): Future[(Seq[String], Seq[String])]

  def refreshChangedOppijasFromKoski(cursor: Option[String] = None, timeToWaitUntilNextBatch: FiniteDuration = 1.minutes)(implicit scheduler: Scheduler): Unit
}

class KoskiServiceMock extends IKoskiService {
  override def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit = None
  override def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit = None
  override def updateAktiivisetHaut(): () => Unit = () => ()
  override def updateHenkilotForHaku(hakuOid: String, params: KoskiSuoritusHakuParams): Future[Unit] = Future.successful(())
  override def updateHenkilot(oppijaOids: Set[String], params: KoskiSuoritusHakuParams): Future[(Seq[String], Seq[String])] = Future.successful(Seq[String](), Seq[String]())

  override def refreshChangedOppijasFromKoski(cursor: Option[String] = None, timeToWaitUntilNextBatch: FiniteDuration = 1.minutes)(implicit scheduler: Scheduler): Unit = {}
}