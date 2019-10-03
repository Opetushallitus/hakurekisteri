package fi.vm.sade.hakurekisteri.acceptance.tools

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.{Date, UUID}

import akka.actor._
import com.github.nscala_time.time.Imports._
import com.github.nscala_time.time.TypeImports.LocalDate
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.integration.henkilo.{MockPersonAliasesProvider, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActorRef
import fi.vm.sade.hakurekisteri.integration.parametrit.{MockParameterActor, ParametritActorRef}
import fi.vm.sade.hakurekisteri.koodisto.MockedKoodistoActor
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaJDBCActor, OpiskelijaTable}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.tools.{ItPostgres, Peruskoulu}
import fi.vm.sade.hakurekisteri.web.opiskelija.{OpiskelijaResource, OpiskelijaSwaggerApi}
import fi.vm.sade.hakurekisteri.web.rest.support._
import fi.vm.sade.hakurekisteri.web.suoritus.{SuoritusResource, SuoritusSwaggerApi}
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization._
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers._
import org.scalatest.words.EmptyWord
import org.scalatra.test.HttpComponentsClient
import org.scalatra.test.scalatest.ScalatraFeatureSpec

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.language.implicitConversions
import scala.xml.{Elem, Node, NodeSeq}


object kausi extends Enumeration {
  type Kausi = Value
  val Keväällä, Syksyllä = Value
  val Kevät = Keväällä
  val Syksy = Syksyllä
}

import fi.vm.sade.hakurekisteri.acceptance.tools.kausi._

trait HakurekisteriContainer extends ScalatraFeatureSpec with BeforeAndAfterEach with HakurekisteriJsonSupport with HttpComponentsClient {
  override protected implicit def jsonFormats = super.jsonFormats ++ List(new SuoritusDeserializer)
  implicit val swagger = new HakurekisteriSwagger
  implicit val security = new TestSecurity
  implicit var system: ActorSystem = _
  implicit var database: Database = _
  var suoritusJournal: JDBCJournal[Suoritus, UUID, SuoritusTable] = _
  private val mockConfig: MockConfig = new MockConfig


  override def beforeAll(): Unit = {
    system = ActorSystem()
    database = Database.forURL(ItPostgres.getEndpointURL)

    suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable], config = mockConfig)
    val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable], config = mockConfig)
    val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(system.actorOf(Props(new SuoritusJDBCActor(suoritusJournal, 1, MockPersonAliasesProvider, mockConfig))))))
    val guardedOpiskelijaRekisteri = system.actorOf(Props(new FakeAuthorizer(system.actorOf(Props(new OpiskelijaJDBCActor(opiskelijaJournal, 1, mockConfig))))))
    val koodistoActor =  new KoodistoActorRef(system.actorOf(Props(new MockedKoodistoActor())))
    val parameterActor = new ParametritActorRef(system.actorOf(Props(new MockParameterActor(config = mockConfig)(system))))

    val personOidsAliasFetcher: Set[String] => Future[PersonOidsWithAliases]= oids => Future.successful(PersonOidsWithAliases.apply(henkiloOids = oids))
    val suoritusQueryMaker: Map[String, String] => fi.vm.sade.hakurekisteri.rest.support.Query[Suoritus] = p => fi.vm.sade.hakurekisteri.suoritus.SuoritusQuery(p)
    addServlet(new SuoritusResource(guardedSuoritusRekisteri, parameterActor, koodistoActor)
      with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus], "/rest/v1/suoritukset")
    addServlet(new OpiskelijaResource(guardedOpiskelijaRekisteri), "/rest/v1/opiskelijat")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), Duration(15, TimeUnit.SECONDS))
      database.close()
    }
  }

  override def beforeEach() {
    ItPostgres.reset()
    super.beforeEach()
  }

  object db {
    def is(token:Any) = token match {
      case e:EmptyWord => has()
    }

    def has(suoritukset: Suoritus*) = {
      suoritukset.foreach(s => suoritusJournal.addModification(Updated(s.identify)))
    }
  }

  def allSuoritukset: Seq[Suoritus] = get("/rest/v1/suoritukset") {
    hae(suoritukset)
  }

  def create (suoritus: Suoritus){
    val json = write(suoritus)
    post("/rest/v1/suoritukset", json, Map("Content-Type" -> "application/json; charset=utf-8")) {
    }
  }

  def create (opiskelija: Opiskelija){
    val json = write(opiskelija)
    post("/rest/v1/opiskelijat", json, Map("Content-Type" -> "application/json; charset=utf-8"))  {
    }
  }

  val kevatJuhla = new MonthDay(6,4).toLocalDate(DateTime.now.getYear)
  val suoritus = Peruskoulu("1.2.3", "KESKEN",  kevatJuhla, "1.2.4")
  val suoritus2 =  Peruskoulu("1.2.5", "KESKEN", kevatJuhla, "1.2.3")
  val suoritus3 =  Peruskoulu("1.2.5", "KESKEN",  kevatJuhla, "1.2.6")

  def hae[T: Manifest](query:ResourceQuery[T]):Seq[T] = {
    query.find
  }

  trait ResourceQuery[T] {
    def arvot:Map[String,String]
    def resourcePath:String


    def find[R: Manifest]:Seq[R] = {

      get(resourcePath,arvot) {
        parse(body)
      }.extract[Seq[R]]
    }
  }

  case class OpiskelijaQuery(arvot:Map[String,String]) extends ResourceQuery[Opiskelija] {
    def resourcePath: String = "/rest/v1/opiskelijat"

    def koululle(oid: String): OpiskelijaQuery = {
      OpiskelijaQuery(arvot + ("oppilaitosOid" -> oid))
    }
  }

  case class SuoritusQuery(arvot:Map[String, String]) extends ResourceQuery[Suoritus]{
    def vuodelta(vuosi:Int): SuoritusQuery = {
      new SuoritusQuery(arvot + ("vuosi" -> vuosi.toString))
    }

    def koululle(oid: String): SuoritusQuery = {
      new SuoritusQuery(arvot + ("myontaja" -> oid))
    }

    def getKausiCode(kausi:Kausi):String = kausi match {
      case Kevät => "K"
      case Syksy => "S"
    }

    def kaudelta(kausi: Kausi): SuoritusQuery = {
      new SuoritusQuery(arvot + ("kausi" -> getKausiCode(kausi)))
    }

    def henkilolle(henkilo: Henkilo): SuoritusQuery = {
      new SuoritusQuery(arvot + ("henkilo" -> henkilo.oid))
    }

    def resourcePath: String = "/rest/v1/suoritukset"
  }

  val suoritukset = SuoritusQuery(Map())
  val opiskelijat = OpiskelijaQuery(Map())

  case class Valmistuja(oid:String, vuosi:String, kausi: Kausi) {
    val date: LocalDate =
      kausi match {
        case Kevät => new MonthDay(6,4).toLocalDate(vuosi.toInt)
        case Syksy => new MonthDay(12,21).toLocalDate(vuosi.toInt)
      }

    def koulusta(koulu:String) {
      suoritusJournal.addModification(Updated(Peruskoulu(koulu, "KESKEN", date, oid).identify))
    }
  }

  trait Henkilo {
    def oid:String
    def hetu: String

    def valmistuu(kausi:Kausi, vuosi:Int) = {
      new Valmistuja(oid, "" + vuosi, kausi)
    }
  }

  object Mikko extends Henkilo{
    val hetu: String = "291093-9159"
    def oid: String = "1.2.3"
  }

  object Matti extends Henkilo {
    val hetu: String = "121298-869R"
    def oid: String = "1.2.4"
  }

  def beBefore(s:String) =
    new Matcher[LocalDate] {
      def apply(left: LocalDate): MatchResult = {
        val pattern = DateTimeFormat.forPattern("dd.MM.yyyy")
        MatchResult(
          left < pattern.parseLocalDate(s),
          left.toString(pattern) + " was not before " + s,
          left.toString(pattern) + " was before " +s
        )
      }
    }

  object koulu {
    val koodi = "05536"
    val id ="1.2.3"

    implicit def nodeSeq2String(seq:NodeSeq) : String = {
      seq.text
    }

    object oppilaitosRekisteri {
      def findOrg(koulukoodi: String): String   = koulukoodi match {
        case "05536" => "1.2.3"
      }
    }

    object henkiloRekisteri {
      def find(hetu:String) = hetu match {
        case  Mikko.hetu => Mikko.oid
        case  Matti.hetu => Matti.oid
      }
    }

    def parseSuoritukset(rowset: Node):Seq[Suoritus]  =  {
      rowset \ "ROW" map ((row) =>
        Peruskoulu(
          oppilaitos = oppilaitosRekisteri.findOrg(row \ "LAHTOKOULU") ,
          tila = "KESKEN",
          valmistuminen = kevatJuhla,
          henkiloOid = henkiloRekisteri.find(row \ "HETU")) )
    }

    def lahettaa(kaavake:Elem){
      parseSuoritukset(kaavake) foreach create
      parseOpiskelijat(kaavake) foreach create
    }

    def getStartDate(vuosi: String, kausi: String): DateTime = kausi match {
      case "S" => new MonthDay(1, 1).toLocalDate(vuosi.toInt).toDateTimeAtStartOfDay
      case "K" => new MonthDay(8, 1).toLocalDate(vuosi.toInt).toDateTimeAtStartOfDay
      case default => throw new RuntimeException("unknown kausi")
    }

    def parseOpiskelijat(rowset: Node):Seq[Opiskelija] = rowset \ "ROW" map ((row) =>
      Opiskelija(
        oppilaitosOid = oppilaitosRekisteri.findOrg(row \ "LAHTOKOULU") ,
        luokkataso = row \ "LUOKKATASO",
        luokka = row \ "LUOKKA",
        henkiloOid = henkiloRekisteri.find(row \ "HETU"),
        alkuPaiva = getStartDate(row \ "VUOSI", row \"KAUSI"), source = "Test")
      )
  }
  val dateformat = new SimpleDateFormat("dd.MM.yyyy")

  implicit def string2Date(s:String):Date = {
    dateformat.parse(s)
  }

  implicit def string2LocalDate(s: String): LocalDate = {
    DateTime.parse(s, DateTimeFormat.forPattern("dd.MM.yyyy")).toLocalDate
  }

  implicit def string2DateTime(s: String): DateTime = {
    DateTime.parse(s, DateTimeFormat.forPattern("dd.MM.yyyy"))
  }
}
