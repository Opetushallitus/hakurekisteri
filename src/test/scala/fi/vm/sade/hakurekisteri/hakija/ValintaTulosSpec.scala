package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.integration.valintatulos.ValintaTulos
import org.scalatest.{Matchers, FlatSpec}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

class ValintaTulosSpec extends FlatSpec with Matchers {
  behavior of "Valinnan tulosten parsinta"

  trait Parsed extends HakurekisteriJsonSupport {
    def casMap[T: ClassTag: TypeTag](value: T) = {
      val m = runtimeMirror(getClass.getClassLoader)
      val im = m.reflect(value)
      typeOf[T].members.collect{ case m: MethodSymbol if m.isCaseAccessor => m }.map(im.reflectMethod).map((m) => m.symbol.name.toString -> m()).toMap
    }

    import org.json4s.jackson.Serialization.read
    val resource = read[Seq[ValintaTulos]](json)
  }

  it should "find pisteet" in new Parsed() {
    resource.flatMap(_.hakutoiveet.map(_.pisteet.get)) should be(List(4.0, 26.0))
  }

  val json =
    """[
      |{"hakemusOid":"1.2.246.562.11.00000441369","hakijaOid":"1.2.246.562.24.14229104472","aikataulu":{"vastaanottoEnd":"2100-01-10T12:00:00Z","vastaanottoBufferDays":14},"hakutoiveet":[{"hakukohdeOid":"1.2.246.562.5.72607738902","tarjoajaOid":"1.2.246.562.10.591352080610","valintatapajonoOid":"14090336922663576781797489829886","valintatila":"HYVAKSYTTY","vastaanottotila":"KESKEN","ilmoittautumistila":{"ilmoittautumistila":"EI_TEHTY"},"vastaanotettavuustila":"VASTAANOTETTAVISSA_SITOVASTI","viimeisinValintatuloksenMuutos":"2014-08-26T19:05:23Z","jonosija":1,"varasijojaKaytetaanAlkaen":"2014-08-26T19:05:23Z","varasijojaTaytetaanAsti":"2014-08-26T19:05:23Z","julkaistavissa":true,"tilanKuvaukset":{},"pisteet":4.0},{"hakukohdeOid":"1.2.246.562.5.16303028779","tarjoajaOid":"1.2.246.562.10.455978782510","valintatapajonoOid":"","valintatila":"PERUUNTUNUT","vastaanottotila":"KESKEN","ilmoittautumistila":{"ilmoittautumistila":"EI_TEHTY"},"vastaanotettavuustila":"EI_VASTAANOTETTAVISSA","julkaistavissa":true,"tilanKuvaukset":{},"pisteet":26.0}]}
      |]""".stripMargin
}
