package fi.vm.sade.hakurekisteri

import org.scalatest.WordSpec
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija

import org.json4s.FieldSerializer._
import org.json4s.{CustomSerializer, FieldSerializer, DefaultFormats, Formats}
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization._
import java.util.{UUID, Date}
import org.scalatest.matchers.ShouldMatchers
import org.json4s.JsonAST.{JValue, JField, JString, JObject}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, IdentitySerializer, UUIDSerializer}
import fi.vm.sade.hakurekisteri.storage.Identified

class IdentifiableSerializationSpec extends WordSpec with ShouldMatchers with HakurekisteriJsonSupport {

  val identifier = UUID.randomUUID()
  val opiskelija = new Opiskelija("1.2.3", "9": String, "9A": String, "2.3.4": String, new Date, Some(new Date))


  "An identified resource " when {
    val o = Opiskelija.identify(opiskelija, identifier)
    "serialized" should {
      val result = serializeDeserialize[Opiskelija](o)
      val identity = serializeDeserialize[Identified](o)
      "retain henkiloOid" in {
        result.henkiloOid should equal (o.henkiloOid)
      }

      "retain luokkataso" in {
        result.luokkataso should equal (o.luokkataso)
      }

      "retain luokka" in {
        result.luokka should equal (o.luokka)
      }

      "retain loppuPaiva with 1s precision" in {
        result.loppuPaiva.get.getTime / 1000 should be (o.loppuPaiva.get.getTime / 1000)
      }

      "retain alkuPaiva with 1s precision" in {
        result.alkuPaiva.getTime / 1000 should be (o.alkuPaiva.getTime / 1000)
      }

      "retain oppilaitosOid" in {
        result.oppilaitosOid should equal (o.oppilaitosOid)
      }

      "retain identity" in {
        identity.id should equal (o.id)
      }


    }
  }


  def serializeDeserialize[A: Manifest](o: Opiskelija with Identified)  = {
    val json = write(o)
    read[A](json)
  }
}
