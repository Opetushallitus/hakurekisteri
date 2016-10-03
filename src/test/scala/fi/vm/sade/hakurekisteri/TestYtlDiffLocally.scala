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
import org.joda.time.format.DateTimeFormat
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

  val input = new FileInputStream(new File(path, file))
  val tar = new TarArchiveInputStream(input)

  def iterate(): Iterator[List[(String, String)]] = {
    iterateTar(tar).map {
      case archive =>
        val tar2 = new TarArchiveInputStream(new ByteArrayInputStream(ByteStreams.toByteArray(tar)))
        iterateTar(tar2).map(e => (e.getName, new String(ByteStreams.toByteArray(tar2)))).toList
    }
  }

  def findSsn(): List[(String, String)]  = {
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
    }
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
  }

  //
  val limit = DateFormat.fmt.parseLocalDate("2007-01-01")

  def toKokelaat(studentJson: String, xmlKokelas: String): (Kokelas,Kokelas, String) = {
    def fileToStringFromSsn(ssn: String) = IOUtils.toString(new FileInputStream(new File(ssn + ".json")))
    def parse(s: String) = {
      implicit val formats = Student.formatsStudent
      import org.json4s.jackson.Serialization._
      read[Student](s)
    }
    val student = parse(studentJson)
    val kokelasFromJson = StudentToKokelas.convert(student.ssn, student)
    val kokelasFromXml = xmlToKokelas(xmlKokelas).get
    (kokelasFromJson, kokelasFromXml, student.ssn)
  }

  case class Loytyy(aineLoytyy: ArrayBuffer[String] = ArrayBuffer[String](),
                    aineLoytyyMyonnettySamaanAikaan: ArrayBuffer[String] = ArrayBuffer[String](),
                    aineJaKoetunnusLoytyy: ArrayBuffer[String] = ArrayBuffer[String](),
                    aineJaKoetunnusJaAineyhdistelmarooliLoytyy: ArrayBuffer[String] = ArrayBuffer[String](),
                    aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaLoytyy: ArrayBuffer[String] = ArrayBuffer[String]()) {
    override def toString = {
      s"(aineLoytyy: ${aineLoytyy.size}, aineLoytyyMyonnettySamaanAikaan: ${aineLoytyyMyonnettySamaanAikaan.size}, aineJaKoetunnusLoytyy: ${aineJaKoetunnusJaAineyhdistelmarooliLoytyy.size}, aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaLoytyy: ${aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaLoytyy.size})"
    }
  }
  case class OsakoeVsYoTodistus(osakoe: Loytyy = Loytyy(), yotodistus: Loytyy = Loytyy())
  case class JsonVsXmlLoytyy(json: OsakoeVsYoTodistus = OsakoeVsYoTodistus(), xml: OsakoeVsYoTodistus = OsakoeVsYoTodistus())

  val jsonVsXmlLoytyy = JsonVsXmlLoytyy()

  //Iterator(findSsn())
  //iterate()
  iterate().foreach{
    case List((jsonFile, studentJson), (httpFile, httpKokelas), (xmlFile, xmlKokelas), (xmlJson,xmlJsonKokelas)) =>
      val (kJson, kXml, ssn) = toKokelaat(studentJson, xmlKokelas)

      val osakokeetJson = osakokeet(kJson).filter(_.myonnetty.isAfter(limit)).filter(filterOsakoeImprobatur)
      val yoTodistusJson = yoTodistukset(kJson).filter(_.myonnetty.isAfter(limit)).filter(filterImprobatur)
      val yoTodistusXml = yoTodistukset(kXml).filter(_.myonnetty.isAfter(limit))
      val osakokeetXml = osakokeet(kXml).filter(_.myonnetty.isAfter(limit))
      val uniqueOsakoeJsonFunc = uniqueOsakoeLeft(osakokeetJson, osakokeetXml)_
      val uniqueOsakoeXmlFunc = uniqueOsakoeLeft(osakokeetXml, osakokeetJson)_
      val uniqueYoTodJsonFunc = uniqueLeft(yoTodistusJson, yoTodistusXml)_
      val uniqueYoTodXmlFunc = uniqueLeft(yoTodistusXml, yoTodistusJson)_

      def aineLoytyy(y:Osakoe) = (y.aine.aine, y.aine.lisatiedot)
      def aineLoytyyMyonnettySamaanAikaan(y:Osakoe) = (y.aine.aine, y.aine.lisatiedot,y.myonnetty)
      def aineJaKoetunnusLoytyy(y:Osakoe) = (y.aine.aine, y.aine.lisatiedot,y.koetunnus)
      def aineJaKoetunnusJaAineyhdistelmarooliLoytyy(y:Osakoe) = (y.aine.aine, y.aine.lisatiedot,y.koetunnus,y.aineyhdistelmarooli)
      def aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaLoytyy(y:Osakoe) = (y.aine.aine, y.aine.lisatiedot,y.koetunnus,y.aineyhdistelmarooli,y.arvio.arvosana)

      def aineLoytyyYo(y:YoKoe) = (y.aine.aine, y.aine.lisatiedot)
      def aineLoytyyMyonnettySamaanAikaanYo(y:YoKoe) = (y.aine.aine, y.aine.lisatiedot,y.myonnetty)
      def aineJaKoetunnusLoytyyYo(y:YoKoe) = (y.aine.aine, y.aine.lisatiedot,y.koetunnus)
      def aineJaKoetunnusJaAineyhdistelmarooliLoytyyYo(y:YoKoe) = (y.aine.aine, y.aine.lisatiedot,y.koetunnus,y.aineyhdistelmarooli)
      def aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaJaPisteetLoytyyYo(y:YoKoe) = (y.aine.aine, y.aine.lisatiedot,y.koetunnus,y.aineyhdistelmarooli,y.arvio.arvosana,y.arvio.pisteet)

      if(!uniqueOsakoeJsonFunc(aineLoytyy).isEmpty) jsonVsXmlLoytyy.json.osakoe.aineLoytyy.+=(ssn)
      if(!uniqueOsakoeJsonFunc(aineLoytyyMyonnettySamaanAikaan).isEmpty) jsonVsXmlLoytyy.json.osakoe.aineLoytyyMyonnettySamaanAikaan.+=(ssn)
      if(!uniqueOsakoeJsonFunc(aineJaKoetunnusLoytyy).isEmpty) jsonVsXmlLoytyy.json.osakoe.aineJaKoetunnusLoytyy.+=(ssn)
      if(!uniqueOsakoeJsonFunc(aineJaKoetunnusJaAineyhdistelmarooliLoytyy).isEmpty) jsonVsXmlLoytyy.json.osakoe.aineJaKoetunnusJaAineyhdistelmarooliLoytyy.+=(ssn)
      if(!uniqueOsakoeJsonFunc(aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaLoytyy).isEmpty) jsonVsXmlLoytyy.json.osakoe.aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaLoytyy.+=(ssn)

      if(!uniqueYoTodJsonFunc(aineLoytyyYo).isEmpty) jsonVsXmlLoytyy.json.yotodistus.aineLoytyy.+=(ssn)
      if(!uniqueYoTodJsonFunc(aineLoytyyMyonnettySamaanAikaanYo).isEmpty) jsonVsXmlLoytyy.json.yotodistus.aineLoytyyMyonnettySamaanAikaan.+=(ssn)
      if(!uniqueYoTodJsonFunc(aineJaKoetunnusLoytyyYo).isEmpty) jsonVsXmlLoytyy.json.yotodistus.aineJaKoetunnusLoytyy.+=(ssn)
      if(!uniqueYoTodJsonFunc(aineJaKoetunnusJaAineyhdistelmarooliLoytyyYo).isEmpty) jsonVsXmlLoytyy.json.yotodistus.aineJaKoetunnusJaAineyhdistelmarooliLoytyy.+=(ssn)
      if(!uniqueYoTodJsonFunc(aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaJaPisteetLoytyyYo).isEmpty) jsonVsXmlLoytyy.json.yotodistus.aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaLoytyy.+=(ssn)

      if(!uniqueOsakoeXmlFunc(aineLoytyy).isEmpty) jsonVsXmlLoytyy.xml.osakoe.aineLoytyy.+=(ssn)
      if(!uniqueOsakoeXmlFunc(aineLoytyyMyonnettySamaanAikaan).isEmpty) jsonVsXmlLoytyy.xml.osakoe.aineLoytyyMyonnettySamaanAikaan.+=(ssn)
      if(!uniqueOsakoeXmlFunc(aineJaKoetunnusLoytyy).isEmpty) jsonVsXmlLoytyy.xml.osakoe.aineJaKoetunnusLoytyy.+=(ssn)
      if(!uniqueOsakoeXmlFunc(aineJaKoetunnusJaAineyhdistelmarooliLoytyy).isEmpty) jsonVsXmlLoytyy.xml.osakoe.aineJaKoetunnusJaAineyhdistelmarooliLoytyy.+=(ssn)
      if(!uniqueOsakoeXmlFunc(aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaLoytyy).isEmpty) jsonVsXmlLoytyy.xml.osakoe.aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaLoytyy.+=(ssn)

      if(!uniqueYoTodXmlFunc(aineLoytyyYo).isEmpty) jsonVsXmlLoytyy.xml.yotodistus.aineLoytyy.+=(ssn)
      if(!uniqueYoTodXmlFunc(aineLoytyyMyonnettySamaanAikaanYo).isEmpty) jsonVsXmlLoytyy.xml.yotodistus.aineLoytyyMyonnettySamaanAikaan.+=(ssn)
      if(!uniqueYoTodXmlFunc(aineJaKoetunnusLoytyyYo).isEmpty) jsonVsXmlLoytyy.xml.yotodistus.aineJaKoetunnusLoytyy.+=(ssn)
      if(!uniqueYoTodXmlFunc(aineJaKoetunnusJaAineyhdistelmarooliLoytyyYo).isEmpty) jsonVsXmlLoytyy.xml.yotodistus.aineJaKoetunnusJaAineyhdistelmarooliLoytyy.+=(ssn)
      if(!uniqueYoTodXmlFunc(aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaJaPisteetLoytyyYo).isEmpty) jsonVsXmlLoytyy.xml.yotodistus.aineJaKoetunnusJaAineyhdistelmarooliJaArvosanaLoytyy.+=(ssn)
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

  println(jsonVsXmlLoytyy.toString)
}
