package fi.vm.sade.hakurekisteri

import java.io._
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipInputStream
import javafx.collections.transformation.SortedList

import akka.actor.{ActorSystem}
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.integration.ytl._
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil}
import fi.vm.sade.hakurekisteri.suoritus.ItseilmoitettuTutkinto
import fi.vm.sade.hakurekisteri.tools.{Zip}
import fi.vm.sade.javautils.httpclient.ApacheOphHttpClient
import fi.vm.sade.scalaproperties.OphProperties
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.{TarArchiveEntry, TarArchiveOutputStream}
import org.apache.commons.io.IOUtils
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import org.json4s.DefaultFormats
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.collection.{immutable, SortedMap}
import scala.concurrent.{Await, Future}
import javax.net.ssl._;
import java.security.{SecureRandom}
import java.security.cert.X509Certificate
import scala.concurrent.duration._
import scala.io.Source
import scala.util.{Failure, Success, Try}
import org.json4s.jackson.Serialization._

import scala.xml.pull.{EvElemStart, XMLEvent, XMLEventReader}

private final class TrustManagerThatTrustsAllCertificates extends X509TrustManager {
  override def getAcceptedIssuers: Array[X509Certificate] = Array()
  override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ()
  override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = ()
}

object SplitXmlIntoChunks {
  def extractFile(file: File): ArrayBuffer[(String, String)] = {
    val input = new FileInputStream(file)
    val in = new InputStreamReader(input, Charset.forName("ISO-8859-1"))
    val ylioppilas = new StringBuilder();
    val buffer = new StringBuilder();
    var start = false
    val t0 = System.currentTimeMillis()
    var hetu = ""
    val y = ArrayBuffer[(String, String)]()

    do {
      val i: Int = in.read()
      if(i == -1) {
        val t = System.currentTimeMillis() - t0;
        println("took " + t + "ms and got " + y.size + " 'ylioppilas'")
        return y;
      };
      val c: Char = i.toChar
      buffer.append(c)
      val searchingForBeginning = !start
      val elementIsDone = '>'.equals(c)
      if(elementIsDone) {
        val element = buffer.toString.trim
        if(searchingForBeginning) {
          if(element.contains("<YLIOPPILAS>")) {
            ylioppilas.append(element)
            start = true
          }

        } else {
          ylioppilas.append(element)
          if(element.contains("</HENKILOTUNNUS>")) {
            hetu = element.stripSuffix("</HENKILOTUNNUS>").trim
          } else if(element.contains("</YLIOPPILAS>")) {
            val ylioppilasXml = ylioppilas.toString()
            y.+=((hetu,ylioppilasXml))
            ylioppilas.setLength(0)
            start = false;
          }
        }

        buffer.setLength(0)
      }
    } while(true)
    throw new RuntimeException("")
  }
}

object YtlProperties extends OphProperties {
  val ssn = System.getProperty("ssn")
  val ytlUuid = System.getProperty("ytl.uuid")
  val suoritusrekisteri_ytl_http_host = System.getProperty("suoritusrekisteri_ytl_http_host")
  val ytlFile = System.getProperty("ytl.file")
  def statusUrl = suoritusrekisteri_ytl_http_host + "/api/oph-transfer/status/$1"
  def bulkUrl = suoritusrekisteri_ytl_http_host +"/api/oph-transfer/bulk"
  def downloadUrl = suoritusrekisteri_ytl_http_host+ "/api/oph-transfer/bulk/$1"
  def fetchOneUrl = suoritusrekisteri_ytl_http_host+ "/api/oph-transfer/student/$1"
  val ytlDonwloadDir = System.getProperty("ytl.download")
  addDefault("ytl.http.download.directory", ytlDonwloadDir)
  addDefault("ytl.http.host.bulk", bulkUrl)
  addDefault("ytl.http.host.download", downloadUrl)
  addDefault("ytl.http.host.fetchone", fetchOneUrl)
  addDefault("ytl.http.host.status", statusUrl)
  addDefault("ytl.http.username",System.getProperty("suoritusrekisteri_ytl_http_username"))
  addDefault("ytl.http.password",System.getProperty("suoritusrekisteri_ytl_http_password"))
}
object TestYtlDataLocally extends App {
  val groupUuid = UUID.randomUUID().toString
  val ssnFinder: String => Future[String] = hetu => Future.successful(hetu)
  lazy val ssnToYlioppilasXml: Map[String, String] = SplitXmlIntoChunks.extractFile(new File(YtlProperties.ytlFile)).toMap
  lazy val slowlyFetchedButCorrectlyFilteredSsns = readYtlFileWithYtlActorParser()
  def readYtlFileWithYtlActorParser(): Seq[String] = {
    YTLXml.findKokelaat(Source.fromFile(YtlProperties.ytlFile, "ISO-8859-1"), ssnFinder)
      .flatMap(f => {
        Await.result(f, 5000.milliseconds);
        val v: Option[Option[Kokelas]] = f.value.flatMap(t => t match {
          case Success(kok) => Some(kok)
          case Failure(f) => None
        })
        v.flatten.map(_.oid)
      })
  }

  implicit val system = ActorSystem()
  implicit val clientEc = ExecutorUtil.createExecutor(1, "ytl-test-pool")
  def xmlToKokelas(xml: String): Option[Kokelas] = {
    val f: Future[Option[Kokelas]] = YTLXml.findKokelaat(Source.fromString(xml), ssnFinder).head
    Await.result(f, 5000.milliseconds);
    val v: Option[Option[Kokelas]] = f.value.flatMap(t => t match {
      case Success(kok) => Some(kok)
      case Failure(f) => None
    })
    v.flatten
  }

  def createInsecureClientBuilder: ApacheOphHttpClient.ApacheHttpClientBuilder  = {
    val builder = ApacheOphHttpClient.createCustomBuilder()
    val ctx = SSLContext.getInstance("TLS");
    val trustManager: TrustManager = new TrustManagerThatTrustsAllCertificates()
    ctx.init(null, Array[TrustManager](trustManager), new SecureRandom());
    builder.httpBuilder.setSSLContext(ctx)
    builder
  }

  val fileSystem = new YtlFileSystem(YtlProperties)
  val ytlHttpFetch = new YtlHttpFetch(YtlProperties,fileSystem,createInsecureClientBuilder)
  val fmt = DateTimeFormat.forPattern("yyyy-MM-dd");
  private def caseClassToMap(cc: Any): SortedMap[String, Any] =
    (SortedMap[String, Any]() /: cc.getClass.getDeclaredFields) {(a, f) =>
      f.setAccessible(true)
      a + (f.getName -> (f.get(cc) match {
        case(v:Seq[Any]) => v.map(caseClassToMap)
        case v: LocalDate => {
          fmt.print(v)
        }
        case any => any
      }))
    }
  def copyWithSortedKokeet(k: Kokelas): Kokelas = {
    def sortByKoe(k1: Koe, k2: Koe): Boolean = {
      val t1 = caseClassToMap(k1).get("koetunnus").map(_.toString).getOrElse("")
      val t2 = caseClassToMap(k2).get("koetunnus").map(_.toString).getOrElse("")
      t1.compareTo(t2) < 0
    }
    val ok1 = k.osakokeet.sortWith(sortByKoe)
    val yo1 = k.yoTodistus.sortWith(sortByKoe)
    k.copy(yoTodistus = yo1, osakokeet = ok1)
  }
  def downloadWithUuidFromYtl(uuid: String) = {
    val s = System.currentTimeMillis()
    val o = fileSystem.write(uuid, uuid)
    val i = ytlHttpFetch.downloadZip(uuid)(uuid).right.get
    IOUtils.copy(i, o)
    IOUtils.closeQuietly(o)
    IOUtils.closeQuietly(i)
    val s0 = System.currentTimeMillis();
    println("Took " + (s0 - s) + "ms")
  }
  def kokelasToBytes(k: Kokelas): Array[Byte] = {
    implicit val formats = DefaultFormats
    writePretty(caseClassToMap(k)).getBytes
  }
  def addEntry(t: TarArchiveOutputStream, entry: String, b: Array[Byte]) = {
    val ehttp = new TarArchiveEntry(entry)
    ehttp.setSize(b.size)
    t.putArchiveEntry(ehttp)
    IOUtils.write(b, t)
    t.closeArchiveEntry()
  }
  def kokelaatToTarBytes(json: String, xml: Option[String]): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    val tar = new TarArchiveOutputStream(out)

    addEntry(tar, "student.json", json.getBytes)
    addEntry(tar, "ylioppilas.xml", xml.getOrElse("").getBytes)

    tar.close()
    out.close()
    out.toByteArray
  }
  def kokelaatToDiffTar(uuid: String, tarFile: String) = {
    val inps = fileSystem.read(uuid).map(new ZipInputStream(_)).toList
    val ytlKokelaat = ytlHttpFetch.zipToStudents(inps.toIterator)

    val diffsOutput = new FileOutputStream(new File(YtlProperties.ytlDonwloadDir,tarFile))
    val diffsTar = new TarArchiveOutputStream(diffsOutput)

    ytlKokelaat.foreach { case (json,student) =>
      val xml = ssnToYlioppilasXml.get(student.ssn)
      addEntry(diffsTar, student.ssn + ".tar", kokelaatToTarBytes(json, xml))
    }
    IOUtils.closeQuietly(diffsTar)
    IOUtils.closeQuietly(diffsOutput)
    inps.foreach(IOUtils.closeQuietly)
    println("done writing tar")
  }
  def findSsn(uuid: String, ssn: String) = {
    val zips = fileSystem.read(uuid).map(new ZipInputStream(_))
    val student: Option[(String,Student)] = ytlHttpFetch.zipToStudents(zips).find { case (json,student) => student.ssn == ssn}
    zips.foreach(IOUtils.closeQuietly)
    student
  }
  def fetchFromYtl() = {
    ytlHttpFetch.fetch(groupUuid, ssnToYlioppilasXml.keys.toList) foreach {
      case Right((zip,a)) =>
        println(a)
        IOUtils.closeQuietly(zip)
      case Left(a) => {
        a.printStackTrace()
        println(a)
      }
    }
  }
  implicit val formats = Student.formatsStudent

  //println(slowlyFetchedButCorrectlyFilteredSsns.size)
  try {

    //ytlHttpFetch.fetch(slowlyFetchedButCorrectlyFilteredSsns)
    //downloadWithUuidFromYtl(YtlProperties.ytlUuid)
    //kokelaatToDiffTar(YtlProperties.ytlUuid,"diffs.tar")
  }catch {
    case e :Throwable => {
      e.printStackTrace()
      println(e.getMessage)
    }
  }

}
