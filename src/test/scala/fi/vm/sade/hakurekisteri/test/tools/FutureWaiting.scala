package fi.vm.sade.hakurekisteri.test.tools

import org.scalatest.concurrent.AsyncAssertions
import scala.concurrent.{ExecutionContext, Future}
import org.scalatest.time.{Millis, Span}

trait FutureWaiting extends AsyncAssertions {

  val span = Span(10000, Millis)

  implicit val ec:ExecutionContext = ExecutionContext.Implicits.global


  def waitFuture[A](f: Future[A])(assertion: A => Unit) = {
    val w = new Waiter

    f.onComplete(r => {
      w(assertion(r.get))
      w.dismiss()
    })


    w.await(timeout(span), dismissals(1))
  }

  import org.scalatest.Assertions.intercept

  def expectFailure[F <: AnyRef : Manifest](f:Future[_]) = {
    val w = new Waiter

    f.onComplete(r => {
      w(intercept[F](r.get))
      w.dismiss()
    })


    w.await(timeout(span), dismissals(1))


    }

}
