package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import org.json4s.jackson.Serialization._

import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, SuoritusDeserializer}
import fi.vm.sade.hakurekisteri.storage.Identified

import java.text.SimpleDateFormat
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus}
import org.joda.time.{DateTime, MonthDay}
import fi.vm.sade.hakurekisteri.tools.Peruskoulu

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class IdentifiableSerializationSpec
    extends AnyWordSpec
    with Matchers
    with HakurekisteriJsonSupport {

  override protected implicit def jsonFormats = super.jsonFormats ++ List(new SuoritusDeserializer)

  val identifier = UUID.randomUUID()
  val opiskelija = new Opiskelija(
    "1.2.3",
    "9": String,
    "9A": String,
    "2.3.4": String,
    DateTime.now,
    Some(DateTime.now),
    source = "Test"
  )

  val df = new SimpleDateFormat("yyyyMMdd")

  val kevatJuhla = new MonthDay(6, 4).toLocalDate(DateTime.now.getYear).toDateTimeAtStartOfDay

  val suoritus: VirallinenSuoritus = Peruskoulu("1.2.3", "KESKEN", kevatJuhla.toLocalDate, "1.2.4")

  "An identified suoritus " when {
    val s: Suoritus with Identified[UUID] = suoritus.identify
    "serialized" should {
      val result = serializeDeserialize[Suoritus, Suoritus](s)
      val identity = serializeDeserialize[Identified[UUID], Suoritus](s)
      "retain henkiloOid" in {
        result.henkiloOid should equal(s.henkiloOid)
      }

      "retain identity" in {
        identity.id should equal(s.id)
      }

    }
  }

  "An identified opiskelija " when {
    val o = opiskelija.identify(identifier)
    "serialized" should {
      val result = serializeDeserialize[Opiskelija, Opiskelija](o)
      val identity = serializeDeserialize[Identified[UUID], Opiskelija](o)
      "retain henkiloOid" in {
        result.henkiloOid should equal(o.henkiloOid)
      }

      "retain luokkataso" in {
        result.luokkataso should equal(o.luokkataso)
      }

      "retain luokka" in {
        result.luokka should equal(o.luokka)
      }

      "retain loppuPaiva with 1s precision" in {
        result.loppuPaiva.get.toDate.getTime / 1000 should be(
          o.loppuPaiva.get.toDate.getTime / 1000
        )
      }

      "retain alkuPaiva with 1s precision" in {
        result.alkuPaiva.toDate.getTime / 1000 should be(o.alkuPaiva.toDate.getTime / 1000)
      }

      "retain oppilaitosOid" in {
        result.oppilaitosOid should equal(o.oppilaitosOid)
      }

      "retain identity" in {
        identity.id should equal(o.id)
      }

    }
  }

  def serializeDeserialize[A: Manifest, B: Manifest](o: B with Identified[UUID]) = {
    val json = write(o)
    read[A](json)
  }
}
