package fi.vm.sade.hakurekisteri.acceptance.tools

import org.scalatra.test.HttpComponentsClient

import javax.servlet.http.{HttpServletRequest, HttpServlet}
import akka.actor.{Props, ActorSystem}

import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization._
import java.util.{UUID, Date}
import java.text.SimpleDateFormat
import org.scalatest.matchers._
import org.scalatest.Suite
import scala.xml.{Elem, Node, NodeSeq}
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.opiskelija.{CreateOpiskelijaCommand, OpiskelijaSwaggerApi, Opiskelija, OpiskelijaActor}
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.{LocalDate, DateTime}
import org.joda.time.format.DateTimeFormat

import com.github.nscala_time.time.Imports._
import fi.vm.sade.hakurekisteri.storage.repository.{Updated, InMemJournal}
import fi.vm.sade.hakurekisteri.rest.support.User
import com.github.nscala_time.time.TypeImports.LocalDate
import com.github.nscala_time.time.StaticForwarderImports.LocalDate


object kausi extends Enumeration {
  type Kausi = Value
  val Keväällä, Syksyllä = Value
  val Kevät = Keväällä
  val Syksy = Syksyllä
}

import kausi._

trait TestSecurity extends SecuritySupport {

  object TestUser extends User {

    override def orgsFor(action: String, resource: String): Set[String] = Set("1.2.246.562.10.00000000001")

    override val username: String = "Test"
  }

  override def currentUser(implicit request: HttpServletRequest): Option[fi.vm.sade.hakurekisteri.rest.support.User] = Some(TestUser)
}

trait HakurekisteriSupport extends Suite with HttpComponentsClient with HakurekisteriJsonSupport {
  override def withFixture(test: NoArgTest) {
    tehdytSuoritukset = Seq()
    db.initialized = false
    super.withFixture(test)
  }

  implicit val swagger = new HakurekisteriSwagger

  def addServlet(servlet: HttpServlet, path: String):Unit

  object empty

  object db {
    var initialized = false

    def init() {
      if (!initialized) {
        println ("Initializing db with: " + tehdytSuoritukset)
        implicit val system = ActorSystem()
        implicit def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID]](s:Seq[R]) = {
          var journal = new InMemJournal[R, UUID]
          s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
          journal
        }
        val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(tehdytSuoritukset)))
        val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(suoritusRekisteri)))
        val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(Seq())))
        val guardedOpiskelijaRekisteri = system.actorOf(Props(new FakeAuthorizer(opiskelijaRekisteri)))
        addServlet(new HakurekisteriResource[Suoritus, CreateSuoritusCommand](guardedSuoritusRekisteri, fi.vm.sade.hakurekisteri.suoritus.SuoritusQuery(_)) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus, CreateSuoritusCommand] with TestSecurity, "/rest/v1/suoritukset")
        addServlet(new HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand](guardedOpiskelijaRekisteri, fi.vm.sade.hakurekisteri.opiskelija.OpiskelijaQuery(_)) with OpiskelijaSwaggerApi with HakurekisteriCrudCommands[Opiskelija, CreateOpiskelijaCommand] with TestSecurity , "/rest/v1/opiskelijat")
        initialized = true
      }
    }

    def is(token:Any) = token match {
      case empty => has()
    }

    def has(suoritukset: Suoritus*) = {
      tehdytSuoritukset = suoritukset
    }
  }

  def allSuoritukset: Seq[Suoritus] = get("/rest/v1/suoritukset") {
    hae(suoritukset)
  }

  def create (suoritus: Suoritus){
    db.init()
    val json = write(suoritus)
    println(json)
    post("/rest/v1/suoritukset", json, Map("Content-Type" -> "application/json; charset=utf-8")) {}
  }

  def create (opiskelija: Opiskelija){
    db.init()
    post("/rest/v1/opiskelijat", write(opiskelija), Map("Content-Type" -> "application/json; charset=utf-8")) {}
  }

  val kevatJuhla = new MonthDay(6,4).toLocalDate(DateTime.now.getYear)
  val suoritus = Peruskoulu("1.2.3", "KESKEN",  kevatJuhla, "1.2.4")
  val suoritus2 =  Peruskoulu("1.2.5", "KESKEN", kevatJuhla, "1.2.3")
  val suoritus3 =  Peruskoulu("1.2.5", "KESKEN",  kevatJuhla, "1.2.6")

  def hae[T: Manifest](query:ResourceQuery[T]):Seq[T] = {
    db.init()
    query.find
  }

  trait ResourceQuery[T] {
    def arvot:Map[String,String]
    def resourcePath:String


    def find[R: Manifest]:Seq[R] = {
      get(resourcePath,arvot) {
        println("Haku polusta:  " + resourcePath + " arvoilla " + arvot)
        println("Tulos: " + body)
        parse(body)
      }.extract[Seq[R]]
    }
  }

  case class OpiskelijaQuery(arvot:Map[String,String]) extends ResourceQuery[Opiskelija] {
    def resourcePath: String = "/rest/v1/opiskelijat"

    def koululle(oid: String): OpiskelijaQuery = {
      OpiskelijaQuery(arvot + ("koulu" -> oid))
    }
  }

  case class SuoritusQuery(arvot:Map[String, String]) extends ResourceQuery[Suoritus]{
    def vuodelta(vuosi:Int): SuoritusQuery = {
      new SuoritusQuery(arvot + ("vuosi" -> vuosi.toString))
    }

    def koululle(oid: String): SuoritusQuery = {
      new SuoritusQuery(arvot + ("koulu" -> oid))
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
  var tehdytSuoritukset:Seq[Suoritus] = Seq()

  case class Valmistuja(oid:String, vuosi:String, kausi: Kausi) {
    val date: LocalDate =
      kausi match {
        case Kevät => new MonthDay(6,4).toLocalDate(vuosi.toInt)
        case Syksy => new MonthDay(12,21).toLocalDate(vuosi.toInt)
      }

    def koulusta(koulu:String) {
      val list = tehdytSuoritukset.toList
      val valmistuminen = Peruskoulu(koulu, "KESKEN", date, oid)
      println(valmistuminen)
      tehdytSuoritukset = (list :+ valmistuminen).toSeq
      println(tehdytSuoritukset)
    }
  }

  trait Henkilo {
    def oid:String
    def hetu: String

    def valmistuu(kausi:Kausi, vuosi:Int) = {
      println(oid + " valmistuu " + kausi + " vuonna " + vuosi)
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

    def lähettää(kaavake:Elem){
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


object PerusopetuksenToteutus2005S {
  def apply (oppilaitos: String) : Komoto = {
    Komoto("komotoid", "peruskoulu", oppilaitos, "2005", Kausi.Syksy)
  }
}
object Peruskoulu {
  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): VirallinenSuoritus = {
    VirallinenSuoritus("peruskoulu", oppilaitos, tila, valmistuminen, henkiloOid, yksilollistaminen.Ei, "fi",  lahde = "Test")
  }
}
object OsittainYksilollistettyPerusopetus {
  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): VirallinenSuoritus = {
    VirallinenSuoritus("peruskoulu", oppilaitos, tila, valmistuminen, henkiloOid, yksilollistaminen.Osittain, "fi", lahde = "Test")
  }
}
object AlueittainYksilollistettyPerusopetus {
  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): VirallinenSuoritus = {
    VirallinenSuoritus("peruskoulu", oppilaitos, tila, valmistuminen, henkiloOid, yksilollistaminen.Alueittain, "fi", lahde = "Test")
  }
}
object KokonaanYksillollistettyPerusopetus {
  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): VirallinenSuoritus = {
    VirallinenSuoritus("peruskoulu", oppilaitos, tila, valmistuminen, henkiloOid, yksilollistaminen.Kokonaan, "fi", lahde = "Test")
  }
}