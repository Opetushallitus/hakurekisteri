package fi.vm.sade.hakurekisteri.integration.hakemus

import java.util.UUID

import akka.actor.Actor.Receive
import akka.actor.Status.Success
import akka.actor._
import akka.testkit.TestActorRef
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.{suoritus, SpecsLikeMockito}
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvosana, ArvosanaQuery, ArvosanaActor}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.rest.support.Resource
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import org.json4s.JsonAST.JObject
import org.mockito.Mockito._
import org.scalatest.{Matchers, FlatSpec}
import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import akka.pattern.AskTimeoutException
import akka.pattern.ask
import akka.util.Timeout
import akka.testkit.{ TestActors, TestKit, ImplicitSender }
import org.scalatest.WordSpecLike
import org.scalatest.Matchers
import org.scalatest.BeforeAndAfterAll

import scala.collection
import scala.collection.{immutable, Seq}
import scala.collection.immutable.List
import scala.concurrent.{Await, Future}
import akka.testkit.TestActorRef
import scala.concurrent.duration._
import scala.concurrent.Await
import akka.pattern.ask
import org.scalatest._
import matchers._

/**
 * @author Jussi Jartamo
 */
class HakemusActorSpec extends FlatSpec with Matchers with FutureWaiting with SpecsLikeMockito with DispatchSupport {

  implicit val formats = DefaultFormats
  import scala.language.implicitConversions
  implicit val system = ActorSystem("test-hakemus-system")
  import scala.concurrent.duration._
  implicit val timeout: Timeout = 5.second
  import CustomMatchers._

  it should "create suoritukset and arvosanat to rekisteri" in {

    val suoritusRekisteri = TestActorRef(new SuoritusActor)
    val arvosanaRekisteri = TestActorRef(new ArvosanaActor)

    IlmoitetutArvosanatTrigger.muodostaSuorituksetJaArvosanat(
      Hakemus()
        .setPersonOid("person1")
        .setLukionPaattotodistusvuosi(2000)
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("LK_MA","8")
        .putArvosana("PK_AI","7")
        .putArvosana("PK_AI_OPPIAINE","FI")
        .build,
      suoritusRekisteri, arvosanaRekisteri)

    val future = (arvosanaRekisteri ? ArvosanaQuery(None)).mapTo[Seq[Arvosana with Identified[UUID]]]
    val result = future.value.get

    result.isSuccess should be (true)
    result.get should containArvosanat (Seq(
      Arvosana(null,Arvio410("7"),"AI",Some("FI"),false,None,"person1"),
      Arvosana(null,Arvio410("8"),"MA",None,false,None,"person1")
      ))

    val suoritusFut = (suoritusRekisteri ? SuoritusQuery(None))
    val suoritusRes = suoritusFut.value.get
    suoritusRes.isSuccess should be (true)

    System.err.println(suoritusRes)

  }

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

  it should "create suorituksia from koulutustausta" in {
    IlmoitetutArvosanatTrigger.createSuorituksetKoulutustausta(
      Hakemus()
        .setPersonOid("person1")
        .setLukionPaattotodistusvuosi(2000)
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("LK_MA","8")
        .build
    ) should contain theSameElementsAs Seq(ItseilmoitettuLukioTutkinto("person1", 2000, "FI"), ItseilmoitettuPeruskouluTutkinto("person1", 1988, "FI"))
  }

  it should "create suorituksia ja arvosanoja from oppimiset" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromOppimiset(
      Hakemus()
        .setPersonOid("person1")
        .setLukionPaattotodistusvuosi(2000)
        .setPerusopetuksenPaattotodistusvuosi(1988)
        .putArvosana("LK_MA","8")
        .putArvosana("PK_AI","7")
        .build
    ) should contain theSameElementsAs Seq(
      (ItseilmoitettuLukioTutkinto("person1", 2000, "FI"),
        Seq(Arvosana(suoritus = null, arvio = Arvio410("8"), "MA", lisatieto = None, valinnainen = false, myonnetty = None, source = "person1"))),
      (ItseilmoitettuPeruskouluTutkinto("person1", 1988, "FI"),
        Seq(Arvosana(suoritus = null, arvio = Arvio410("7"), "AI", lisatieto = None, valinnainen = false, myonnetty = None, source = "person1"))
        ))
  }

  //
  it should "handle arvosana special cases" in {
    IlmoitetutArvosanatTrigger.createSuorituksetJaArvosanatFromOppimiset(
      Hakemus()
        .setPersonOid("person1")
        .setLukionPaattotodistusvuosi(2000)
        .putArvosana("LK_AI", "8")
        .putArvosana("LK_AI_OPPIAINE", "FI")
        .putArvosana("LK_B1", "7")
        .putArvosana("LK_B1_OPPIAINE", "SV")
        .putArvosana("LK_", "ROSKAA")
        .putArvosana("LK_SA_SDF", "ROSKAA")
        .putArvosana("LK_SA_SDF_ASDF_ASDF_ASDF_ASDF", "ROSKAA")
        .build
    ) should contain theSameElementsAs Seq(
      (ItseilmoitettuLukioTutkinto("person1", 2000, "FI"),
        Seq(
          Arvosana(suoritus = null, arvio = Arvio410("7"), "B1", lisatieto = Some("SV"), valinnainen = false, myonnetty = None, source = "person1"),
          Arvosana(suoritus = null, arvio = Arvio410("8"), "AI", lisatieto = Some("FI"), valinnainen = false, myonnetty = None, source = "person1")
        )))
  }


  trait CustomMatchers {

    class ArvosanatMatcher(expectedArvosanat: Seq[Arvosana]) extends Matcher[Seq[Arvosana]] {

      def apply(left: Seq[Arvosana]) = {
        MatchResult(
          left.map(l => expectedArvosanat.exists(p => l.arvio.equals(p.arvio) && l.lisatieto.equals(p.lisatieto) )).reduceLeft(_ && _),
          s"""Arvosana not in expected arvosanat""",
          s"""Arvosana in expected arvosanat"""
        )
      }
    }

    def containArvosanat(expectedExtension: Seq[Arvosana]) = new ArvosanatMatcher(expectedExtension)
  }
  object CustomMatchers extends CustomMatchers













  /*
  "An Echo actor" must {
    val suoritusRekisteri = system.actorOf(Props(new SuoritusActor()))
    val arvosanaRekisteri = TestActorRef(Props(new ArvosanaActor()))

    "send back messages unchanged" in {
      val suorituksetKoulutustaustasta = IlmoitetutArvosanatTrigger.muodostaSuorituksetKoulutustausta(
        Hakemus()
          .setPersonOid("person1")
          .setLukionPaattotodistusvuosi(2000)
          .putArvosana("LK_MA","8")
          .build
      )



      IlmoitetutArvosanatTrigger.muodostaSuorituksetJaArvosanat(
        Hakemus()
          .setPersonOid("person1")
          .setLukionPaattotodistusvuosi(2000)
          .putArvosana("LK_MA","8")
          .build,
        suoritusRekisteri, arvosanaRekisteri);

      //val arvosanatFut: Future[Seq[Arvosana with Identified[UUID]]] = (arvosanaRekisteri ? ArvosanaQuery(None)).mapTo[Seq[Arvosana with Identified[UUID]]];
      //val uuid = UUID.randomUUID()
      //arvosanaRekisteri ! Arvosana(suoritus = UUID.fromString("1f592dd4-59cb-460d-9d35-7c88e57be289"), arvio = Arvio410("6"), aine = "", lisatieto = Some(""), valinnainen = false, myonnetty = None, source = "person1");

      val u = UUID.fromString("1f592dd5-59cb-460d-9d35-7c88e57be289")
      expectMsg(Arvosana(u, arvio = Arvio410("6"), aine = "", lisatieto = Some(""), valinnainen = false, myonnetty = None, source = "person1"))

      //expectNoMsg()
      //val arvosanat = Await.result(arvosanatFut, 3.second)

      //arvosanat should not be empty
    }

  }*/

  //implicit val formats = DefaultFormats

  //behavior of "HakemusActor"

  /*
  def createHakuAppMockClient(hakemukset: Seq[FullHakemus], system: ActorSystem) = {
    implicit val s = system;
    val result = mock[Endpoint]
    when(result.request(forPattern("http://localhost/applications/listfull.*"))).thenReturn((200, List(),write(hakemukset)))
    new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost"), aClient = Some(new AsyncHttpClient(new CapturingProvider(result))))
  }
  object HakemusActorTriggerWithHakemus {
      def apply(hakemusAndSuoritusrekisteri: (FullHakemus, ((ActorRef, ActorRef, FullHakemus) => Unit))): Unit = {
          implicit val system = ActorSystem("test-hakemus-system")
          implicit val ec = system.dispatcher
          val hakemusActor = TestActorRef(Props(new HakemusActor(createHakuAppMockClient(Seq(hakemusAndSuoritusrekisteri._1),system))))


          val suoritusRekisteri = TestActorRef(Props(new SuoritusActor()))
          val arvosanaRekisteri = TestActorRef(Props(new ArvosanaActor()))
          val sWaiter = new Waiter()
          val triggeredActor = TestActorRef(Props(new TestActor({
            case Triggered => sWaiter.dismiss()
          })))

          hakemusActor ! Trigger {
            (hakemus) => {
              hakemusAndSuoritusrekisteri._2(suoritusRekisteri, arvosanaRekisteri, hakemus); // Contains test asserts
              triggeredActor ! Triggered // Automatically fails the test if never called
            }
          }
          //hakemusActor ! (ReloadingDone("test-oid",0))
          hakemusActor ! (ReloadHaku("test-oid"))

          import org.scalatest.time.SpanSugar._
          sWaiter.await(timeout(15.seconds), dismissals(1))
      }
  }
  */

  /*
  it should "fire a Trigger for new Hakemus" in {
    HakemusActorTriggerWithHakemus((
      HakemusBuilder().setPersonOid("person1").build, (suoritusrekisteri, arvosanaRekisteri, hakemus) => {
        // Automatic failure if never called
    }));


  }

  it should "create itseilmoitetut suoritukset for reported suoritukset" in {
    import scala.concurrent.duration._
    implicit val timeout: Timeout = 5.second

    HakemusActorTriggerWithHakemus((
      HakemusBuilder()
        .setPersonOid("person1")
        .setPaattotodistusvuosi(2000)
        .putArvosana("LK_MA","8")
        .build, (suoritusRekisteri, arvosanaRekisteri, hakemus) => {



        IlmoitetutArvosanatTrigger(suoritusRekisteri, arvosanaRekisteri).newApplicant(hakemus);



        (arvosanaRekisteri ? ArvosanaQuery(None)).mapTo[Seq[Arvosana with Identified[UUID]]].map(s => {
          System.err.println(s.isEmpty)

          s.isEmpty should not be true
          System.err.println("sdf" + s.isEmpty)
        })



        val ve =
        (suoritusRekisteri ? ItseilmoitettuLukioTutkinto("person",2000,"FI"));//.mapTo[Suoritus with Identified[UUID]]

        ve.foreach(k => {
          System.err.println(k)
        })

    }));

  }

  it should "create itseilmoitetut suoritukset for reported arvosanat" in {
    val hakemus = HakemusBuilder().build

    HakemusActorTriggerWithHakemus((HakemusBuilder().build, (suoritusrekisteri, arvosanaRekisteri, hakemus) => {
      //IlmoitetutArvosanatTrigger(suoritusrekisteri).newApplicant(hakemus);
      // Automatic failure if never called
      //System.err.println("fdafd");
    }));

  }

  it should "add arvosanat for existing suorituksille" in {
    val hakemus = HakemusBuilder().build


    HakemusActorTriggerWithHakemus((HakemusBuilder().build, (suoritusrekisteri, arvosanaRekisteri, hakemus) => {
      //IlmoitetutArvosanatTrigger(suoritusrekisteri).newApplicant(hakemus);
      // Automatic failure if never called
      //System.err.println("fdafd");
    }));

  }
  */
}



class TestActor(handler: PartialFunction[Any, Unit]) extends Actor {

  override def receive: Receive = handler
}

object Triggered

object Hakemus {
  def apply(): HakemusBuilder = HakemusBuilder(Map.empty, None, None, None)
}

case class HakemusBuilder(osaaminen: Map[String, String], personOid: Option[String], PK_PAATTOTODISTUSVUOSI: Option[String], lukioPaattotodistusVuosi: Option[String]) {

  def setPersonOid(pOid: String): HakemusBuilder =
    HakemusBuilder(osaaminen, Some(pOid), PK_PAATTOTODISTUSVUOSI, lukioPaattotodistusVuosi)

  def setPerusopetuksenPaattotodistusvuosi(paattotodistusvuosi: Int): HakemusBuilder =
    HakemusBuilder(osaaminen, personOid, Some(paattotodistusvuosi.toString), lukioPaattotodistusVuosi)

  def setLukionPaattotodistusvuosi(paattotodistusvuosi: Int): HakemusBuilder =
    HakemusBuilder(osaaminen, personOid, PK_PAATTOTODISTUSVUOSI, Some(paattotodistusvuosi.toString))

  def putArvosana(aine: String, arvosana: String): HakemusBuilder = HakemusBuilder(osaaminen + (aine -> arvosana), personOid, PK_PAATTOTODISTUSVUOSI, lukioPaattotodistusVuosi)

  def build: FullHakemus = FullHakemus("oid1", personOid, "hakuoid1", Some(HakemusAnswers(
    Some(HakemusHenkilotiedot(Henkilotunnus = Some("110388-9241"),
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
      koulusivistyskieli = None))
    , Some(Koulutustausta(
      lahtokoulu = None,
      POHJAKOULUTUS = None,
      lukioPaattotodistusVuosi,
      PK_PAATTOTODISTUSVUOSI,
      LISAKOULUTUS_KYMPPI = None,
      LISAKOULUTUS_VAMMAISTEN = None,
      LISAKOULUTUS_TALOUS = None,
      LISAKOULUTUS_AMMATTISTARTTI = None,
      LISAKOULUTUS_KANSANOPISTO = None,
      LISAKOULUTUS_MAAHANMUUTTO = None,
      luokkataso = None,
      lahtoluokka = None,
      perusopetuksen_kieli = None,
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
      aiempitutkinto_vuosi = None
    )), None, None, Some(osaaminen))), Some("ACTIVE"), Nil)

}