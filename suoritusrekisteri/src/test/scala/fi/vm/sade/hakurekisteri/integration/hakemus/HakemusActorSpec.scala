package fi.vm.sade.hakurekisteri.integration.hakemus

import java.util.UUID

import akka.actor._
import akka.event.Logging
import akka.testkit.TestActorRef
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.storage.{Identified, InsertResource, LogMessage}
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.hakurekisteri.{Oids, SpecsLikeMockito}
import org.joda.time.{DateTime, LocalDate}
import org.json4s._
import org.scalatest.concurrent.Waiters
import org.scalatest.matchers._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.Seq
import scala.concurrent.duration._
import scala.language.{implicitConversions, reflectiveCalls}

class HakemusActorSpec
    extends FlatSpec
    with Matchers
    with FutureWaiting
    with SpecsLikeMockito
    with Waiters
    with MockitoSugar
    with DispatchSupport
    with ActorSystemSupport
    with LocalhostProperties {

  implicit val formats = DefaultFormats
  implicit val timeout: Timeout = 5.second
  val hakuappConfig = ServiceConfig(serviceUrl = "http://localhost/haku-app")

  it should "include arvosana 'S'" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("PK_MA_VAL1", "S")
        .build
    ) should contain theSameElementsAs Seq(
      (
        ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", 1988, "FI"),
        Seq(
          Arvosana(
            suoritus = null,
            arvio = Arvio410("S"),
            "MA",
            lisatieto = None,
            valinnainen = true,
            myonnetty = None,
            source = "person1",
            Map(),
            Some(1)
          )
        )
      )
    )

    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("PK_MA_VAL1", "NOT S")
        .build
    ) should contain theSameElementsAs Seq(
      (ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", 1988, "FI"), Seq())
    )

    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setPerusopetuksenPaattotodistusvuosi(None)
        //.putArvosana("PK_PAATTOTODISTUSVUOSI","")
        .build
    ) should contain theSameElementsAs Seq()
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLukionPaattotodistusvuosi(None)
        //.putArvosana("PK_PAATTOTODISTUSVUOSI","")
        .build
    ) should contain theSameElementsAs Seq()
  }

  it should "split arvosanat correctly in RicherOsaaminen" in {
    RicherOsaaminen(Map("roskaa" -> "1")).groupByKomoAndGroupByAine should be(empty)

    RicherOsaaminen(
      Map(
        "LK_AI" -> "8",
        "LK_AI_OPPIAINE" -> "FI"
      )
    ).groupByKomoAndGroupByAine should be(
      Map("LK" -> Map("AI" -> Map("" -> "8", "OPPIAINE" -> "FI")))
    )

    RicherOsaaminen(
      Map(
        "LK_AI_" -> "ROSKAA",
        "_" -> "ROSKAA",
        "LK_AI_OPPIAINE" -> "4"
      )
    ).groupByKomoAndGroupByAine should be(empty)

    val r = RicherOsaaminen(
      Map(
        "LK_AI" -> "8",
        "LK_AI_OPPIAINE" -> "FI"
      )
    ).groupByKomoAndGroupByAine

    r("LK")("AI")("") should be("8")
    r("LK")("AI")("OPPIAINE") should be("FI")

  }

  it should "include valinnaiset arvosanat" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("PK_MA", "8")
        .putArvosana("PK_MA_VAL1", "6")
        .putArvosana("PK_MA_VAL2", "5")
        .putArvosana("PK_MA_VAL3", "7")
        .build
    ) should contain theSameElementsAs Seq(
      (
        ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", 1988, "FI"),
        Seq(
          Arvosana(
            suoritus = null,
            arvio = Arvio410("6"),
            "MA",
            lisatieto = None,
            valinnainen = true,
            myonnetty = None,
            source = "person1",
            Map(),
            Some(1)
          ),
          Arvosana(
            suoritus = null,
            arvio = Arvio410("5"),
            "MA",
            lisatieto = None,
            valinnainen = true,
            myonnetty = None,
            source = "person1",
            Map(),
            Some(2)
          ),
          Arvosana(
            suoritus = null,
            arvio = Arvio410("7"),
            "MA",
            lisatieto = None,
            valinnainen = true,
            myonnetty = None,
            source = "person1",
            Map(),
            Some(3)
          ),
          Arvosana(
            suoritus = null,
            arvio = Arvio410("8"),
            "MA",
            lisatieto = None,
            valinnainen = false,
            myonnetty = None,
            source = "person1",
            Map()
          )
        )
      )
    )
  }

  it should "create suorituksia from koulutustausta" in {
    val a = IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(2000)
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("LK_MA", "8")
        .build
    )
    a should contain theSameElementsAs Seq(
      (ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", 1988, "FI"), Seq.empty),
      (
        ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", 2000, "FI"),
        Seq(
          Arvosana(
            suoritus = null,
            arvio = Arvio410("8"),
            "MA",
            lisatieto = None,
            valinnainen = false,
            myonnetty = None,
            source = "person1",
            Map()
          )
        )
      )
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

  it should "not create lukio if valmistuminen in current year and lahtokoulu is given" in {
    val currentYear = new DateTime().year().get()
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(currentYear)
        .build
    ) should equal(Seq.empty)
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
    ) should equal(
      Seq((ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", currentYear - 1, "FI"), Seq()))
    )
  }

  it should "handle 'ei arvosanaa'" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(2000)
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("LK_MA", "Ei arvosanaa")
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
        .putArvosana("LK_MA", "8")
        .putArvosana("PK_AI", "7")
        .build
    ) should contain theSameElementsAs Seq(
      (
        ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", 2000, "FI"),
        Seq(
          Arvosana(
            suoritus = null,
            arvio = Arvio410("8"),
            "MA",
            lisatieto = None,
            valinnainen = false,
            myonnetty = None,
            source = "person1",
            Map()
          )
        )
      ),
      (
        ItseilmoitettuPeruskouluTutkinto("hakemus1", "person1", 1988, "FI"),
        Seq(
          Arvosana(
            suoritus = null,
            arvio = Arvio410("7"),
            "AI",
            lisatieto = None,
            valinnainen = false,
            myonnetty = None,
            source = "person1",
            Map()
          )
        )
      )
    )
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
      (
        ItseilmoitettuLukioTutkinto("foobarKoulu", "person1", 2000, "FI"),
        Seq(
          Arvosana(
            suoritus = null,
            arvio = Arvio410("7"),
            "B1",
            lisatieto = Some("SV"),
            valinnainen = false,
            myonnetty = None,
            source = "person1",
            Map()
          ),
          Arvosana(
            suoritus = null,
            arvio = Arvio410("8"),
            "AI",
            lisatieto = Some("FI"),
            valinnainen = false,
            myonnetty = None,
            source = "person1",
            Map()
          )
        )
      )
    )
  }

  it should "not create not empty lukiosuoritus for current year" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromHakemus(
      Hakemus()
        .setHakemusOid("hakemus1")
        .setPersonOid("person1")
        .setLahtokoulu("foobarKoulu")
        .setLukionPaattotodistusvuosi(new LocalDate().getYear)
        .build
    ) should contain theSameElementsAs Seq.empty
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
      (
        ItseilmoitettuTutkinto(Oids.lisaopetusKomoOid, "hakemus1", "person1", kymppiVuosi, "FI"),
        List()
      )
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
    val waiterTimeout = timeout(5.minutes)
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
        case q @ SuoritusQuery(Some(henkilo), _, _, _, _, _, _) =>
          sender ! suoritukset.filter(_.henkiloOid == henkilo)
          suoritusQueryWaiter.dismiss()
        case q @ SuoritusQueryWithPersonAliases(
              SuoritusQuery(Some(henkilo), _, _, _, _, _, _),
              _
            ) =>
          sender ! suoritukset.filter(_.henkiloOid == henkilo)
          suoritusQueryWaiter.dismiss()
        case LogMessage(_, Logging.DebugLevel) =>
          bypassWaiter.dismiss()
      }
    })
    val arvosanaRekisteri = TestActorRef(new Actor {
      override def receive: Receive = {
        case i: InsertResource[_, _] if i.resource.isInstanceOf[Arvosana] =>
          val identified: Arvosana with Identified[UUID] =
            i.resource.asInstanceOf[Arvosana].identify(UUID.randomUUID())
          sender ! identified
          arvosanaWaiter.dismiss()
      }
    })

    val hakemus = Hakemus()
      .setHakemusOid("hakemus1")
      .setPersonOid("person1")
      .setLahtokoulu("foobarKoulu")
      .setPerusopetuksenPaattotodistusvuosi(1988)
      .putArvosana("PK_MA", "8")
      .build

    IlmoitetutArvosanatTrigger.muodostaSuorituksetJaArvosanat(
      hakemus,
      suoritusRekisteri,
      arvosanaRekisteri,
      PersonOidsWithAliases(hakemus.personOid.toSet),
      logBypassed = true
    )

    suoritusQueryWaiter.await(waiterTimeout, dismissals(1))
    suoritusWaiter.await(waiterTimeout, dismissals(1))
    arvosanaWaiter.await(waiterTimeout, dismissals(1))
    bypassWaiter.await(waiterTimeout, dismissals(0))

    IlmoitetutArvosanatTrigger.muodostaSuorituksetJaArvosanat(
      hakemus,
      suoritusRekisteri,
      arvosanaRekisteri,
      PersonOidsWithAliases(hakemus.personOid.toSet),
      logBypassed = true
    )

    suoritusQueryWaiter.await(waiterTimeout, dismissals(1))
    bypassWaiter.await(waiterTimeout, dismissals(1))

    suoritusRekisteri.underlyingActor.suoritukset.size should be(1)
  }

  trait CustomMatchers {

    class ArvosanatMatcher(expectedArvosanat: Seq[Arvosana]) extends Matcher[Seq[Arvosana]] {

      def apply(left: Seq[Arvosana]) = {
        MatchResult(
          if (left.isEmpty) false
          else
            left
              .map(l =>
                expectedArvosanat.exists(p =>
                  l.arvio.equals(p.arvio) && l.lisatieto.equals(p.lisatieto)
                )
              )
              .reduceLeft(_ && _),
          s"""Arvosanat\n ${left.toList}\nwas not expected\n $expectedArvosanat""",
          s"""Arvosanat\n ${stripId(left).toList}\nwas expected\n $expectedArvosanat"""
        )
      }

      private def stripId(l: Seq[Arvosana]) = {
        l.map(l0 => {
          l0.copy(suoritus = null)
        })
      }
    }

    def containArvosanat(expectedExtension: Seq[Arvosana]) = new ArvosanatMatcher(expectedExtension)
  }
  object CustomMatchers extends CustomMatchers

}

object Hakemus {
  def apply(): HakemusBuilder =
    HakemusBuilder(Map.empty, "", None, None, None, None, None, None, None, "")
}

case class HakemusBuilder(
  osaaminen: Map[String, String],
  hakemusOid: String = "",
  personOid: Option[String],
  PK_PAATTOTODISTUSVUOSI: Option[String],
  LISAKOULUTUS_KYMPPI: Option[String],
  KYMPPI_PAATTOTODISTUSVUOSI: Option[String],
  lukioPaattotodistusVuosi: Option[String],
  lahtokoulu: Option[String],
  suoritusoikeus_tai_aiempi_tutkinto_vuosi: Option[String],
  applicationSystemId: String
) {
  def setApplicationSystemId(oid: String): HakemusBuilder =
    this.copy(applicationSystemId = oid)

  def setSuorittanutSuomalaisenKkTutkinnon(vuosi: Int): HakemusBuilder =
    this.copy(suoritusoikeus_tai_aiempi_tutkinto_vuosi = Some(vuosi.toString))

  def setHakemusOid(hOid: String): HakemusBuilder =
    this.copy(hakemusOid = hOid)

  def setPersonOid(pOid: String): HakemusBuilder =
    this.copy(personOid = Some(pOid))

  def setLahtokoulu(kouluOid: String): HakemusBuilder =
    this.copy(lahtokoulu = Some(kouluOid))

  def setPerusopetuksenPaattotodistusvuosi(paattotodistusvuosi: Option[String]): HakemusBuilder =
    this.copy(PK_PAATTOTODISTUSVUOSI = paattotodistusvuosi)

  def setPerusopetuksenPaattotodistusvuosi(paattotodistusvuosi: Int): HakemusBuilder =
    this.copy(PK_PAATTOTODISTUSVUOSI = Some(paattotodistusvuosi.toString))

  def setLukionPaattotodistusvuosi(paattotodistusvuosi: Option[String]): HakemusBuilder =
    this.copy(lukioPaattotodistusVuosi = paattotodistusvuosi)

  def setLukionPaattotodistusvuosi(paattotodistusvuosi: Int): HakemusBuilder =
    this.copy(lukioPaattotodistusVuosi = Some(paattotodistusvuosi.toString))

  def setLisaopetusKymppi(bool: String): HakemusBuilder =
    this.copy(LISAKOULUTUS_KYMPPI = Some(bool))

  def setKymppiVuosi(vuosi: Int): HakemusBuilder =
    this.copy(KYMPPI_PAATTOTODISTUSVUOSI = Some(vuosi.toString))

  def putArvosana(aine: String, arvosana: String): HakemusBuilder =
    this.copy(osaaminen = osaaminen + (aine -> arvosana))

  def build: FullHakemus = FullHakemus(
    hakemusOid,
    personOid,
    applicationSystemId,
    Some(
      HakemusAnswers(
        Some(
          HakemusHenkilotiedot(
            Henkilotunnus = Some("110388-9241"),
            aidinkieli = None,
            lahiosoite = None,
            Postinumero = None,
            osoiteUlkomaa = None,
            postinumeroUlkomaa = None,
            kaupunkiUlkomaa = None,
            asuinmaa = None,
            matkapuhelinnumero1 = None,
            matkapuhelinnumero2 = None,
            Sähköposti = None,
            kotikunta = None,
            Sukunimi = None,
            Etunimet = None,
            Kutsumanimi = None,
            kansalaisuus = None,
            onkoSinullaSuomalainenHetu = None,
            sukupuoli = None,
            syntymaaika = None,
            koulusivistyskieli = None,
            turvakielto = None
          )
        ),
        Some(
          Koulutustausta(
            lahtokoulu = lahtokoulu,
            POHJAKOULUTUS = None,
            lukioPaattotodistusVuosi,
            PK_PAATTOTODISTUSVUOSI,
            KYMPPI_PAATTOTODISTUSVUOSI,
            LISAKOULUTUS_KYMPPI,
            LISAKOULUTUS_VAMMAISTEN = None,
            LISAKOULUTUS_TALOUS = None,
            LISAKOULUTUS_AMMATTISTARTTI = None,
            LISAKOULUTUS_KANSANOPISTO = None,
            LISAKOULUTUS_MAAHANMUUTTO = None,
            LISAKOULUTUS_MAAHANMUUTTO_LUKIO = None,
            LISAKOULUTUS_VALMA = None,
            LISAKOULUTUS_OPISTOVUOSI = None,
            luokkataso = None,
            lahtoluokka = None,
            perusopetuksen_kieli = None,
            lukion_kieli = None,
            pohjakoulutus_yo = None,
            pohjakoulutus_yo_vuosi = None,
            pohjakoulutus_am = None,
            pohjakoulutus_am_vuosi = None,
            pohjakoulutus_amt = None,
            pohjakoulutus_amt_vuosi = None,
            pohjakoulutus_kk = None,
            pohjakoulutus_kk_pvm = None,
            pohjakoulutus_ulk = None,
            pohjakoulutus_ulk_vuosi = None,
            pohjakoulutus_avoin = None,
            pohjakoulutus_muu = None,
            pohjakoulutus_muu_vuosi = None,
            aiempitutkinto_tutkinto = None,
            aiempitutkinto_korkeakoulu = None,
            aiempitutkinto_vuosi = None,
            suoritusoikeus_tai_aiempi_tutkinto_vuosi = suoritusoikeus_tai_aiempi_tutkinto_vuosi,
            suoritusoikeus_tai_aiempi_tutkinto =
              if (suoritusoikeus_tai_aiempi_tutkinto_vuosi.isDefined) Some("true") else None,
            muukoulutus = None
          )
        ),
        None,
        None,
        Some(osaaminen)
      )
    ),
    Some("ACTIVE"),
    Nil,
    Nil,
    Some(1615219923688L),
    updated = None
  )

}
