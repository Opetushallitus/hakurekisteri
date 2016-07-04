package fi.vm.sade.hakurekisteri.dates

import org.joda.time._
import org.joda.time.chrono.BaseChronology

object InFuture extends ReadableInstant {
  override def isBefore(instant: ReadableInstant): Boolean = false

  override def isAfter(instant: ReadableInstant): Boolean = true

  override def isEqual(instant: ReadableInstant): Boolean = instant eq InFuture

  override def toInstant: Instant = new Instant(Long.MaxValue)

  override def isSupported(field: DateTimeFieldType): Boolean = false

  override def get(`type`: DateTimeFieldType): Int = 0

  override def getZone: DateTimeZone = DateTimeZone.UTC

  override def getChronology: Chronology = new BaseChronology{
    override def withZone(zone: DateTimeZone): Chronology = this

    override def withUTC(): Chronology = this

    override def getZone: DateTimeZone = DateTimeZone.UTC

    override def toString: String = "futurecron"
  }

  override def getMillis: Long = Long.MaxValue

  override def compareTo(o: ReadableInstant): Int = Int.MaxValue
}
