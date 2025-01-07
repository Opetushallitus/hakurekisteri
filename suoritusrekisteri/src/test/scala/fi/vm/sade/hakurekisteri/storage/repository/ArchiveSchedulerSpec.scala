package fi.vm.sade.hakurekisteri.storage.repository

import org.mockito.Mockito
import org.mockito.Mockito.{never, reset, times, verify}
import org.scalatest.concurrent.Waiters
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import support.{ArchiveScheduler, Archiver}

class ArchiveSchedulerSpec
    extends AnyFlatSpec
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with Matchers
    with MockitoSugar
    with Waiters {

  private val archiver: Archiver = mock[Archiver]
  private val archiveScheduler = new ArchiveScheduler(archiver)
  archiveScheduler.start("10 10 * * * ?")

  override protected def beforeEach(): Unit = {
    reset(archiver)
  }

  it should "fail if cron expression is invalid" in {
    val expectedException =
      intercept[RuntimeException] {
        archiveScheduler.start("invalid")
      }
    expectedException.getMessage should include("CronExpression 'invalid' is invalid")
  }

  it should "don't invoke archive() if lock is not acquired" in {
    Mockito.when(archiver.acquireLockForArchiving()).thenReturn(false)
    Mockito.when(archiver.clearLockForArchiving()).thenReturn(true)

    (archiveScheduler.archive())()
    verify(archiver, times(1)).acquireLockForArchiving()
    verify(archiver, never()).archive()
    verify(archiver, never()).clearLockForArchiving()
  }

  it should "invoke archive() if lock is acquired, and then release the lock" in {
    Mockito.when(archiver.acquireLockForArchiving()).thenReturn(true)
    Mockito.when(archiver.clearLockForArchiving()).thenReturn(true)
    (archiveScheduler.archive())()
    verify(archiver, times(1)).acquireLockForArchiving()
    verify(archiver, times(1)).archive()
    verify(archiver, times(1)).clearLockForArchiving()
  }
}
