package siirto

import java.io.{ByteArrayInputStream, StringReader}
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.{SAXSource, SAXResult}

import org.dom4j.Document
import org.dom4j.io.{SAXReader, DocumentSource, DOMReader}
import org.scalatest.{BeforeAndAfter, Matchers, FlatSpec}

import scala.annotation.tailrec
import scala.compat.Platform
import scala.util.Random
import scala.xml.parsing.NoBindingFactoryAdapter
import scala.xml.{Node, Elem, XML}

import scala.xml.Source._

import fi.vm.sade.hakurekisteri.tools.XmlHelpers._

import org.scalatest.fixture
/**
 * Created by verneri on 13.11.14.
 */
class ValidationLoadSpec extends fixture.FlatSpec with Matchers with BeforeAndAfter {

  behavior of "Validation under load"

  type FixtureParam = (ValidXml, Seq[Elem])

  override protected def withFixture(test: OneArgTest) = {
    val data = for (i <- 0 until 100) yield PerustiedotGenerator.perustiedot(1000)
    println("generated")
    val validator = new ValidXml(Perustiedot, PerustiedotKoodisto)

    val start = Platform.currentTime
    val result = test.toNoArgTest((validator, data))()
    val end = Platform.currentTime

    println(s"${test.text} took ${end -start}ms")
    result
  }



  it should "validate with dom" in { input =>
    val (validator, xml) = input
    for (
      file <- xml
    ) validator.validate(new DOMSource(file.toJdkDoc.getDocumentElement))


  }

  it should "validate with sax" in { input =>
    val (validator, xml) = input
    for (
      file <- xml
    ) {
      val validation = validator.validate(new SAXSource(fromString(file.toString)))
      validation.valueOr{
        errors =>
          val (level, ex) = errors.head
          throw ex
      }
    }


  }

  def asXml(dom: _root_.org.w3c.dom.Node): Node = {
    val source = new DOMSource(dom)
    val adapter = new NoBindingFactoryAdapter
    val saxResult = new SAXResult(adapter)
    val transformerFactory = javax.xml.transform.TransformerFactory.newInstance()
    val transformer = transformerFactory.newTransformer()
    transformer.transform(source, saxResult)
    adapter.rootElem
  }


}



trait Generator {

  lazy val generator = Random


  def CHAR = (65 + int(26)).toChar

  def char = (97 + int(26)).toChar

  def int(max: Option[Int] = None): Int = max.map(generator.nextInt).getOrElse(generator.nextInt)

  def int(max: Int): Int = {
    assert(max > 0, "too low")
    int(Some(max))
  }

  def items[T](f: => T, amount: Int): Seq[T] = for (
    i <- 0 until amount
  ) yield f

  def uniqueItems[T](f: => T, amount: Int): Set[T] = {
    @tailrec def generateItems(f: => T, current: Set[T] = Set()): Set[T] =
      if (current.size < amount)
        generateItems(f, current + f)
      else current



    return generateItems(f)
  }

  def seqOf[T](f: => T, max: Option[Int] = None): Seq[T] = items(f, int(max) + 1)
  def seqOf[T](f: => T, max: Int): Seq[T] = seqOf(f, Some(max))

  def from[T](all: Seq[T]): T = {
    assert(all.length > 0, "no items")
    all(int(all.length))
  }

  def from[T](head: T, s: T*): T = from(head +: s)
}

trait HenkiloGenerator extends Generator{

  sealed trait Sukupuoli

  object mies extends Sukupuoli
  object nainen extends Sukupuoli

  def sukupuoli: Sukupuoli = if (int(100) < 48) mies else nainen

  var hetus = Set[String]()

  def hetu: String = {
    val vuosi = 1994 + int(5)
    val kuukausi = int(12) + 1
    val pitkat = Set(1,3,5,7,8,10,12)
    val lyhyet = Set(4,6,9,11)
    def isKarkaus(vuosi:Int) =  vuosi % 4 == 0 && vuosi % 100 != 0

    val maxPaiva = (vuosi, kuukausi) match {
      case (_, kuukausi) if pitkat.contains(kuukausi) => 31
      case (_, kuukausi) if lyhyet.contains(kuukausi) => 30
      case (vuosi, 2) if isKarkaus(vuosi) => 29
      case _ => 28
    }

    val paiva = int(maxPaiva)

    def vuosisataMerkki(vuosi:Int) = if ((vuosi / 100) == 19) "-" else "A"

    def sukupuolita(raw:Int, sukupuoli: Sukupuoli) = sukupuoli match {
      case `mies` => (raw * 2) + 1
      case `nainen` => raw * 2
    }

    val merkit = List("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "A", "B", "C", "D", "E", "F", "H", "J", "K", "L", "M", "N", "P", "R", "S", "T", "U", "V", "W", "X", "Y")
    val start = "%02d%02d%02d9%d%d".format(paiva, kuukausi, vuosi % 100, int(10), sukupuolita(int(5), sukupuoli))
    val tarkastus = start.toInt % 31
    start.substring(0, 6) + vuosisataMerkki(vuosi) + start.substring(6) + merkit(tarkastus)


  }

  def luokka: String = "9" + CHAR


  def Word(min:Int, max: Int): String = CHAR + seqOf(char, min + int((max - min) + 1)).mkString("")



  def sukunimi: String = Word(5, 8)

  def etunimet: Seq[String] = seqOf(Word(5,10), 3)

  def kutsumanimi(etunimet: Seq[String]): String = from(etunimet)

  def kunta: String = "020"

  def aidinkieli: String = kieli

  def kansalaisuus: String = "246"

  def lahiosoite(kunta:String) = "Katu 1 A 1"

  def postinumero(kunta: String, lahisoite:String): String = "00100"

  def yksilollistaminen: String = {
    "ALUEITTAIN"
  }

  def kieli: String = from("FI", "SV")

  def oppilaitos: String = {
    "05127"
  }

  def valmistuminen: String = {
    "2015-06-04"
  }

  def puhelin: String = {
    "09 " + from(0 until 9) + from(0 until 9) + from(0 until 9) + from(0 until 9) + " " + from(0 until 9) + from(0 until 9) + from(0 until 9)
  }

  def matkapuhelin: String = {
    "040 " + from(0 until 9) + from(0 until 9) + from(0 until 9) + from(0 until 9) + " " + from(0 until 9) + from(0 until 9) + from(0 until 9)
  }

  def henkilo(hetu: String): Elem = henkilo(hetu, kunta)

  def henkilo(hetu: String, kunta: String): Elem = henkilo(hetu, kunta, lahiosoite(kunta))

  def henkilo(hetu: String, henkilonKunta: String, lahiosoite: String): Elem =
    henkilo(hetu: String, etunimet, henkilonKunta, lahiosoite, postinumero(kunta, lahiosoite), oppilaitos)


  def henkilo(hetu: String, etunimet: Seq[String], kunta: String, lahiosoite: String, postinumero: String, lahtokoulu: String): Elem =
    <henkilo>
      <hetu>{hetu}</hetu>
      <lahtokoulu>{lahtokoulu}</lahtokoulu>
      <luokka>{luokka}</luokka>
      <sukunimi>{sukunimi}</sukunimi>
      <etunimet>{etunimet.mkString(" ")}</etunimet>
      <kutsumanimi>{kutsumanimi(etunimet)}</kutsumanimi>
      <kotikunta>{kunta}</kotikunta>
      <aidinkieli>{aidinkieli}</aidinkieli>
      <kansalaisuus>{kansalaisuus}</kansalaisuus>
      <lahiosoite>{lahiosoite}</lahiosoite>
      <postinumero>{postinumero}</postinumero>
      <matkapuhelin>{matkapuhelin}</matkapuhelin>
      <muuPuhelin>{puhelin}</muuPuhelin>
      <perusopetus>
        <valmistuminen>{valmistuminen}</valmistuminen>
        <myontaja>{lahtokoulu}</myontaja>
        <suorituskieli>{kieli}</suorituskieli>
        <tila>KESKEN</tila>
        <yksilollistaminen>{yksilollistaminen}</yksilollistaminen>
      </perusopetus>
    </henkilo>

}

object PerustiedotGenerator extends HenkiloGenerator {

  def perustiedot(amount:Int) =
    <perustiedot>
      <eranTunniste>{UUID.randomUUID}</eranTunniste>
      <henkilot>
        {uniqueItems(hetu, amount).map(henkilo)}
      </henkilot>
      </perustiedot>
}
