package fi.vm.sade.hakurekisteri.hakija

import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

class ForkedSeq[A](forked: Seq[Future[A]]) {
  def join(implicit executor: ExecutionContext) = Future.sequence(forked)
}

object ForkedSeq {

  implicit def Seq2Forked[A](s: Seq[Future[A]]): ForkedSeq[A] = new ForkedSeq(s)

}
