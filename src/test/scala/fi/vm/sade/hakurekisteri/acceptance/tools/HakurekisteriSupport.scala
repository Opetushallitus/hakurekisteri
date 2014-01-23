package fi.vm.sade.hakurekisteri.acceptance.tools

import org.scalatra.test.HttpComponentsClient
import org.json4s.{DefaultFormats, Formats}
import javax.servlet.http.HttpServlet
import fi.vm.sade.hakurekisteri.{SuoritusServlet, SuoritusActor}
import akka.actor.{Props, ActorSystem}
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization._
import fi.vm.sade.hakurekisteri.Suoritus
import java.util.Date
import java.text.SimpleDateFormat
import org.scalatest.matchers._
import org.scalatest.Suite


object kausi extends Enumeration {
  type Kausi = Value
  val Keväällä, Syksyllä = Value
  val Kevät = Keväällä
  val Syksy = Syksyllä


}

import kausi._

trait HakurekisteriSupport extends  Suite with HttpComponentsClient {
  override def withFixture(test: NoArgTest) {
    tehdytSuoritukset = Seq()
    db.initialized = false
    super.withFixture(test)
  }


  protected implicit val jsonFormats: Formats = DefaultFormats

  def addServlet(servlet: HttpServlet, path: String):Unit


  object empty

  object db {

    var initialized = false

    def init() {
      if (!initialized) {
        println ("Initializing db with: " + tehdytSuoritukset)
        val system = ActorSystem()
        val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(tehdytSuoritukset)))
        addServlet(new SuoritusServlet(system, suoritusRekisteri), "/rest/v1/suoritukset")
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

  val df = new SimpleDateFormat("yyyyMMdd")

  val kevatJuhla = df.parse("20140604")


  val suoritus = new  Suoritus("1.2.3", "KESKEN", "9", kevatJuhla, "9D", "1.2.4")

  val suoritus2 =  new Suoritus("1.2.5", "KESKEN", "9", kevatJuhla, "9A", "1.2.3")

  val suoritus3 =  new Suoritus("1.2.5", "KESKEN", "9", kevatJuhla, "9B", "1.2.6")


  def hae[T: Manifest](query:ResourceQuery[T]):Seq[T] = {
    db.init()
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

  case class SuoritusQuery(arvot:Map[String, String]) extends ResourceQuery[Suoritus]{


    def vuodelta(vuosi:Int): SuoritusQuery = {
      new SuoritusQuery(arvot + ("vuosi" -> vuosi.toString))
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

  val suoritukset = new SuoritusQuery(Map())


  var tehdytSuoritukset:Seq[Suoritus] = Seq()

  case class Valmistuja(oid:String, vuosi:String, kausi: Kausi) {

    val date:Date =
      kausi match {
        case Kevät => df.parse(vuosi + "0604")
        case Syksy => df.parse(vuosi + "1221")
      }



    def koulusta(koulu:String) {
      val list = tehdytSuoritukset.toList
      val valmistuminen = new Suoritus(koulu, "KESKEN", "9", date, "9A", oid)
      println(valmistuminen)
      tehdytSuoritukset = (list :+ valmistuminen).toSeq
      println(tehdytSuoritukset)
    }



  }

  trait Henkilo {

    def oid:String

    def valmistuu(kausi:Kausi, vuosi:Int) = {
      println(oid + " valmistuu " + kausi + " vuonna " + vuosi)
      new Valmistuja(oid, "" + vuosi, kausi)


    }

  }

  object Mikko extends Henkilo{
    def oid: String = "1.2.3"
  }

  object Matti extends Henkilo {
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


}


