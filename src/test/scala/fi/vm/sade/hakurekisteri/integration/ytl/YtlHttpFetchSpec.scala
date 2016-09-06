package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID
import java.util.zip.{ZipEntry, ZipOutputStream}

import fi.vm.sade.hakurekisteri.rest.support.{StudentDeserializer, KausiDeserializer}
import fi.vm.sade.scalaproperties.OphProperties
import org.apache.commons.io.IOUtils
import org.json4s.NoTypeHints
import org.json4s.jackson.Serialization
import org.scalatra.test.scalatest.ScalatraFunSuite


class YtlHttpFetchSpec extends ScalatraFunSuite with YtlMockFixture {
  val config = new OphProperties()
    .addDefault("ytl.http.host.bulk", bulkUrl)
    .addDefault("ytl.http.host.download", downloadUrl)
    .addDefault("ytl.http.host.fetchone", fetchOneUrl)
    .addDefault("ytl.http.host.status", statusUrl)
    .addDefault("ytl.http.username", username)
    .addDefault("ytl.http.password", password)
  val fileSystem = new YtlFileSystem(config)
  val ytlHttpFetch = new YtlHttpFetch(config,fileSystem)

  test("zip to students") {

    ytlHttpFetch.zipToStudents(getClass.getResource("/student-results.zip").openStream())
    "ok" should equal ("ok")
  }

  test("Fetch one with basic auth") {

    val student = ytlHttpFetch.fetchOne("050996-9574")
    student.lastname should equal ("Vasala")
    student.firstnames should equal ("Sampsa")
  }

  test("Fetch many as zip") {
    val students = ytlHttpFetch.fetch(List("050996-9574"))

    students.right.get.size should equal (7)
  }

  test("Memory usage when streaming") {
    val uuid = UUID.randomUUID().toString
    createVeryLargeZip(uuid)
    val runtime = Runtime.getRuntime()
    System.gc()
    val usedMemoryBefore = runtime.totalMemory() - runtime.freeMemory()
    val all = ytlHttpFetch.zipToStudents(fileSystem.read(uuid))
    val first = all.head
    System.gc()
    val usedMemoryAfter = runtime.totalMemory() - runtime.freeMemory()
    println("Memory increased:" + (usedMemoryAfter - usedMemoryBefore))
  }


  def createVeryLargeZip(uuid: String): Unit = {
    val output = fileSystem.write(uuid)
    val zout = new ZipOutputStream(output)
    val entry = new ZipEntry("verylarge.json")
    zout.putNextEntry(entry)
    import org.json4s.jackson.Serialization.{write}
    implicit val formats = Serialization.formats(NoTypeHints) + new KausiDeserializer
    zout.write("[")
    val last = 100000
    for (a <- 1 to last) {
      val json: String = write(Student("050996-9574", "", "", None, None, None, None, None, Nil))
      zout.write(json.getBytes)
      if(a != last) {
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
