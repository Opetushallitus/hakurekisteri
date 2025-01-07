package fi.vm.sade.hakurekisteri.rest.support

import org.joda.time.DateTime
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class DateFormatterSpec extends AnyFlatSpec with Matchers {

  behavior of "HakurekisteriDefaultFormats.lossless.dateFormat"

  val format = HakurekisteriDefaultFormats.lossless.dateFormat

  it should "parse timestamp without millis and timezone" in {
    format.parse("2014-01-01T01:02:03") should be(Some(new DateTime(2014, 1, 1, 1, 2, 3).toDate))
  }

  it should "parse timestamp with millis and without timezone" in {
    format.parse("2014-01-01T01:02:03.004") should be(
      Some(new DateTime(2014, 1, 1, 1, 2, 3, 4).toDate)
    )
  }

  it should "parse timestamp with short millis and without timezone" in {
    format.parse("2014-01-01T01:02:03.4") should be(
      Some(new DateTime(2014, 1, 1, 1, 2, 3, 400).toDate)
    )
  }

  it should "parse timestamp with millis and timezone" in {
    format.parse("2014-01-01T01:02:03.004Z") should be(
      Some(new DateTime(2014, 1, 1, 3, 2, 3, 4).toDate)
    )
  }

  it should "parse timestamp with millis and numeric timezone" in {
    format.parse("2014-01-01T01:02:03.004+02:00") should be(
      Some(new DateTime(2014, 1, 1, 1, 2, 3, 4).toDate)
    )
  }

  it should "parse timestamp with short millis and timezone" in {
    format.parse("2014-01-01T01:02:03.4Z") should be(
      Some(new DateTime(2014, 1, 1, 3, 2, 3, 400).toDate)
    )
  }

  it should "parse timestamp without millis and with timezone" in {
    format.parse("2014-01-01T01:02:03Z") should be(Some(new DateTime(2014, 1, 1, 3, 2, 3).toDate))
  }

  it should "parse timestamp without millis and with numeric timezone" in {
    format.parse("2014-01-01T01:02:03+02:00") should be(
      Some(new DateTime(2014, 1, 1, 1, 2, 3).toDate)
    )
  }

}
