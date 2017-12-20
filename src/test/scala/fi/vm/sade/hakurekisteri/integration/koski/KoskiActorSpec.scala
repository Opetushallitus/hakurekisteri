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
        .setSuorituksetForPeruskoulu(List(("BI", "4"), ("PS", "5"), ("IHME JA KUMMA", "10"), ("", "11")))
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


  /*
, "BI", "PS", "KT", "KO", "FI", "KE", "YH", "TE", "KS", "FY", "GE", "LI", "KU", "MA", "YL", "OP"

(ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", 1988, "FI"),
        Seq(Arvosana(suoritus = null, arvio = Arvio410("S"), "MA", lisatieto = None, valinnainen = true, myonnetty = None, source = "person1", Map(), Some(1))))


 KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      Suoritus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("PK_MA_VAL1","NOT S")
        .build
    ) should contain theSameElementsAs Seq(
      (ItseilmoitettuPeruskouluTutkinto("hakemus1","person1", 1988, "FI"),
        Seq()))

    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      Suoritus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setPerusopetuksenPaattotodistusvuosi(None)
        //.putArvosana("PK_PAATTOTODISTUSVUOSI","")
        .build
    ) should contain theSameElementsAs Seq()
    KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(
      Suoritus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLukionPaattotodistusvuosi(None)
        //.putArvosana("PK_PAATTOTODISTUSVUOSI","")
        .build
    ) should contain theSameElementsAs Seq()


  it should "split arvosanat correctly in RicherOsaaminen" in {
    RicherOsaaminen(Map("roskaa" -> "1")).groupByKomoAndGroupByAine should be (empty)


    RicherOsaaminen(Map(
      "LK_AI" -> "8",
      "LK_AI_OPPIAINE" -> "FI"
    )).groupByKomoAndGroupByAine should be (Map("LK" -> Map("AI" -> Map("" -> "8", "OPPIAINE" -> "FI"))))

    RicherOsaaminen(Map(
      "LK_AI_" -> "ROSKAA",
      "_" -> "ROSKAA",
      "LK_AI_OPPIAINE" -> "4"
    )).groupByKomoAndGroupByAine should be (empty)


    val r = RicherOsaaminen(Map(
      "LK_AI" -> "8",
      "LK_AI_OPPIAINE" -> "FI"
    )).groupByKomoAndGroupByAine


    r("LK")("AI")("") should be ("8")
    r("LK")("AI")("OPPIAINE") should be ("FI")

  }

  it should "include valinnaiset arvosanat" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("PK_MA","8")
        .putArvosana("PK_MA_VAL1","6")
        .putArvosana("PK_MA_VAL2","5")
        .putArvosana("PK_MA_VAL3","7")
        .build
    ) should contain theSameElementsAs Seq(
      (ItseilmoitettuPeruskouluTutkinto("hakemus1","person1", 1988, "FI"),
        Seq(
          Arvosana(suoritus = null, arvio = Arvio410("6"), "MA", lisatieto = None, valinnainen = true, myonnetty = None, source = "person1", Map(), Some(1)),
          Arvosana(suoritus = null, arvio = Arvio410("5"), "MA", lisatieto = None, valinnainen = true, myonnetty = None, source = "person1", Map(), Some(2)),
          Arvosana(suoritus = null, arvio = Arvio410("7"), "MA", lisatieto = None, valinnainen = true, myonnetty = None, source = "person1", Map(), Some(3)),
          Arvosana(suoritus = null, arvio = Arvio410("8"), "MA", lisatieto = None, valinnainen = false, myonnetty = None, source = "person1", Map()))
        ))
  }

  it should "create suorituksia from koulutustausta" in {
    val a = IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(2000)
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("LK_MA","8")
        .build
    )
    a should contain theSameElementsAs Seq(
      (ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", 1988, "FI"),Seq.empty),
      (ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", 2000, "FI"), Seq(Arvosana(suoritus = null, arvio = Arvio410("8"), "MA", lisatieto = None, valinnainen = false, myonnetty = None, source = "person1", Map())))
    )
  }

  it should "not create perusopetus suorituksia from koulutustausta if application current year" in {
    val currentYear = new DateTime().year().get()
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setPerusopetuksenPaattotodistusvuosi(currentYear)
        .build
    ) should equal(Seq.empty)
  }

  it should "create lukio if valmistuminen in current year and lahtokoulu is given" in {
    val currentYear = new DateTime().year().get()
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(currentYear)
        .build
    ) should equal(Seq((ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", currentYear, "FI"), Seq())))
  }

  it should "not create lukio if valmistuminen in current year and lahtokoulu not given" in {
    val currentYear = new DateTime().year().get()
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLukionPaattotodistusvuosi(currentYear)
        .build
    ) should equal(Seq.empty)
  }

  it should "create lukio is valmistuminen not in current year" in {
    val currentYear = new DateTime().year().get()
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(currentYear - 1)
        .build
    ) should equal(Seq((ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", currentYear - 1, "FI"), Seq())))
  }

  it should "handle 'ei arvosanaa'" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(2000)
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("LK_MA","Ei arvosanaa")
        .build
    ) should contain theSameElementsAs Seq(
      (ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", 2000, "FI"), Seq()),
      (ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", 1988, "FI"), Seq())
    )
  }

  it should "create suorituksia ja arvosanoja from oppimiset" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(2000)
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("LK_MA","8")
        .putArvosana("PK_AI","7")
        .build
    ) should contain theSameElementsAs Seq(
      (ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", 2000, "FI"),
        Seq(Arvosana(suoritus = null, arvio = Arvio410("8"), "MA", lisatieto = None, valinnainen = false, myonnetty = None, source = "person1", Map()))),
      (ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", 1988, "FI"),
        Seq(Arvosana(suoritus = null, arvio = Arvio410("7"), "AI", lisatieto = None, valinnainen = false, myonnetty = None, source = "person1", Map()))
        ))
    // FIXME: PK+LK combination not possible with the current application logic
  }

  //
  it should "handle arvosana special cases" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(2000)
        .putArvosana("LK_AI", "8")
        .putArvosana("LK_AI_OPPIAINE", "FI")
        .putArvosana("LK_B1", "7")
        .putArvosana("LK_MA", "")
        .putArvosana("LK_B1_OPPIAINE", "SV")
        .putArvosana("LK_", "ROSKAA")
        .putArvosana("LK_SA_SDF", "ROSKAA")
        .putArvosana("LK_SA_SDF_ASDF_ASDF_ASDF_ASDF", "ROSKAA")
        .build
    ) should contain theSameElementsAs Seq(
      (ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", 2000, "FI"),
        Seq(
          Arvosana(suoritus = null, arvio = Arvio410("7"), "B1", lisatieto = Some("SV"), valinnainen = false, myonnetty = None, source = "person1", Map()),
          Arvosana(suoritus = null, arvio = Arvio410("8"), "AI", lisatieto = Some("FI"), valinnainen = false, myonnetty = None, source = "person1", Map())
        )))
  }

  it should "create empty lukiosuoritus for current year" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(new LocalDate().getYear)
        .build
    ) should contain theSameElementsAs Seq( (ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", new LocalDate().getYear, "FI"), Seq()) )
  }

  it should "create kymppisuoritus with the year entered in the application" in {
    val pkVuosi = 2009
    val kymppiVuosi = 2010
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setPerusopetuksenPaattotodistusvuosi(pkVuosi)
        .setLisaopetusKymppi("true")
        .setKymppiVuosi(kymppiVuosi)
        .build
    ) should contain theSameElementsAs Seq(
      (ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", pkVuosi, "FI"), List()),
      (ItseilmoitettuTutkinto(Oids.lisaopetusKomoOid, "hakemus1", "person1", kymppiVuosi, "FI"), List())
    )
  }

  it should "create kymppisuoritus with perusopetus year if kymppi year not available" in {
    val pkVuosi = 2009
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setPerusopetuksenPaattotodistusvuosi(pkVuosi)
        .setLisaopetusKymppi("true")
        .build
    ) should contain theSameElementsAs Seq(
      (ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", pkVuosi, "FI"), List()),
      (ItseilmoitettuTutkinto(Oids.lisaopetusKomoOid, "hakemus1", "person1", pkVuosi, "FI"), List())
    )
  }

  it should "create suoritus and arvosanat only once" in {
    val waiterTimeout = timeout(5.seconds)
    val suoritusWaiter = new Waiter()
    val suoritusQueryWaiter = new Waiter()
    val bypassWaiter = new Waiter()
    val arvosanaWaiter = new Waiter()
    implicit val system = ActorSystem("only-once-system")
    val suoritusRekisteri = TestActorRef(new Actor {
      var suoritukset: Seq[Suoritus with Identified[UUID]] = Seq()
      override def receive: Receive = {
        case i: InsertResource[_, _] if i.resource.isInstanceOf[Suoritus] =>
          val identified = i.resource.asInstanceOf[Suoritus].identify(UUID.randomUUID())
          suoritukset = suoritukset :+ identified
          sender ! identified
          suoritusWaiter.dismiss()
        case q@SuoritusQuery(Some(henkilo), _, _, _, _, _) =>
          sender ! suoritukset.filter(_.henkiloOid == henkilo)
          suoritusQueryWaiter.dismiss()
        case q@SuoritusQueryWithPersonAliases(SuoritusQuery(Some(henkilo), _, _, _, _, _), _) =>
          sender ! suoritukset.filter(_.henkiloOid == henkilo)
          suoritusQueryWaiter.dismiss()
        case LogMessage(_, Logging.DebugLevel) =>
          bypassWaiter.dismiss()
      }
    })
    val arvosanaRekisteri = TestActorRef(new Actor {
      override def receive: Receive = {
        case i: InsertResource[_, _] if i.resource.isInstanceOf[Arvosana] =>
          val identified: Arvosana with Identified[UUID] = i.resource.asInstanceOf[Arvosana].identify(UUID.randomUUID())
          sender ! identified
          arvosanaWaiter.dismiss()
      }
    })

    val hakemus = Hakemus()
      .setHakemusOid("hakemus1")
      .setPersonOid("person1")
      .setLahtokoulu("foobarKoulu")
      .setPerusopetuksenPaattotodistusvuosi(1988)
      .putArvosana("PK_MA","8")
      .build

    IlmoitetutArvosanatTrigger.muodostaSuorituksetJaArvosanat(hakemus, suoritusRekisteri, arvosanaRekisteri, PersonOidsWithAliases(hakemus.personOid.toSet), logBypassed = true)

    suoritusQueryWaiter.await(waiterTimeout, dismissals(1))
    suoritusWaiter.await(waiterTimeout, dismissals(1))
    arvosanaWaiter.await(waiterTimeout, dismissals(1))
    bypassWaiter.await(waiterTimeout, dismissals(0))

    IlmoitetutArvosanatTrigger.muodostaSuorituksetJaArvosanat(hakemus, suoritusRekisteri, arvosanaRekisteri, PersonOidsWithAliases(hakemus.personOid.toSet), logBypassed = true)

    suoritusQueryWaiter.await(waiterTimeout, dismissals(1))
    bypassWaiter.await(waiterTimeout, dismissals(1))

    suoritusRekisteri.underlyingActor.suoritukset.size should be (1)
  }

  trait CustomMatchers {

    class ArvosanatMatcher(expectedArvosanat: Seq[Arvosana]) extends Matcher[Seq[Arvosana]] {

      def apply(left: Seq[Arvosana]) = {
        MatchResult(
          if(left.isEmpty) false else left.map(l => expectedArvosanat.exists(p => l.arvio.equals(p.arvio) && l.lisatieto.equals(p.lisatieto) )).reduceLeft(_ && _),
          s"""Arvosanat\n ${left.toList}\nwas not expected\n $expectedArvosanat""",
          s"""Arvosanat\n ${stripId(left).toList}\nwas expected\n $expectedArvosanat"""
        )
      }

      private def stripId(l: Seq[Arvosana]) = {
        l.map(l0 => {
          l0.copy(suoritus=null)
        })
      }
    }

    def containArvosanat(expectedExtension: Seq[Arvosana]) = new ArvosanatMatcher(expectedExtension)
  }
  object CustomMatchers extends CustomMatchers

}



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
        koulutusmoduuli = dummyKoulutusmoduuli,
        tyyppi = Some (KoskiKoodi (Oids.perusopetusKomoOid, "") ),
        kieli = None,
        pakollinen = None,
        toimipiste = Some (KoskiOrganisaatio ("orgOid") ),
        vahvistus = Some (KoskiVahvistus (päivä = "2016-02-02", myöntäjäOrganisaatio = organisaatio) ),
        suorituskieli = None, // default FI tai sitten Muuta
        arviointi = None,
        yksilöllistettyOppimäärä = None,
        osasuoritukset = osasuoritus
      )
    }

    def getKymppiSuoritus(osasuoritus: Seq[KoskiOsasuoritus]): KoskiSuoritus = {
      KoskiSuoritus(
        koulutusmoduuli = dummyKoulutusmoduuli,
        tyyppi = Some (KoskiKoodi (Oids.lisaopetusKomoOid, "") ),
        kieli = None,
        pakollinen = None,
        toimipiste = Some (KoskiOrganisaatio ("orgOid") ),
        vahvistus = Some (KoskiVahvistus (päivä = "2016-02-02", myöntäjäOrganisaatio = organisaatio) ),
        suorituskieli = None, // default FI tai sitten Muuta
        arviointi = None,
        yksilöllistettyOppimäärä = None,
        osasuoritukset = osasuoritus
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
