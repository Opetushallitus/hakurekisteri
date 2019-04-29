package support

trait Archiver {

  def archive()

  def acquireLockForArchiving(): Seq[Boolean]

  def clearLockForArchiving(): Seq[Boolean]

}