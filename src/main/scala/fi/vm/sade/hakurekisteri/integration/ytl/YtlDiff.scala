package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.FileWriter

import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization._

object YtlDiff {

  implicit val formats = DefaultFormats

  def writeKokelaatAsJson(kokelaat: Seq[Option[Kokelas]], filename: String) = {
    val writer = new FileWriter(filename)
    writePretty(kokelaat, writer)
    writer.close()
  }

}
