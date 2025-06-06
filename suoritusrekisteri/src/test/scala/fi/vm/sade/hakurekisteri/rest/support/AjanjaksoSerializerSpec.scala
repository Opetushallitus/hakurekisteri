package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.{DefaultFormats, Formats}
import org.json4s.jackson.Serialization._
import org.json4s.jackson.JsonMethods
import fi.vm.sade.hakurekisteri.dates.Ajanjakso
import org.joda.time.DateTime._
import com.github.nscala_time.time.Implicits._
import org.json4s.JsonAST.{JNothing, JString, JValue}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AjanjaksoSerializerSpec extends AnyFlatSpec with Matchers {

  protected implicit def jsonFormats: Formats = DefaultFormats + new AjanjaksoSerializer

  behavior of "Ajanjakso serialization"

  it should "serialize end date if it's defined" in {
    val ajanjakso = Ajanjakso(now, now + 1.day)
    val loppuField = toJValue(ajanjakso) \ "loppu"
    loppuField should equal(JString(ajanjakso.loppu.toString))
  }

  it should "not serialize end date if it's defined" in {
    val ajanjakso = Ajanjakso(now, None)
    toJValue(ajanjakso) \ "loppu" should be(JNothing)
  }

  def toJValue(ajanjakso: Ajanjakso): JValue = {
    JsonMethods.parse(write(ajanjakso))
  }

}
