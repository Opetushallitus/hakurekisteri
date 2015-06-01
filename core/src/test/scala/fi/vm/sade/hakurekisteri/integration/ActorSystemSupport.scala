package fi.vm.sade.hakurekisteri.integration

import akka.actor.ActorSystem

import scala.concurrent.duration._
import scala.util.Random

trait ActorSystemSupport {
  def withSystem(f: ActorSystem => Unit) = {
    val system = ActorSystem(s"test-system-${Random.nextLong()}")

    f(system)

    system.shutdown()
    system.awaitTermination(15.seconds)
  }
}
