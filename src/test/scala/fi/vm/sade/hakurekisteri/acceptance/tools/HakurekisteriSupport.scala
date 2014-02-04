package fi.vm.sade.hakurekisteri.acceptance.tools

import org.scalatra.test.HttpComponentsClient
import org.json4s.{DefaultFormats, Formats}
import javax.servlet.http.HttpServlet
import akka.actor.{Props, ActorSystem}
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization._
import java.util.Date
import java.text.SimpleDateFormat
import org.scalatest.matchers._
import org.scalatest.Suite
import fi.vm.sade.hakurekisteri.rest.{OpiskelijaServlet, HakurekisteriJsonSupport, HakurekisteriSwagger, SuoritusServlet}
import fi.vm.sade.hakurekisteri.actor.{OpiskelijaActor, SuoritusActor}
import fi.vm.sade.hakurekisteri.domain.{Opiskelija, yksilollistaminen, Peruskoulu, Suoritus}
import scala.xml.{Elem, Node, NodeSeq}


object kausi extends Enumeration {
  type Kausi = Value
  val Keväällä, Syksyllä = Value
  val Kevät = Keväällä
  val Syksy = Syksyllä


}

import kausi._

trait HakurekisteriSupport extends  Suite with HttpComponentsClient with HakurekisteriJsonSupport  {
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
        val system = ActorSystem()
        val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(tehdytSuoritukset)))
        val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(Seq())))
        addServlet(new SuoritusServlet(system, suoritusRekisteri), "/rest/v1/suoritukset")
        addServlet(new OpiskelijaServlet(system, opiskelijaRekisteri), "/rest/v1/opiskelijat")
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
    post("/rest/v1/suoritukset", write(suoritus), Map("Content-Type" -> "application/json; charset=utf-8")) {}
  }

  def create (opiskelija: Opiskelija){
    db.init()
    post("/rest/v1/opiskelijat", write(opiskelija), Map("Content-Type" -> "application/json; charset=utf-8")) {}
  }

  val df = new SimpleDateFormat("yyyyMMdd")

  val kevatJuhla = df.parse("20140604")


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
        println(body)
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

    val date:Date =
      kausi match {
        case Kevät => df.parse(vuosi + "0604")
        case Syksy => df.parse(vuosi + "1221")
      }



    def koulusta(koulu:String) {
      val list = tehdytSuoritukset.toList
      val valmistuminen = Peruskoulu(koulu, "KESKEN",  date,  oid)
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
    new Matcher[Date] {
      def apply(left: Date): MatchResult = {
        val format = new SimpleDateFormat("dd.MM.yyyy")
        MatchResult(
          left.before(format.parse(s)),
          format.format(left) + " was not before " + s,
          format.format(left) + " was before " +s
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

    def getStartDate(vuosi: String, kausi: String): Date = kausi match {
      case "S" => dateformat.parse("01.01." + vuosi)
      case "K" => dateformat.parse("01.08." + vuosi)
      case default => throw new RuntimeException("unknown kausi")

    }

    def parseOpiskelijat(rowset: Node):Seq[Opiskelija] = {
      rowset \ "ROW" map ((row) =>
        Opiskelija(
          oppilaitosOid = oppilaitosRekisteri.findOrg(row \ "LAHTOKOULU") ,
          luokkataso = row \ "LUOKKATASO",
          luokka = row \ "LUOKKA",
          henkiloOid = henkiloRekisteri.find(row \ "HETU"),
          alkuPaiva = getStartDate(row \ "VUOSI", row \"KAUSI"))
        )


    }

  }
  val dateformat = new SimpleDateFormat("dd.MM.yyyy")

  implicit def string2Date(s:String):Date = {
    dateformat.parse(s)

  }
}


