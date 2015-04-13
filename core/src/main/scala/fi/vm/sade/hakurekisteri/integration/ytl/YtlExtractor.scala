package fi.vm.sade.hakurekisteri.integration.ytl

import scala.concurrent.{Await, Future, ExecutionContext}
import scala.compat.Platform
import scala.io.Source


object YtlExtractor {

    def main(args: Array[String]) {
      import scala.concurrent.ExecutionContext.Implicits.global
      parseFile(args(0))





    def parseFile(file:String)(implicit ec: ExecutionContext):Seq[Kokelas]  = {
      println(s"start: ${Platform.currentTime}")
      val results = YTLXml.findKokelaat(Source.fromFile(file,"ISO-8859-1"), Future.successful)
      val result = Await.result(Future.sequence(results), scala.concurrent.duration.Duration.Inf)
      println(s"end: ${Platform.currentTime}")
      result
    }
  }

}
