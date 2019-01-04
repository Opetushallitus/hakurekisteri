package fi.vm.sade.hakurekisteri.integration.koski
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.Scheduler

import scala.compat.Platform
import scala.concurrent.Future
import scala.concurrent.duration._

trait IKoskiService {
  def setAktiiviset2AsteHaut(hakuOids: Set[String]): Unit
  def updateLukioArvosanatForAktiivisetHaut(): () => Unit
  def updateHenkilotForHaku(hakuOid: String, createLukio: Boolean = false, overrideTimeCheck: Boolean = false, useBulkOperation: Boolean = false): Future[Unit]
  def updateHenkilot(oppijaOids: Set[String], createLukio: Boolean = false, overrideTimeCheck: Boolean = false): Future[Unit]

  //Tällä voi käydä läpi määritellyn aikaikkunan verran dataa Koskesta, jos joskus tulee tarve käsitellä aiempaa koskidataa uudelleen.
  //Oletusparametreilla hakee muutoset päivän taaksepäin, jotta Sure selviää alle 24 tunnin downtimeistä ilman Koskidatan puuttumista.
  def traverseKoskiDataInChunks(searchWindowStartTime: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(1)),
                                timeToWaitUntilNextBatch: FiniteDuration = 2.minutes,
                                searchWindowSize: Long = TimeUnit.DAYS.toMillis(15),
                                repairTargetTime: Date = new Date(Platform.currentTime),
                                pageNbr: Int = 0,
                                pageSizePerFetch: Int = 1500)(implicit scheduler: Scheduler): Unit
}

class KoskiServiceMock extends IKoskiService {
  override def setAktiiviset2AsteHaut(hakuOids: Set[String]): Unit = None
  override def updateLukioArvosanatForAktiivisetHaut(): () => Unit = () => ()
  override def updateHenkilot(oppijaOids: Set[String], createLukio: Boolean = false, overrideTimeCheck: Boolean = false): Future[Unit] = Future.successful(())

  override def traverseKoskiDataInChunks(searchWindowStartTime: Date, timeToWaitUntilNextBatch:
  FiniteDuration, searchWindowSize: Long, repairTargetTime: Date, pageNbr: Int, pageSizePerFetch: Int)
                                        (implicit scheduler: Scheduler): Unit = {}

  override def updateHenkilotForHaku(hakuOid: String, createLukio: Boolean, overrideTimeCheck: Boolean = false, useBulkOperation: Boolean = false): Future[Unit] = {Future.successful(Unit)}
}