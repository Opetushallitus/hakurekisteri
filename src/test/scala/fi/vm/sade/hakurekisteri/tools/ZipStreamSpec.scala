package fi.vm.sade.hakurekisteri.tools

import java.io.{SequenceInputStream, InputStream, FilterInputStream, ByteArrayInputStream}

import com.google.common.io.ByteStreams
import fi.vm.sade.hakurekisteri.integration.ytl.Student
import fi.vm.sade.hakurekisteri.integration.ytl.Student.StudentAsyncParser
import jawn.AsyncParser
import jawn.ast.{JParser, JValue}
import org.apache.commons.io.input.ProxyInputStream
import org.json4s.jackson.JsonMethods._
import org.scalatra.test.scalatest.ScalatraFunSuite
import org.slf4j.LoggerFactory

import scala.util.Try

class ZipStreamSpec extends ScalatraFunSuite {
  private val logger = LoggerFactory.getLogger(getClass)

  implicit val formats = Student.formatsStudent

  case class InfiniteInputStream(sample: Array[Byte]) extends InputStream {
    var pos = 0L
    override def read(): Int = {
      val ind = pos.toInt % sample.length
      pos += 1
      sample(ind)
    }
  }

  test("should handle chunk halving long utf8 character") {
    val studentJson =
      """
        |[
        |{
        |"ssn": "s", "lastname": "\u0628", "firstnames": "f", "language": "fi"
        |}
        |]
      """.stripMargin
    val (s1, s2) = studentJson.getBytes.splitAt(31)
    new String(s1).contains("\u0628") should equal(false)
    new String(s2).contains("\u0628") should equal(false)
    val asyncParser = StudentAsyncParser()
    asyncParser.feedChunk(s1)
    val result = asyncParser.feedChunk(s2)

    result.map(_._2).head.get.lastname should equal("\u0628")
  }

  private def inifiniteStudentJsonStream: InputStream = new SequenceInputStream(
    new ByteArrayInputStream("["),
    new InfiniteInputStream(studentJson + ",")
  )

  private def readFromStream(len: Int, s: InputStream) = {
    val b = new Array[Byte](len)
    ByteStreams.read(s, b, 0, b.length)
    b
  }

  private def memoryUsageBeforeAfter(callback: () => Unit): Long = {
    val runtime = Runtime.getRuntime()
    System.gc()
    val usedMemoryBefore = runtime.totalMemory() - runtime.freeMemory()
    callback()
    System.gc()
    val usedMemoryAfter = runtime.totalMemory() - runtime.freeMemory()
    val difference: Long = (usedMemoryAfter - usedMemoryBefore)
    logger.info(s"Memory increased: ${difference}")
    difference
  }
  def studentJson: String = {
    val json =
      scala.io.Source.fromFile(getClass.getResource("/ytl-student.json").getFile).getLines.mkString
    compact(render(parse(json)))
  }

}
