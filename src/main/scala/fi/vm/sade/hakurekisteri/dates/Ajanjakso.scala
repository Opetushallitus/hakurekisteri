package fi.vm.sade.hakurekisteri.dates

import org.joda.time.{DateTime, LocalDate, ReadableInstant}
import com.github.nscala_time.time.Implicits._


case class Ajanjakso(alku: ReadableInstant, loppu: ReadableInstant) {

  def toInterval = alku to loppu

  def loppuOption: Option[ReadableInstant] = if (loppu eq InFuture) None else Some(loppu)

  def isCurrently = {
    val referencePoint = DateTime.now
    (alku.isBefore(referencePoint)  || alku.isEqual(referencePoint)) && (loppu.isAfter(referencePoint))
  }

}


object Ajanjakso {

  def apply(alkuPaiva: LocalDate,
            loppuPaiva: Option[LocalDate]): Ajanjakso =  Ajanjakso(alkuPaiva.toDateTimeAtStartOfDay, loppuPaiva.map(_.toDateTimeAtStartOfDay))

  def apply(alkuPaiva: ReadableInstant,
            loppuPaiva: Option[ReadableInstant]): Ajanjakso = Ajanjakso(alkuPaiva, loppuPaiva.getOrElse(InFuture))



}
