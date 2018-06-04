package fi.vm.sade.hakurekisteri.integration.koski


import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.apache.commons.io.IOUtils
import org.joda.time.LocalDate
import org.json4s.jackson.JsonMethods
import org.scalatest.{FlatSpec, ShouldMatchers}
import org.springframework.core.io.ClassPathResource

import scala.collection.JavaConverters._

class ArvosanaMyonnettyParserSpec extends FlatSpec with ShouldMatchers with HakurekisteriJsonSupport with JsonMethods {
  it should "parse dates for arvosanas" in {
    val osaSuoritukset: Seq[KoskiOsasuoritus] = readOsasuoritukset("henkilo_from_koski.json")

    val biologia = osaSuoritukset.find(_.koulutusmoduuli.tunniste.exists(_.koodiarvo == "BI"))
    biologia shouldBe defined
    val determinedDate = ArvosanaMyonnettyParser.findArviointipäivä(biologia.get, "personOid", "aine", new LocalDate(1970, 1, 1))
    determinedDate should be(new LocalDate(2018, 12, 31))
  }

  private def readOsasuoritukset(fileNameInSamePackage: String) = {
    val jsonString: String = readFileFromClasspath(fileNameInSamePackage)
    val koskiHenkiloContainer = parse(jsonString).extract[KoskiHenkiloContainer]

    for {
      opiskeluoikeus <- koskiHenkiloContainer.opiskeluoikeudet
      suoritus <- opiskeluoikeus.suoritukset
      osasuoritus <- suoritus.osasuoritukset
    } yield osasuoritus
  }

  private def readFileFromClasspath(fileNameInSamePackage: String) = {
    val classPathResource = new ClassPathResource(fileNameInSamePackage, getClass)
    IOUtils.readLines(classPathResource.getInputStream, "UTF-8").asScala.mkString
  }
}
