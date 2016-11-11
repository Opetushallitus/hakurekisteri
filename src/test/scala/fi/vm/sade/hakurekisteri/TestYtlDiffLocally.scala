package fi.vm.sade.hakurekisteri

import java.io._
import java.util.Date

import akka.actor.ActorSystem
import com.google.common.io.ByteStreams
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.ytl._
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.io.IOUtils
import org.joda.time.LocalDate
import org.joda.time.LocalDate.Property
import org.joda.time.format.DateTimeFormat
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._

import scala.concurrent.{Await, Future}
import scala.io.Source
import scala.util.{Failure, Success}

object DateFormat {
  val fmt = DateTimeFormat.forPattern("yyyy-MM-dd");

  def print(o:Option[Date]): String = {
    o.map(d => fmt.print(d.getTime)).getOrElse("None")
  }
}
object TestYtlDiffLocally extends App {

  val path = System.getProperty("path")
  val file = System.getProperty("file")
  val ssn = System.getProperty("ssn")
  implicit val system = ActorSystem()
  implicit val clientEc = ExecutorUtil.createExecutor(1, "ytl-test-pool")
  val oidFinder: String => Future[String] = hetu => Future.successful(hetu)
  def iterateTar(t: TarArchiveInputStream): Iterator[ArchiveEntry] = {
    Iterator.continually(t.getNextEntry).takeWhile(_ != null)
  }

  val input = new FileInputStream(new File(file))
  val tar = new TarArchiveInputStream(input)

  def iterate(): Iterator[List[(String, String)]] = {
    iterateTar(tar).map {
      case archive =>
        val tar2 = new TarArchiveInputStream(new ByteArrayInputStream(ByteStreams.toByteArray(tar)))
        iterateTar(tar2).map(e => (e.getName, new String(ByteStreams.toByteArray(tar2)))).toList
    }
  }

  def findSsn(s: String = ssn): List[(String, String)]  = {
    iterateTar(tar).find(_.getName.contains(ssn)) match {
      case Some(archive) =>
        val tar2 = new TarArchiveInputStream(new ByteArrayInputStream(ByteStreams.toByteArray(tar)))
        iterateTar(tar2).map(e => (e.getName, new String(ByteStreams.toByteArray(tar2)))).toList
      case None =>
        throw new RuntimeException(s"Person with SSN $ssn not found!")
    }
  }

  def uniqueLeft(o1: Seq[YoKoe], o2: Seq[YoKoe])(converter: YoKoe => Product): Seq[YoKoe] = {
    def eqFunc: (YoKoe, Seq[YoKoe]) => Boolean = (y,yseq) => yseq.map(converter).contains(converter(y))
    o1.filter(o => !eqFunc(o, o2))
  }

  def uniqueOsakoeLeft(o1: Seq[Osakoe with ArvioArvosana], o2: Seq[Osakoe with ArvioArvosana])(converter: Osakoe with ArvioArvosana => Product): Seq[Osakoe with ArvioArvosana] = {
    def eqFunc: (Osakoe with ArvioArvosana, Seq[Osakoe with ArvioArvosana]) => Boolean = (o,oseq) => oseq.map(converter).contains(converter(o))
    o1.filter(o => !eqFunc(o, o2))
  }

  def xmlToKokelas(xml: String): Option[Kokelas] = {
    val f: Future[Option[Kokelas]] = YTLXml.findKokelaat(Source.fromString(xml), oidFinder).head
    Await.result(f, 5000.milliseconds);
    val v: Option[Option[Kokelas]] = f.value.flatMap(t => t match {
      case Success(kok) => Some(kok)
      case Failure(f) => None
    })
    v.flatten
  }

  def yoTodistukset(k: Kokelas): Seq[YoKoe] = {
    k.yoTodistus.flatMap {
      case y: YoKoe => Some(y)
      case _ => None
    }.sortBy(a => (a.aine.aine, a.koetunnus))
  }
  def osakokeet(k: Kokelas): Seq[Osakoe with ArvioArvosana] = {

    val yot: Seq[YoKoe] = yoTodistukset(k)
    k.osakokeet.flatMap {
      case o: Osakoe => {
        val vastaavaYoTodistus = yot.find(y => y.koetunnus == o.koetunnus && y.aineyhdistelmarooli == o.aineyhdistelmarooli && y.aine.aine == o.aine.aine && y.aine.lisatiedot == o.aine.lisatiedot).get
        Some(new Osakoe(o.arvio, o.koetunnus, o.osakoetunnus, o.aineyhdistelmarooli, o.myonnetty)  with ArvioArvosana {
          def arvionArvosana = vastaavaYoTodistus.arvio.arvosana
        })
      }
      case _ => None
    }
  }
  def filterImprobatur(k: YoKoe):Boolean = {
    val improbatur = Array("I+", "I", "I-", "I=")
    !improbatur.contains(k.arvio.arvosana)
  }
  def filterOsakoeImprobatur(k: Osakoe with ArvioArvosana):Boolean = {
    val improbatur = Array("I+", "I", "I-", "I=")
    !improbatur.contains(k.arvionArvosana)
  }
  trait ArvioArvosana {
    def arvionArvosana: String

    override def toString(): String = super.toString + s" + Arvosana($arvionArvosana)"
  }

  //
  val limit = DateFormat.fmt.parseLocalDate("2007-01-01")

  def toKokelaat(studentJson: String, xmlKokelas: String): (Kokelas,Option[Kokelas], String) = {
    def fileToStringFromSsn(ssn: String) = IOUtils.toString(new FileInputStream(new File(ssn + ".json")))
    def parse(s: String) = {
      implicit val formats = Student.formatsStudent
      import org.json4s.jackson.Serialization._
      read[Student](s)
    }
    val student = parse(studentJson)
    val kokelasFromJson = StudentToKokelas.convert(student.ssn, student)
    val kokelasFromXml = xmlToKokelas(xmlKokelas)
    (kokelasFromJson, kokelasFromXml, student.ssn)
  }

  trait Eroavaisuus {}
  case class Impro(kausi: Kausi, ssn: String) extends Eroavaisuus;
  case class Uniikki(kausi: Kausi, ssn: String) extends Eroavaisuus;
  case class Korotus(kausi: Kausi, ssn: String) extends Eroavaisuus;

  val eroavaisuudetToXml: ArrayBuffer[Eroavaisuus] = ArrayBuffer[Eroavaisuus]()
  val collectSsn = ArrayBuffer[String]()
  val missing = ArrayBuffer[String]()
  def analysoi(osa1: Seq[Osakoe with ArvioArvosana], osa2: Seq[Osakoe with ArvioArvosana]) = {
    def tunnukset(y:Osakoe) = (y.koetunnus, y.osakoetunnus, y.aineyhdistelmarooli)
    val taysinUniikit = osa1.filter(o => !osa2.map(tunnukset).contains(tunnukset(o)))
    val eiImproja = taysinUniikit.filter(filterOsakoeImprobatur).sortBy(o => (o.koetunnus,o.osakoetunnus))
    val vainImprot = taysinUniikit.filter(!eiImproja.contains(_)).sortBy(o => (o.koetunnus,o.osakoetunnus))
    val korotukset = osa1.filter(!eiImproja.contains(_)).filter(o => !osa2.contains(o)).sortBy(o => (o.koetunnus,o.osakoetunnus))
    (vainImprot, eiImproja, korotukset)
  }
  def analysoiYot(y1: Seq[YoKoe], y2: Seq[YoKoe]) = {
    def tunnukset(y:YoKoe) = (y.aine.aine, y.aine.lisatiedot, y.koetunnus, y.aineyhdistelmarooli)
    y1.filter(y => !y2.map(tunnukset).contains(tunnukset(y)))
  }
  //Iterator(findSsn())
  //iterate()
  iterate().foreach{
    case List((jsonFile, studentJson), (xmlFile, xmlKokelas)) =>
      val (kJson, kXmlOpt, ssn) = toKokelaat(studentJson, xmlKokelas)
      kXmlOpt match {
        case Some(kXml) =>
          val (_, osakoeUniikit,_) = analysoi(osakokeet(kXml), osakokeet(kJson))
          val uniikit = analysoiYot(yoTodistukset(kXml), yoTodistukset(kJson))
          //val (improt1, uniikit1, korotukset1) = analysoi(osakokeet(kJson), osakokeet(kXml))
          //val (improt1, uniikit1, korotukset1) = analysoi(osakokeet(kXml), osakokeet(kJson))
          /*
          def toKausi(l:LocalDate) = if(l.getMonthOfYear != 1) Syksy(l.getYear) else Kevat(l.getYear)
          val kaikki = Seq(improt1.map(o => Impro(toKausi(o.myonnetty),ssn)) ++ uniikit1.map(o => Uniikki(toKausi(o.myonnetty),ssn)) ++ korotukset1.map(o => Korotus(toKausi(o.myonnetty),ssn))).flatten
          val erot = uniikit1.filter(_.myonnetty.isAfter(limit)).map(o => Uniikki(toKausi(o.myonnetty),ssn))
          */
          if(!uniikit.isEmpty || !osakoeUniikit.isEmpty) {
            val yoJson = yoTodistukset(kJson)
            val yoXml = yoTodistukset(kXml)
            //eroavaisuudetToXml ++= Seq(erot.head)
            collectSsn += ssn
          } else {

          }
        case None => {
          val u = osakokeet(kJson).filter(filterOsakoeImprobatur).sortBy(o => (o.koetunnus,o.osakoetunnus))
          val bothEmpty = u.isEmpty && yoTodistukset(kJson).filter(filterImprobatur).isEmpty
          if(!bothEmpty) {
            missing += ssn

          }
          //missing += ssn
        }
      }

  }

  def writeSsnsToFile(file: String, ssnBuffer: ArrayBuffer[String]): Unit = {
    println(s"starting to write file $file")
    val ssnSet = ssnBuffer.toSet
    val fw = new FileWriter(new File(file))
    ssnSet.foreach(ssn => {
      fw.append(ssn)
      fw.append(System.lineSeparator())
    })
    IOUtils.closeQuietly(fw)
  }
  //println(collectSsn.size)
  //writeSsnsToFile("osakokeetPuuttuu.txt", collectSsn)
}
