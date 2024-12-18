package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.Scheduler

import scala.concurrent.Future
import scala.concurrent.duration._

trait IKoskiService {
  def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit

  def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit

  def setAktiivisetKKHaut(hakuOids: Set[String]): Unit

  def setAktiivisetToisenAsteenJatkuvatHaut(hakuOids: Set[String]): Unit

  def updateAktiivisetKkAsteenHaut(): () => Unit

  def updateAktiivisetToisenAsteenHaut(): () => Unit

  def syncHaunHakijat(
    hakuOid: String,
    params: KoskiSuoritusHakuParams,
    personOidsForHakuFn: String => Future[Set[String]]
  ): Future[Unit]

  def syncHaunHakijat(hakuOid: String, params: KoskiSuoritusHakuParams): Future[Unit]

  def updateHenkilot(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])]

  def updateHenkilotWithAliases(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])]

  def fetchKoulusivistyskielet(oppijaOids: Seq[String]): Future[Map[String, Seq[String]]]

  def fetchOppivelvollisuusTietos(oppijaOids: Seq[String]): Future[Seq[OppivelvollisuusTieto]]

  def refreshChangedOppijasFromKoski(
    lastQueryStart: Option[String],
    timeToWaitUntilNextBatch: FiniteDuration = 10.seconds
  )(implicit scheduler: Scheduler): Unit

  def updateAktiivisetToisenAsteenJatkuvatHaut(): () => Unit

  def updateHenkilotNew(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])]

  def updateHenkilotNewMuuttunutJalkeen(
    muuttunutJalkeen: String,
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])]
}

class KoskiServiceMock extends IKoskiService {
  override def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit = None
  override def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit = None
  override def setAktiivisetKKHaut(hakuOids: Set[String]): Unit = None
  override def setAktiivisetToisenAsteenJatkuvatHaut(hakuOids: Set[String]): Unit = None
  override def updateAktiivisetKkAsteenHaut(): () => Unit = () => ()
  override def updateAktiivisetToisenAsteenHaut(): () => Unit = () => ()
  override def updateAktiivisetToisenAsteenJatkuvatHaut(): () => Unit = () => ()
  override def updateHenkilot(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = Future.successful(Seq[String](), Seq[String]())
  override def updateHenkilotWithAliases(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = Future.successful(Seq[String](), Seq[String]())

  override def fetchOppivelvollisuusTietos(
    oppijaOids: Seq[String]
  ): Future[Seq[OppivelvollisuusTieto]] = Future.successful(Seq[OppivelvollisuusTieto]())

  override def refreshChangedOppijasFromKoski(
    cursor: Option[String] = None,
    timeToWaitUntilNextBatch: FiniteDuration = 1.minutes
  )(implicit scheduler: Scheduler): Unit = {}

  override def fetchKoulusivistyskielet(
    oppijaOids: Seq[String]
  ): Future[Map[String, Seq[String]]] = Future.successful(Map[String, Seq[String]]())
  override def syncHaunHakijat(
    hakuOid: String,
    params: KoskiSuoritusHakuParams,
    personOidsForHakuFn: String => Future[Set[String]]
  ): Future[Unit] = ???

  override def syncHaunHakijat(hakuOid: String, params: KoskiSuoritusHakuParams): Future[Unit] = ???

  override def updateHenkilotNew(
    oppijaOids: Set[String],
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = ???

  override def updateHenkilotNewMuuttunutJalkeen(
    muuttunutJalkeen: String,
    params: KoskiSuoritusHakuParams
  ): Future[(Seq[String], Seq[String])] = ???
}
