package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.FileWriter

import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization._
import org.slf4j.LoggerFactory

object YtlDiff {
  private val logger = LoggerFactory.getLogger(getClass)

  implicit val formats = DefaultFormats

  def writeKokelaatAsJson(kokelaat: Iterator[Kokelas], filename: String) = {
    if(!kokelaat.hasNext) {
      logger.warn(s"Got zero kokelaat from YTL!")
    } else {
      val writer = new FileWriter(filename)
      writer.append("[")
      writer.append(writePretty(kokelaat.next))
      for(kokelas <- kokelaat) {
        writer.append(",")
        writer.append(writePretty(kokelas))
      }
      writer.append("]")
      writer.close()
    }
  }

}
