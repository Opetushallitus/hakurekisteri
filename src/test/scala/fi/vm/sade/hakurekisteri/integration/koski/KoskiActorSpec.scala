package fi.vm.sade.hakurekisteri.integration.koski

import java.util.UUID

import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.koski.KoskiArvosanaTrigger.parseLocalDate
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.hakurekisteri.{Oids, OrganisaatioOids, SpecsLikeMockito}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import org.joda.time.{DateTime, LocalDate}
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
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = Some("010101-0101"), syntymäaika = None, etunimet = Some("Test"), kutsumanimi = Some("Test"), sukunimi = Some("Tester")))
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
      Map.empty), Seq(Arvosana( suoritus = null, arvio = Arvio410("9"), "A1", lisatieto = Some("FI"), valinnainen = false, myonnetty = None, source = "henkilo_oid", Map())), "", parseLocalDate("2016-02-02"), None
    ))
  }


  it should "list should return peruskoulutus with arvosanat" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setSuorituksetForPeruskoulu(List(("HI", Some("9"), false), ("MU", Some("8"), false)))
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = Some("010101-0101"), syntymäaika = None, etunimet = Some("Test"), kutsumanimi = Some("Test"), sukunimi = Some("Tester")))
        .setLuokka("9E")
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
    ), "", parseLocalDate("2016-02-02"), None),
      (VirallinenSuoritus("luokka", "orgId", "VALMIS", parseLocalDate("2016-02-02"), "henkilo_oid", yksilollistaminen.Ei, "FI", None, true, OrganisaatioOids.oph, Map.empty), Seq(
        Arvosana(null, Arvio410("9"), "MA", lisatieto = None, false, None, "henkilo_oid",Map())
      ), "9E", parseLocalDate("2016-02-02"), None))
  }

  it should "alkamispaiva should be from alkamispaiva" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setSuorituksetForPeruskoulu(List(("HI", Some("9"), false), ("MU", Some("8"), false)))
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = Some("010101-0101"), syntymäaika = None, etunimet = Some("Test"), kutsumanimi = Some("Test"), sukunimi = Some("Tester")))
        .setLuokka("9A", Some("2017-03-03"))
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
    ), "", parseLocalDate("2016-02-02"), None),
      (VirallinenSuoritus("luokka", "orgId", "VALMIS", parseLocalDate("2016-02-02"), "henkilo_oid", yksilollistaminen.Ei, "FI", None, true, OrganisaatioOids.oph, Map.empty), Seq(
        Arvosana(null, Arvio410("9"), "MA", lisatieto = None, false, None, "henkilo_oid",Map())
      ), "9A", parseLocalDate("2017-03-03"), None))
  }


  it should "list should return peruskoulutus skip bad" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setSuorituksetForPeruskoulu(List(("BI", Some("4"), false), ("PS", Some("5"), false), ("IHME JA KUMMA", Some("10"), false), ("TE", Some("11"), false)))
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = Some("010101-0101"), syntymäaika = None, etunimet = Some("Test"), kutsumanimi = Some("Test"), sukunimi = Some("Tester")))
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
    ), "", parseLocalDate("2016-02-02"), None))
  }

  it should "Suoritus should be osittain yksilöllistetty" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setSuorituksetForPeruskoulu(List(("BI", Some("4"), false), ("PS", None, true)))
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = Some("010101-0101"), syntymäaika = None, etunimet = Some("Test"), kutsumanimi = Some("Test"), sukunimi = Some("Tester")))
        .build
    ) should contain theSameElementsAs Seq((VirallinenSuoritus(Oids.perusopetusKomoOid,
      "orgId",
      "VALMIS",
      parseLocalDate("2016-02-02"),
      "henkilo_oid",
      yksilollistaminen.Osittain,
      "FI",
      None,
      true,
      OrganisaatioOids.oph,
      Map.empty), Seq(
        Arvosana(suoritus = null, arvio = Arvio410("4"), "BI", lisatieto = None, valinnainen = false, myonnetty = None, source = "henkilo_oid", Map())
    ), "", parseLocalDate("2016-02-02"), None))
  }

  it should "Kesken should set enddate as next fourth of june" in {

    val nextFourthOfJune = KoskiArvosanaTrigger.parseNextFourthOfJune()

    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setSuorituksetForPeruskoulu(List(("BI", Some("4"), false), ("PS", None, true)))
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = Some("010101-0101"), syntymäaika = None, etunimet = Some("Test"), kutsumanimi = Some("Test"), sukunimi = Some("Tester")))
        .setOpiskeluOikeusJakso(Seq(KoskiTila(alku = "2016-01-01", tila = KoskiKoodi(koodiarvo = "kesken", koodistoUri = "uri"))))
    ) should contain theSameElementsAs Seq((VirallinenSuoritus(Oids.perusopetusKomoOid,
      "orgId",
      "KESKEN",
      nextFourthOfJune,
      "henkilo_oid",
      yksilollistaminen.Osittain,
      "FI",
      None,
      true,
      OrganisaatioOids.oph,
      Map.empty), Seq(
        Arvosana(suoritus = null, arvio = Arvio410("4"), "BI", lisatieto = None, valinnainen = false, myonnetty = None, source = "henkilo_oid", Map())
    ), "", parseLocalDate("2016-02-02"), None))
  }

  it should "Suoritus should be kokonaan yksilöllistetty" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setSuorituksetForPeruskoulu(List(("YL", Some("4"), true), ("OP", None, true), ("DDR", None, false)))
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = Some("010101-0101"), syntymäaika = None, etunimet = Some("Test"), kutsumanimi = Some("Test"), sukunimi = Some("Tester")))
        .build
    ) should contain theSameElementsAs Seq((VirallinenSuoritus(Oids.perusopetusKomoOid,
      "orgId",
      "VALMIS",
      parseLocalDate("2016-02-02"),
      "henkilo_oid",
      yksilollistaminen.Kokonaan,
      "FI",
      None,
      true,
      OrganisaatioOids.oph,
      Map.empty), Seq(
        Arvosana(suoritus = null, arvio = Arvio410("4"), "YL", lisatieto = None, valinnainen = false, myonnetty = None, source = "henkilo_oid", Map())
    ), "", parseLocalDate("2016-02-02"), None))
  }

  it should "list should return suoritus kymppiluokka" in {
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      HenkiloContainer()
        .setSuorituksetForKymppi(List(("KT", Some("10")), ("KE", Some("8"))))
        .setHenkilo(KoskiHenkilo(oid = Some("henkilo_oid"), hetu = Some("010101-0101"), syntymäaika = None, etunimet = Some("Test"), kutsumanimi = Some("Test"), sukunimi = Some("Tester")))
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
    ), "", parseLocalDate("2016-02-02"), None))
  }

  it should "detectOppilaitos should return 10 as luokka for peruskoulun lisäopetus" in {
    KoskiArvosanaTrigger.detectOppilaitos(
      SuoritusLuokka(VirallinenSuoritus(Oids.lisaopetusKomoOid, "orgId", "VALMIS", parseLocalDate("2017-01-01"), "henkilo_oid",
        yksilollistaminen.Ei, "FI", None, true, OrganisaatioOids.oph, Map.empty), "", parseLocalDate("2017-01-01"))
    ) should equal ("10", "orgId", "10")
  }

  it should "detectOppilaitos should return luokka for peruskoulun lisäopetus if not empty" in {
    KoskiArvosanaTrigger.detectOppilaitos(
      SuoritusLuokka(VirallinenSuoritus(Oids.lisaopetusKomoOid, "orgId", "VALMIS", parseLocalDate("2017-01-01"), "henkilo_oid",
        yksilollistaminen.Ei, "FI", None, true, OrganisaatioOids.oph, Map.empty), "10C", parseLocalDate("2017-01-01"))
    ) should equal ("10", "orgId", "10C")
  }

  it should "createOpiskelija should create opiskelija" in {
    KoskiArvosanaTrigger.createOpiskelija("henkilo_oid",
      SuoritusLuokka(VirallinenSuoritus(Oids.perusopetusKomoOid, "orgId", "VALMIS", parseLocalDate("2017-01-01"), "henkilo_oid",
        yksilollistaminen.Ei, "FI", None, true, OrganisaatioOids.oph, Map.empty), "9F", parseLocalDate("2016-01-01"), Some("9"))
      ) should equal (
      Opiskelija("orgId", "9", "9F", "henkilo_oid", DateTime.parse("2016-01-01"), Some(DateTime.parse("2017-01-01")), "koski")
    )
  }

  object HenkiloContainer {
    def apply(): HenkiloContainerBuilder = HenkiloContainerBuilder(KoskiHenkilo(None, Some(""), None, Some(""), Some(""), Some("")), Seq(), "")
  }

  case class HenkiloContainerBuilder(
                            koskiHenkilo: KoskiHenkilo,
                            suoritukset: Seq[KoskiSuoritus],
                            orgId: String) {

    var dummyKoulutusmoduuli = KoskiKoulutusmoduuli(
      tunniste = None,
      kieli = None,
      koulutustyyppi = None,
      laajuus = None)

    var organisaatio = KoskiOrganisaatio("orgId")

    var arvosana9 = KoskiArviointi(arvosana = KoskiKoodi(koodiarvo = "9", koodistoUri = ""), hyväksytty = Some(true))

    var opiskeluOikeusJakso = KoskiOpiskeluoikeusjakso(opiskeluoikeusjaksot = Seq(KoskiTila(alku = "2016-01-01", tila = KoskiKoodi(koodiarvo = "valmistunut", koodistoUri = "uri"))))

    // suomen kielinen kielisuoritus
    var suomenkieliKoulutusmoduuli = KoskiKoulutusmoduuli(
      tunniste = Some(KoskiKoodi("A1", "uri")),
      kieli = Some(KoskiKieli("FI", "")),
      koulutustyyppi = None,
      laajuus = None)

    var kieliOsasuoritus = KoskiOsasuoritus(
      koulutusmoduuli = suomenkieliKoulutusmoduuli,
      tyyppi = KoskiKoodi("", ""),
      arviointi = Seq(arvosana9),
      pakollinen = Some(true),
      yksilöllistettyOppimäärä = Some(false)
    )

    def setOpiskeluOikeusJakso(tilat: Seq[KoskiTila]) = {
      opiskeluOikeusJakso = opiskeluOikeusJakso.copy(opiskeluoikeusjaksot = tilat)
      this.build
    }

    def getOpiskeluOikeusJakso: KoskiOpiskeluoikeusjakso = {
      opiskeluOikeusJakso
    }

    def getOsasuoritus(aine: String, arvosana: Option[String], yksilollistetty: Boolean = false): KoskiOsasuoritus = {
      val arv = arvosana match {
        case Some(s) => Seq(getArvosana(s))
        case None => Seq()
      }
      KoskiOsasuoritus(
        koulutusmoduuli = getKoulutusModuuli(aine),
        tyyppi = KoskiKoodi("", ""),
        arviointi = arv,
        pakollinen = Some(true),
        yksilöllistettyOppimäärä = Some(yksilollistetty)
      )
    }

    def getKoulutusModuuli(aine: String): KoskiKoulutusmoduuli = {
      KoskiKoulutusmoduuli(
        tunniste = Some(KoskiKoodi(aine, "uri")),
        kieli = None,
        koulutustyyppi = None,
        laajuus = None)
    }

    def setLuokka(luokka: String, alkamisPaiva: Option[String] = None): HenkiloContainerBuilder = {
      var uudetSuoritukset = suoritukset :+ KoskiSuoritus(
        luokka = Some(luokka),
        koulutusmoduuli = dummyKoulutusmoduuli,
        tyyppi = Some (KoskiKoodi ("perusopetuksenvuosiluokka", "") ),
        kieli = None,
        pakollinen = None,
        toimipiste = Some (KoskiOrganisaatio ("orgOid") ),
        vahvistus = Some (KoskiVahvistus (päivä = "2016-02-02", myöntäjäOrganisaatio = organisaatio) ),
        suorituskieli = None, // default FI tai sitten Muuta
        arviointi = None,
        yksilöllistettyOppimäärä = None,
        osasuoritukset = Seq(getOsasuoritus("MA", Some("9"))),
        ryhmä = None,
        alkamispäivä = alkamisPaiva
      )
      this.copy(suoritukset = uudetSuoritukset)
    }

    def getArvosana(arvosana: String): KoskiArviointi = {
      KoskiArviointi(arvosana = KoskiKoodi(koodiarvo = arvosana, koodistoUri = "arviointiasteikkoyleissivistava"), hyväksytty = None)
    }

    def getPeruskouluSuoritus(osasuoritus: Seq[KoskiOsasuoritus]): KoskiSuoritus = {
      KoskiSuoritus(
        luokka = Some(""),
        koulutusmoduuli = dummyKoulutusmoduuli,
        tyyppi = Some (KoskiKoodi ("perusopetuksenoppimaara", "") ),
        kieli = None,
        pakollinen = None,
        toimipiste = Some (KoskiOrganisaatio ("orgOid") ),
        vahvistus = Some (KoskiVahvistus (päivä = "2016-02-02", myöntäjäOrganisaatio = organisaatio) ),
        suorituskieli = None, // default FI tai sitten Muuta
        arviointi = None,
        yksilöllistettyOppimäärä = None,
        osasuoritukset = osasuoritus,
        ryhmä = None,
        alkamispäivä = None
      )
    }

    def getKymppiSuoritus(osasuoritus: Seq[KoskiOsasuoritus]): KoskiSuoritus = {
      KoskiSuoritus(
        luokka = Some(""),
        koulutusmoduuli = dummyKoulutusmoduuli,
        tyyppi = Some (KoskiKoodi ("perusopetuksenlisaopetus", "") ),
        kieli = None,
        pakollinen = None,
        toimipiste = Some (KoskiOrganisaatio ("orgOid") ),
        vahvistus = Some (KoskiVahvistus (päivä = "2016-02-02", myöntäjäOrganisaatio = organisaatio) ),
        suorituskieli = None, // default FI tai sitten Muuta
        arviointi = None,
        yksilöllistettyOppimäärä = None,
        osasuoritukset = osasuoritus,
        ryhmä = None,
        alkamispäivä = None
      )
    }

    def setHenkilo(henkilo: KoskiHenkilo): HenkiloContainerBuilder =
      this.copy(koskiHenkilo = henkilo)


    def setSuorituksetForPeruskoulu(osasuoritukset: List[(String, Option[String], Boolean)]): HenkiloContainerBuilder = {
      var osasuor = osasuoritukset.map(a => getOsasuoritus(a._1,a._2,a._3))
      this.copy(suoritukset = Seq(getPeruskouluSuoritus(osasuor)))
    }

    def setSuorituksetForKymppi(osasuoritukset: List[(String, Option[String])]): HenkiloContainerBuilder = {
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
          tila = this.getOpiskeluOikeusJakso,
          lisätiedot = Option.empty,
          suoritukset = suoritukset
        ))
      )
  }

}
