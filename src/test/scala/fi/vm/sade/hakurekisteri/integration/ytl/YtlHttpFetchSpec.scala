package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

import fi.vm.sade.hakurekisteri.tools.ProgressInputStream
import org.apache.commons.io.IOUtils
import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatra.test.scalatest.ScalatraFunSuite
import org.slf4j.LoggerFactory

@RunWith(classOf[JUnitRunner])
class YtlHttpFetchSpec extends ScalatraFunSuite with YtlMockFixture {
  private val logger = LoggerFactory.getLogger(getClass)
  val config = ytlProperties.addDefault("ytl.http.buffersize", "128")
  val fileSystem = YtlFileSystem(config)
  val ytlHttpFetch = new YtlHttpFetch(config, fileSystem)

  test("zip to students") {
    var bytesRead = 0

    val p = Iterator(getClass.getResource("/s.json").openStream())
      .map(ProgressInputStream(bytesRead += _))

    val students = ytlHttpFetch.streamToStudents(p).map(_._2)
    bytesRead should equal(0)
    var lastReadBytes = 0
    Iterator
      .continually(students.next)
      .takeWhile(_ => students.hasNext)
      .foreach(student => {
        bytesRead should be > lastReadBytes
        logger.info(
          s"Bytes read from ${lastReadBytes} -> $bytesRead while getting ${student.firstnames}"
        )
        lastReadBytes = bytesRead
      })
  }

  test("Fetch one with basic auth") {

    val Some((_, student)) = ytlHttpFetch.fetchOne("050996-9574")
    student.lastname should equal("Vasala")
    student.firstnames should equal("Sampsa")
  }

  test("Fetch many as zip") {
    1.to(10).foreach { n =>
      val groupUuid = UUID.randomUUID().toString
      val students: Iterator[Either[Throwable, (ZipInputStream, Iterator[Student])]] =
        ytlHttpFetch.fetch(groupUuid, List("050996-9574"))

      val (zip, stream) = students.map {
        case Right(x) => x
        case Left(e)  => throw e
      }.next
      stream.size should equal(5)
    }
  }

  test("Memory usage when streaming") {
    val uuid = UUID.randomUUID().toString
    createVeryLargeZip(uuid, uuid)
    val runtime = Runtime.getRuntime()
    System.gc()
    val usedMemoryBefore = runtime.totalMemory() - runtime.freeMemory()
    val all = ytlHttpFetch.zipToStudents(fileSystem.read(uuid).map(new ZipInputStream(_)))
    val first = all.next
    System.gc()
    val usedMemoryAfter = runtime.totalMemory() - runtime.freeMemory()
    println("Memory increased:" + (usedMemoryAfter - usedMemoryBefore))
  }

  test("Single failure is retried") {
    makePostFail(1)
    val students: Iterator[Either[Throwable, (ZipInputStream, Iterator[Student])]] =
      ytlHttpFetch.fetch("1", List("050996-9574"))
    val student = students.next().right.map(_._2.next()).right.get
    student.lastname should equal("Testinen")
    student.firstnames should equal("Jussi Johannes")
  }

  def createVeryLargeZip(groupUuid: String, uuid: String): Unit = {
    val output = fileSystem.asInstanceOf[YtlFileFileSystem].getOutputStream(groupUuid, uuid)
    val zout = new ZipOutputStream(output)
    val entry = new ZipEntry("verylarge.json")
    zout.putNextEntry(entry)
    import org.json4s.jackson.Serialization.write
    implicit val formats = Serialization.formats(NoTypeHints) + KausiDeserializer
    zout.write("[")
    val last = 100000
    for (a <- 1 to last) {
      val json: String = write(
        Student("050996-9574", "", "", None, None, "fi", Nil)
      )
      zout.write(json.getBytes)
      if (a != last) {
        zout.write(",")
      }
      //zout.flush()
      //output.flush()
    }
    zout.write("]")
    zout.closeEntry()
    zout.finish()
    IOUtils.closeQuietly(zout)
    IOUtils.closeQuietly(output)
  }
}
