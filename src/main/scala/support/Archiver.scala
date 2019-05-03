package support

trait Archiver {

  def archive(): Unit

  def acquireLockForArchiving(): Boolean

  def clearLockForArchiving(): Boolean

}