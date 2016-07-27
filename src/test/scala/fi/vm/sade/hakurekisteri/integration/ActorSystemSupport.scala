package fi.vm.sade.hakurekisteri.integration

import akka.actor.ActorSystem

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Random

trait ActorSystemSupport {
  def withSystem(f: ActorSystem => Unit) = {
    val system = ActorSystem(s"test-system-${Random.nextLong()}")

    f(system)

    Await.result(system.terminate(), 15.seconds)
  }
}
