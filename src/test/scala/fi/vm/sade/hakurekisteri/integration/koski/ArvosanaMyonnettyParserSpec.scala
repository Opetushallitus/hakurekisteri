package fi.vm.sade.hakurekisteri.integration.koski


import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.test.tools.ClassPathUtil
import org.joda.time.LocalDate
import org.json4s.jackson.JsonMethods
import org.scalatest.{FlatSpec, ShouldMatchers}

class ArvosanaMyonnettyParserSpec extends FlatSpec with ShouldMatchers with HakurekisteriJsonSupport with JsonMethods {
  it should "parse dates for arvosanas" in {
    val osaSuoritukset: Seq[KoskiOsasuoritus] = readOsasuoritukset("henkilo_from_koski.json")

    val biologia = osaSuoritukset.find(_.koulutusmoduuli.tunniste.exists(_.koodiarvo == "BI"))
    biologia shouldBe defined
    val determinedDate = ArvosanaMyonnettyParser.findArviointipäivä(biologia.get, "personOid", "aine", new LocalDate(1970, 1, 1))
    determinedDate should be(Some(new LocalDate(2018, 12, 31)))
  }

  it should "only take arvosana specific myonto dates when they are after suoritus valmistuminen date" in {
    val osaSuoritukset: Seq[KoskiOsasuoritus] = readOsasuoritukset("henkilo_from_koski.json")

    val suorituksenValmistumisPvm: LocalDate = new LocalDate(2018, 6, 2)

    val biologia = osaSuoritukset.find(_.koulutusmoduuli.tunniste.exists(_.koodiarvo == "BI"))
    biologia shouldBe defined
    val biologiaDate = ArvosanaMyonnettyParser.findArviointipäivä(biologia.get, "personOid", "aine", suorituksenValmistumisPvm)
    biologiaDate should be(Some(new LocalDate(2018, 12, 31)))

    val terveystieto = osaSuoritukset.find(_.koulutusmoduuli.tunniste.exists(_.koodiarvo == "TE"))
    terveystieto shouldBe defined
    val terveystietoDate = ArvosanaMyonnettyParser.findArviointipäivä(terveystieto.get, "personOid", "terveystieto", suorituksenValmistumisPvm)
    terveystietoDate should be(None)
  }

  it should "not store arvosana specific myonto dates when they are null in Koski" in {
    val osaSuoritukset: Seq[KoskiOsasuoritus] = readOsasuoritukset("henkilo_without_arviointipvm_from_koski.json")

    val ensimmainenVierasKieli = osaSuoritukset.find(_.koulutusmoduuli.tunniste.exists(_.koodiarvo == "BI"))
    ensimmainenVierasKieli shouldBe defined
    val vieraanKielenMyontoPvm = ArvosanaMyonnettyParser.findArviointipäivä(ensimmainenVierasKieli.get, "personOid", "bilsa", new LocalDate(2018, 6, 19))
    vieraanKielenMyontoPvm should be(None)
  }

  private def readOsasuoritukset(fileNameInSamePackage: String) = {
    val jsonString: String = ClassPathUtil.readFileFromClasspath(getClass, fileNameInSamePackage)
    val koskiHenkiloContainer = parse(jsonString).extract[KoskiHenkiloContainer]

    for {
      opiskeluoikeus <- koskiHenkiloContainer.opiskeluoikeudet
      suoritus <- opiskeluoikeus.suoritukset
      osasuoritus <- suoritus.osasuoritukset
    } yield osasuoritus
  }
}
