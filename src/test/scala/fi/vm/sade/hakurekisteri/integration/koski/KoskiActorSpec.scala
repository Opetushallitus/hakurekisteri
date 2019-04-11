package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.{Actor, ActorSystem}
import akka.testkit.TestActorRef
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.koski.KoskiDataHandler._
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.suoritus.{VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.hakurekisteri.{Oids, OrganisaatioOids, SpecsLikeMockito}
import org.joda.time.DateTime
import org.json4s._
import org.scalatest.concurrent.Waiters
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.Seq
import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.{implicitConversions, reflectiveCalls}

/**
  * Doesn't actually seem to test any "actor"?
  *
  * More tests at {@link fi.vm.sade.hakurekisteri.integration.koski.koskiDatahandlerTest}
  */
class KoskiActorSpec extends FlatSpec with Matchers with FutureWaiting with SpecsLikeMockito with Waiters
  with MockitoSugar with DispatchSupport with ActorSystemSupport with LocalhostProperties {

  implicit val formats = DefaultFormats
  implicit val timeout: Timeout = 5.second
  val koskiConfig = ServiceConfig(serviceUrl = "http://localhost/koski/api/oppija")
  implicit val system = ActorSystem(s"test-system-${Platform.currentTime.toString}")
  implicit def executor: ExecutionContext = system.dispatcher
  val testRef = TestActorRef(new Actor {
    override def receive: Actor.Receive = {
      case q =>
        sender ! Seq()
    }
  })
  val koskiDataHandler: KoskiDataHandler = new KoskiDataHandler(testRef, testRef, testRef)
  val opiskelijaParser = new KoskiOpiskelijaParser
  val params: KoskiSuoritusHakuParams = new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)

  it should "empty KoskiHenkilo should throw NoSuchElementException" in {
    try {
      koskiDataHandler.createSuorituksetJaArvosanatFromKoski(
        HenkiloContainer().build
      ).flatten
    } catch {
      case ex: NoSuchElementException => //Expected
    }
  }

  it should "createOpiskelija should create opiskelija with 10 as luokka for peruskoulun lisäopetus" in {
    opiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876",
      SuoritusLuokka(VirallinenSuoritus(Oids.lisaopetusKomoOid, "orgId", "VALMIS", parseLocalDate("2017-01-01"), "henkilo_oid",
        yksilollistaminen.Ei, "FI", None, true, OrganisaatioOids.oph, None, Map.empty), "", parseLocalDate("2016-01-01"))
    ) should equal (
      Opiskelija("orgId", "10", "10", "1.2.246.562.24.80710434876", DateTime.parse("2016-01-01"), Some(DateTime.parse("2017-01-01")), "koski")
      )
  }

  it should "createOpiskelija should create opiskelija luokka for peruskoulun lisäopetus if not empty" in {
    opiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876",
      SuoritusLuokka(VirallinenSuoritus(Oids.lisaopetusKomoOid, "orgId", "VALMIS", parseLocalDate("2017-01-01"), "henkilo_oid",
        yksilollistaminen.Ei, "FI", None, true, OrganisaatioOids.oph, None, Map.empty), "10C", parseLocalDate("2016-01-01"))
    ) should equal (Opiskelija("orgId", "10", "10C", "1.2.246.562.24.80710434876", DateTime.parse("2016-01-01"), Some(DateTime.parse("2017-01-01")), "koski")
    )
  }

  it should "createOpiskelija should create opiskelija" in {
    opiskelijaParser.createOpiskelija("henkilo_oid",
      SuoritusLuokka(VirallinenSuoritus(Oids.perusopetusKomoOid, "orgId", "VALMIS", parseLocalDate("2017-01-01"), "henkilo_oid",
        yksilollistaminen.Ei, "FI", None, true, OrganisaatioOids.oph, None, Map.empty), "9F", parseLocalDate("2016-01-01"), Some("9"))
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
      laajuus = None,
      pakollinen = None)

    var organisaatio = KoskiOrganisaatio(Some("orgId"))

    var arvosana9 = KoskiArviointi(arvosana = KoskiKoodi(koodiarvo = "9", koodistoUri = ""), hyväksytty = Some(true), päivä = Some("2018-05-28"))

    var opiskeluOikeusJakso = KoskiOpiskeluoikeusjakso(opiskeluoikeusjaksot = Seq(KoskiTila(alku = "2016-01-01", tila = KoskiKoodi(koodiarvo = "valmistunut", koodistoUri = "uri"))))

    // suomen kielinen kielisuoritus
    var suomenkieliKoulutusmoduuli = KoskiKoulutusmoduuli(
      tunniste = Some(KoskiKoodi("A1", "uri")),
      kieli = Some(KoskiKieli("FI", "")),
      koulutustyyppi = None,
      laajuus = None,
      pakollinen = None)

    var kieliOsasuoritus = KoskiOsasuoritus(
      koulutusmoduuli = suomenkieliKoulutusmoduuli,
      tyyppi = KoskiKoodi("", ""),
      arviointi = Seq(arvosana9),
      pakollinen = Some(true),
      yksilöllistettyOppimäärä = Some(false),
      osasuoritukset = None
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
        yksilöllistettyOppimäärä = Some(yksilollistetty),
        osasuoritukset = None
      )
    }

    def getKoulutusModuuli(aine: String): KoskiKoulutusmoduuli = {
      KoskiKoulutusmoduuli(
        tunniste = Some(KoskiKoodi(aine, "uri")),
        kieli = None,
        koulutustyyppi = None,
        laajuus = None,
        pakollinen = None)
    }

    def setLuokka(luokka: String, alkamisPaiva: Option[String] = None): HenkiloContainerBuilder = {
      var uudetSuoritukset = suoritukset :+ KoskiSuoritus(
        luokka = Some(luokka),
        koulutusmoduuli = dummyKoulutusmoduuli,
        tyyppi = Some (KoskiKoodi ("perusopetuksenvuosiluokka", "") ),
        kieli = None,
        pakollinen = None,
        toimipiste = Some (KoskiOrganisaatio (Some("orgOid")) ),
        vahvistus = Some (KoskiVahvistus (päivä = "2016-02-02", myöntäjäOrganisaatio = organisaatio) ),
        suorituskieli = None, // default FI tai sitten Muuta
        arviointi = None,
        yksilöllistettyOppimäärä = None,
        osasuoritukset = Seq(getOsasuoritus("MA", Some("9"))),
        ryhmä = None,
        alkamispäivä = alkamisPaiva,
        jääLuokalle = None
      )
      this.copy(suoritukset = uudetSuoritukset)
    }

    def getArvosana(arvosana: String): KoskiArviointi = {
      KoskiArviointi(arvosana = KoskiKoodi(koodiarvo = arvosana, koodistoUri = "arviointiasteikkoyleissivistava"), hyväksytty = None, päivä = Some("2018-05-28"))
    }

    def getPeruskouluSuoritus(osasuoritus: Seq[KoskiOsasuoritus]): KoskiSuoritus = {
      KoskiSuoritus(
        luokka = Some(""),
        koulutusmoduuli = dummyKoulutusmoduuli,
        tyyppi = Some (KoskiKoodi ("perusopetuksenoppimaara", "") ),
        kieli = None,
        pakollinen = None,
        toimipiste = Some (KoskiOrganisaatio (Some("orgOid")) ),
        vahvistus = Some (KoskiVahvistus (päivä = "2016-02-02", myöntäjäOrganisaatio = organisaatio) ),
        suorituskieli = None, // default FI tai sitten Muuta
        arviointi = None,
        yksilöllistettyOppimäärä = None,
        osasuoritukset = osasuoritus,
        ryhmä = None,
        alkamispäivä = None,
        jääLuokalle = None
      )
    }

    def getKymppiSuoritus(osasuoritus: Seq[KoskiOsasuoritus]): KoskiSuoritus = {
      KoskiSuoritus(
        luokka = Some(""),
        koulutusmoduuli = dummyKoulutusmoduuli,
        tyyppi = Some (KoskiKoodi ("perusopetuksenlisaopetus", "") ),
        kieli = None,
        pakollinen = None,
        toimipiste = Some (KoskiOrganisaatio (Some("orgOid")) ),
        vahvistus = Some (KoskiVahvistus (päivä = "2016-02-02", myöntäjäOrganisaatio = organisaatio) ),
        suorituskieli = None, // default FI tai sitten Muuta
        arviointi = None,
        yksilöllistettyOppimäärä = None,
        osasuoritukset = osasuoritus,
        ryhmä = None,
        alkamispäivä = None,
        jääLuokalle = None
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
          oid = Some(""),
          päättymispäivä = Option.empty,
          oppilaitos = Some(KoskiOrganisaatio(Some(orgId))),
          tila = this.getOpiskeluOikeusJakso,
          lisätiedot = Option.empty,
          suoritukset = suoritukset,
          tyyppi = None,
          aikaleima = None
        ))
      )
  }

}
