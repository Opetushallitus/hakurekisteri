package fi.vm.sade.hakurekisteri.storage.repository

import akka.actor.ActorSystem
import org.mockito.Mockito
import org.mockito.Mockito.{never, verify, times, reset}
import org.scalatest.concurrent.Waiters
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import support.{ArchiveScheduler, Archiver}

import scala.concurrent.duration._
import scala.concurrent.{Await}

class ArchiveSchedulerSpec extends FlatSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers with MockitoSugar with Waiters {

  private implicit val system = ActorSystem("test-jdbc")
  private val archiver: Archiver = mock[Archiver]
  private val archiveScheduler = new ArchiveScheduler(archiver)
  archiveScheduler.start("10 10 * * * ?")

  override protected def beforeEach(): Unit = {
    reset(archiver)
  }

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 15.seconds)
  }

  it should "don't invoke archive() if lock is not acquired" in {
    Mockito.when(archiver.acquireLockForArchiving()).thenReturn(Seq(false))
    Mockito.when(archiver.clearLockForArchiving()).thenReturn(Seq(true))

    (archiveScheduler.archive())()
    verify(archiver, times(1)).acquireLockForArchiving()
    verify(archiver, never()).archive()
    verify(archiver, never()).clearLockForArchiving()
  }

  it should "invoke archive() if lock is acquired, and then release the lock" in {
    Mockito.when(archiver.acquireLockForArchiving()).thenReturn(Seq(true))
    Mockito.when(archiver.clearLockForArchiving()).thenReturn(Seq(true))
    (archiveScheduler.archive())()
    verify(archiver, times(1)).acquireLockForArchiving()
    verify(archiver, times(1)).archive()
    verify(archiver, times(1)).clearLockForArchiving()
  }
}
