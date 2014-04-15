package fi.vm.sade.hakurekisteri.hakija

import scala.concurrent.{ExecutionContext, Future}

class ForkedSeq[A](forked: Seq[Future[A]], executor: ExecutionContext) {
  implicit val ec = executor
  def join() = Future.sequence(forked)
}

object ForkedSeq {

  implicit def Seq2Forked[A](s:Seq[Future[A]])(implicit executor: ExecutionContext): ForkedSeq[A] = new ForkedSeq(s,executor)

}


