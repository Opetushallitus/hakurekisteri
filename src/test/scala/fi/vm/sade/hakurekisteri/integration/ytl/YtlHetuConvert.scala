package fi.vm.sade.hakurekisteri.integration.ytl

import scala.io.Source
import scala.xml.pull.{EvElemEnd, EvText, EvElemStart, XMLEventReader}
import scala.annotation.tailrec
import scala.collection.mutable
import java.io.File

/**
  * Created by verneri on 17.4.15.
  */
object YtlHetuConvert {

  def main(args: Array[String]) {

    val req = args(0)

    val orig = args(1)

    val reader = new XMLEventReader(Source.fromFile(req, "ISO-8859-1"))

    @tailrec def loop(acc: List[String] = List()): List[String] =
      reader.collectFirst { case EvElemStart(_, "Hetu", _, _) =>
        reader.collectFirst { case EvText(text) => text }
      }.flatten match {
        case Some(text) => loop(acc ++ List(text))
        case None       => acc
      }

    val hetus: mutable.MutableList[String] = new mutable.MutableList[String] ++ loop()

    val reader2 = new XMLEventReader(Source.fromFile(orig, "ISO-8859-1"))

    val file = new File("result.xml")
    val p = new java.io.PrintWriter(file)

    def writeloop(path: Seq[String]) {
      println(path)
      val b = reader2.hasNext
      println(reader2.hasNext)
      println(b)
      if (b)
        if (path == Seq("YLIOPPILAAT") && hetus.isEmpty)
          p.print("</YLIOPPILAAT>")
        else
          reader2.next match {

            case e: EvElemStart =>
              p.print(s"<${e.label}>")
              writeloop(e.label +: path)
            case EvElemEnd(_, label) =>
              p.print(s"</${label}>")
              writeloop(path.tail)

            case EvText(hetu) if path.head == "HENKILOTUNNUS" =>
              val topHetu = hetus.head
              hetus.drop(1)
              p.print(topHetu)
              writeloop(path)
            case EvText(text) =>
              p.print(text)
              writeloop(path)
            case event =>
              println(s"unexpected xml event: $event")
              writeloop(path)

          }
    }

    println("start convert")
    try {
      writeloop(Seq())

    } finally {
      p.close()
    }
    println("converted")
    reader.stop()
  }
}
