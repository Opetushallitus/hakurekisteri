package fi.vm.sade.hakurekisteri.integration.koski


import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.joda.time.LocalDate
import org.json4s.jackson.JsonMethods
import org.scalatest.{FlatSpec, ShouldMatchers}

class ArvosanaMyonnettyParserSpec extends FlatSpec with ShouldMatchers with HakurekisteriJsonSupport with JsonMethods {
  private val jsonString: String = scala.io.Source.fromFile("src/test/resources/fi/vm/sade/hakurekisteri/integration/koski/henkilo_from_koski.json").getLines().mkString

  it should "parse dates for arvosanas" in {
    val testDataJson = parse(jsonString)
    val koskiHenkiloContainer = testDataJson.extract[KoskiHenkiloContainer]

    val osaSuoritukset: Seq[KoskiOsasuoritus] = for {
      opiskeluoikeus <- koskiHenkiloContainer.opiskeluoikeudet
      suoritus <- opiskeluoikeus.suoritukset
      osasuoritus <- suoritus.osasuoritukset
    } yield osasuoritus

    val biologia = osaSuoritukset.find(_.koulutusmoduuli.tunniste.exists(_.koodiarvo == "BI"))
    biologia shouldBe defined
    val determinedDate = ArvosanaMyonnettyParser.findArviointipäivä(biologia.get, "personOid", "aine", new LocalDate(1970, 1, 1))
    determinedDate should be(new LocalDate(2018, 12, 31))
  }
}
