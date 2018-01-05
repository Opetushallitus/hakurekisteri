package fi.vm.sade.hakurekisteri.integration.koski

import java.util.UUID

import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.koski.KoskiArvosanaTrigger.parseLocalDate
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.hakurekisteri.{Oids, OrganisaatioOids, SpecsLikeMockito}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import org.joda.time.LocalDate
import org.json4s._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.Seq
import scala.concurrent.duration._
import scala.language.{implicitConversions, reflectiveCalls}


class KoskiActorSpec extends FlatSpec with Matchers with FutureWaiting with SpecsLikeMockito with AsyncAssertions
  with MockitoSugar with DispatchSupport with ActorSystemSupport with LocalhostProperties {

  implicit val formats = DefaultFormats
  implicit val timeout: Timeout = 5.second
  val koskiConfig = ServiceConfig(serviceUrl = "http://localhost/koski/api/oppija")

  it should "empty KoskiHenkilo should return list" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer().build
    ) should contain theSameElementsAs Seq()
  }

  it should "list should return peruskoulutus with kieli arvosana" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setPeruskouluKieli()
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = "010101-0101", syntymäaika = None, etunimet = "Test", kutsumanimi = "Test", sukunimi = "Tester"))
        .build
    ) should contain theSameElementsAs Seq((VirallinenSuoritus(Oids.perusopetusKomoOid,
      "orgId",
      "VALMIS",
      parseLocalDate("2016-02-02"),
      "henkilo_oid",
      yksilollistaminen.Ei,
      "FI",
      None,
      true,
      OrganisaatioOids.oph,
      Map.empty), Seq(Arvosana( suoritus = null, arvio = Arvio410("9"), "A1", lisatieto = Some("FI"), valinnainen = false, myonnetty = None, source = "henkilo_oid", Map())
    )))
  }


  it should "list should return peruskoulutus with arvosanat" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setSuorituksetForPeruskoulu(List(("HI", "9"), ("MU", "8")))
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = "010101-0101", syntymäaika = None, etunimet = "Test", kutsumanimi = "Test", sukunimi = "Tester"))
        .build
    ) should contain theSameElementsAs Seq((VirallinenSuoritus(Oids.perusopetusKomoOid,
      "orgId",
      "VALMIS",
      parseLocalDate("2016-02-02"),
      "henkilo_oid",
      yksilollistaminen.Ei,
      "FI",
      None,
      true,
      OrganisaatioOids.oph,
      Map.empty), Seq(
        Arvosana(suoritus = null, arvio = Arvio410("9"), "HI", lisatieto = None, valinnainen = false, myonnetty = None, source = "henkilo_oid", Map()),
        Arvosana(suoritus = null, arvio = Arvio410("8"), "MU", lisatieto = None, valinnainen = false, myonnetty = None, source = "henkilo_oid", Map())
    )))
  }

  it should "list should return peruskoulutus skip bad" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setSuorituksetForPeruskoulu(List(("BI", "4"), ("PS", "5"), ("IHME JA KUMMA", "10"), ("TE", "11")))
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = "010101-0101", syntymäaika = None, etunimet = "Test", kutsumanimi = "Test", sukunimi = "Tester"))
        .build
    ) should contain theSameElementsAs Seq((VirallinenSuoritus(Oids.perusopetusKomoOid,
      "orgId",
      "VALMIS",
      parseLocalDate("2016-02-02"),
      "henkilo_oid",
      yksilollistaminen.Ei,
      "FI",
      None,
      true,
      OrganisaatioOids.oph,
      Map.empty), Seq(
        Arvosana(suoritus = null, arvio = Arvio410("4"), "BI", lisatieto = None, valinnainen = false, myonnetty = None, source = "henkilo_oid", Map()),
        Arvosana(suoritus = null, arvio = Arvio410("5"), "PS", lisatieto = None, valinnainen = false, myonnetty = None, source = "henkilo_oid", Map())
    )))
  }

  it should "list should return suoritus kymppiluokka" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setSuorituksetForKymppi(List(("KT", "10"), ("KE", "8")))
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = "010101-0101", syntymäaika = None, etunimet = "Test", kutsumanimi = "Test", sukunimi = "Tester"))
        .build
    ) should contain theSameElementsAs Seq((VirallinenSuoritus(Oids.lisaopetusKomoOid,
      "orgId",
      "VALMIS",
      parseLocalDate("2016-02-02"),
      "henkilo_oid",
      yksilollistaminen.Ei,
      "FI",
      None,
      true,
      OrganisaatioOids.oph,
      Map.empty), Seq(
        Arvosana(suoritus = null, arvio = Arvio410("10"), "KT", lisatieto = None, valinnainen = false, myonnetty = None, source = "henkilo_oid", Map()),
        Arvosana(suoritus = null, arvio = Arvio410("8"), "KE", lisatieto = None, valinnainen = false, myonnetty = None, source = "henkilo_oid", Map())
    )))
  }

  /*
 "KO", "FI", "YH", "TE", "KS", "FY", "GE", "LI", "KU", "MA", "YL", "OP"

*/


  object HenkiloContainer {
    def apply(): HenkiloContainerBuilder = HenkiloContainerBuilder(KoskiHenkilo(None, "", None, "", "", ""), Seq(), "")
  }

  case class HenkiloContainerBuilder(
                            koskiHenkilo: KoskiHenkilo,
                            suoritukset: Seq[KoskiSuoritus],
                            orgId: String) {

    var dummyKoulutusmoduuli = KoskiKoulutusmoduuli(
      tunniste = None,
      kieli = None,
      koulutustyyppi = None)

    var organisaatio = KoskiOrganisaatio("orgId")

    var arvosana9 = KoskiArviointi(arvosana = KoskiKoodi(koodiarvo = "9", koodistoUri = ""), hyväksytty = Some(true))

    // suomen kielinen kielisuoritus
    var suomenkieliKoulutusmoduuli = KoskiKoulutusmoduuli(
      tunniste = Some(KoskiKoodi("A1", "uri")),
      kieli = Some(KoskiKieli("FI", "")),
      koulutustyyppi = None)

    var kieliOsasuoritus = KoskiOsasuoritus(
      koulutusmoduuli = suomenkieliKoulutusmoduuli,
      tyyppi = KoskiKoodi("", ""),
      arviointi = Seq(arvosana9),
      pakollinen = Some(true),
      yksilöllistettyOppimäärä = Some(false)
    )

    def getOsasuoritus(aine: String, arvosana: String): KoskiOsasuoritus = {
      KoskiOsasuoritus(
        koulutusmoduuli = getKoulutusModuuli(aine),
        tyyppi = KoskiKoodi("", ""),
        arviointi = Seq(getArvosana(arvosana)),
        pakollinen = Some(true),
        yksilöllistettyOppimäärä = Some(false)
      )
    }

    def getKoulutusModuuli(aine: String): KoskiKoulutusmoduuli = {
      KoskiKoulutusmoduuli(
        tunniste = Some(KoskiKoodi(aine, "uri")),
        kieli = None,
        koulutustyyppi = None)
    }

    def getArvosana(arvosana: String): KoskiArviointi = {
      KoskiArviointi(arvosana = KoskiKoodi(koodiarvo = arvosana, koodistoUri = "arviointiasteikkoyleissivistava"), hyväksytty = None)
    }

    def getPeruskouluSuoritus(osasuoritus: Seq[KoskiOsasuoritus]): KoskiSuoritus = {
      KoskiSuoritus(
        luokka = Some(""),
        koulutusmoduuli = dummyKoulutusmoduuli,
        tyyppi = Some (KoskiKoodi (Oids.perusopetusKomoOid, "") ),
        kieli = None,
        pakollinen = None,
        toimipiste = Some (KoskiOrganisaatio ("orgOid") ),
        vahvistus = Some (KoskiVahvistus (päivä = "2016-02-02", myöntäjäOrganisaatio = organisaatio) ),
        suorituskieli = None, // default FI tai sitten Muuta
        arviointi = None,
        yksilöllistettyOppimäärä = None,
        osasuoritukset = osasuoritus,
        ryhmä = None
      )
    }

    def getKymppiSuoritus(osasuoritus: Seq[KoskiOsasuoritus]): KoskiSuoritus = {
      KoskiSuoritus(
        luokka = Some(""),
        koulutusmoduuli = dummyKoulutusmoduuli,
        tyyppi = Some (KoskiKoodi (Oids.lisaopetusKomoOid, "") ),
        kieli = None,
        pakollinen = None,
        toimipiste = Some (KoskiOrganisaatio ("orgOid") ),
        vahvistus = Some (KoskiVahvistus (päivä = "2016-02-02", myöntäjäOrganisaatio = organisaatio) ),
        suorituskieli = None, // default FI tai sitten Muuta
        arviointi = None,
        yksilöllistettyOppimäärä = None,
        osasuoritukset = osasuoritus,
        ryhmä = None
      )
    }

    def setHenkilo(henkilo: KoskiHenkilo): HenkiloContainerBuilder =
      this.copy(koskiHenkilo = henkilo)


    def setSuorituksetForPeruskoulu(osasuoritukset: List[(String, String)]): HenkiloContainerBuilder = {
      var osasuor = osasuoritukset.map(a => getOsasuoritus(a._1,a._2))
      this.copy(suoritukset = Seq(getPeruskouluSuoritus(osasuor)))
    }

    def setSuorituksetForKymppi(osasuoritukset: List[(String, String)]): HenkiloContainerBuilder = {
      var osasuor = osasuoritukset.map(a => getOsasuoritus(a._1,a._2))
      this.copy(suoritukset = Seq(getKymppiSuoritus(osasuor)))
    }

    def setPeruskouluKieli(): HenkiloContainerBuilder =
      this.copy(suoritukset = Seq(getPeruskouluSuoritus(Seq(kieliOsasuoritus))))

    def build: KoskiHenkiloContainer =
      KoskiHenkiloContainer(
        koskiHenkilo,
        Seq(KoskiOpiskeluoikeus(
          oid = "",
          oppilaitos = KoskiOrganisaatio(orgId),
          tila = KoskiOpiskeluoikeusjakso(
                  opiskeluoikeusjaksot = Seq(KoskiTila(alku = "2016-01-01", tila = KoskiKoodi(koodiarvo = "valmistunut", koodistoUri = "uri")))
          ),
          suoritukset = suoritukset
        ))
      )
  }

}
