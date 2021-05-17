package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.Scheduler

import scala.concurrent.Future
import scala.concurrent.duration._

trait IKoskiService {
  def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit
  def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit
  def updateAktiivisetKkAsteenHaut(): () => Unit
  def updateAktiivisetToisenAsteenHaut(): () => Unit
  def updateHenkilotForHaku(hakuOid: String, params: KoskiSuoritusHakuParams): Future[Unit]
  def updateHenkilot(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])]
  def updateHenkilotWithAliases(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])]

  def fetchOppivelvollisuusTietos(oppijaOids: Seq[String]): Future[Seq[OppivelvollisuusTieto]]

  def refreshChangedOppijasFromKoski(
    cursor: Option[String] = None,
    timeToWaitUntilNextBatch: FiniteDuration = 1.minutes
  )(implicit scheduler: Scheduler): Unit
}

class KoskiServiceMock extends IKoskiService {
  override def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit = None
  override def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit = None
  override def updateAktiivisetKkAsteenHaut(): () => Unit = () => ()
  override def updateAktiivisetToisenAsteenHaut(): () => Unit = () => ()
  override def updateHenkilotForHaku(
    hakuOid: String,
    params: KoskiSuoritusHakuParams
  ): Future[Unit] = Future.successful(())
  override def updateHenkilot(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = Future.successful(Seq[String](), Seq[String]())
  override def updateHenkilotWithAliases(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = Future.successful(Seq[String](), Seq[String]())

  override def fetchOppivelvollisuusTietos(oppijaOids: Seq[String]
  ): Future[Seq[OppivelvollisuusTieto]] = Future.successful(Seq[OppivelvollisuusTieto]())

  override def refreshChangedOppijasFromKoski(
    cursor: Option[String] = None,
    timeToWaitUntilNextBatch: FiniteDuration = 1.minutes
  )(implicit scheduler: Scheduler): Unit = {}
}
