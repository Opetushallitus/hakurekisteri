package fi.vm.sade.hakurekisteri.test.tools

import akka.actor.Actor
import fi.vm.sade.hakurekisteri.rest.support.Query

import scala.concurrent.{ExecutionContext, Future}

class FailingResourceActor[T: Manifest] extends Actor {
  import akka.pattern.PipeableFuture
  implicit val ec: ExecutionContext = context.system.dispatcher
  def pipe[A](future: Future[A])(executionContext: ExecutionContext): PipeableFuture[A] = {
    new PipeableFuture(future)
  }
  override def receive: Receive = {
    case q: Query[T] => pipe(Future.failed(new Exception("test query exception")))(ec) pipeTo sender

    case r: T => pipe(Future.failed(new Exception("test save exception")))(ec) pipeTo sender

    case a: Any => println(s"got unrecognised message $a")
  }
}
