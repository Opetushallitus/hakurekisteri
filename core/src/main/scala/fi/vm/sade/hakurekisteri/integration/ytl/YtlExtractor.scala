package fi.vm.sade.hakurekisteri.integration.ytl

import scala.concurrent.{Await, Future, ExecutionContext}
import scala.compat.Platform
import scala.io.Source


object YtlExtractor {

    def main(args: Array[String]) {
      import scala.concurrent.ExecutionContext.Implicits.global
      println(s"start: ${Platform.currentTime}")
      println("###############################")

      println(parseFile(args(0)).toList)

      println("###############################")

      println(s"end: ${Platform.currentTime}")






    }

  def parseFile(file:String)(implicit ec: ExecutionContext):Seq[Kokelas]  = {
    val results = YTLXml.findKokelaat(Source.fromFile(file,"ISO-8859-1"), Future.successful)
    Await.result(Future.sequence(results), scala.concurrent.duration.Duration.Inf).flatten
  }

}
