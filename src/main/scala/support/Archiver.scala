package support

trait Archiver {
  type BatchStatistics = Map[String, Long]

  type BatchArchiever = () => BatchStatistics

  def archive(
    batchArchiever: BatchArchiever = defaultBatchArchiever,
    maxErrorsAllowed: Int = 3
  ): Unit

  val defaultBatchArchiever: BatchArchiever

  def acquireLockForArchiving(): Boolean

  def clearLockForArchiving(): Boolean
}
