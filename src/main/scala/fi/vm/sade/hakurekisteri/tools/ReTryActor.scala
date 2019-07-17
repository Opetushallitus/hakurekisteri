// inspired by com.codetinkerhack

package fi.vm.sade.hakurekisteri.tools

import akka.actor.{ ActorRef, Props, Actor, ActorLogging }
import akka.pattern.ask
import scala.concurrent.duration._
import scala.util.Success

object ReTryActor {
  private case class Retry(originalSender: ActorRef, message: Any, times: Int)

  private case class Response(originalSender: ActorRef, result: Any)

  def props(tries: Int, retryTimeOut: FiniteDuration, retryInterval: FiniteDuration, forwardTo: ActorRef): Props = Props(new ReTryActor(tries: Int, retryTimeOut: FiniteDuration, retryInterval: FiniteDuration, forwardTo: ActorRef))

}

class ReTryActor(val tries: Int, retryTimeOut: FiniteDuration, retryInterval: FiniteDuration, forwardTo: ActorRef) extends Actor with ActorLogging {

  import context.dispatcher
  import ReTryActor._

  // Retry loop that keep on Re-trying the request
  def retryLoop: Receive = {

    // Response from future either Success or Failure is a Success - we propagate it back to a original sender
    case Response(originalSender, result) =>
      originalSender ! result
      context stop self

    case Retry(originalSender, message, triesLeft) =>

      // Process (Re)try here. When future completes it sends result to self
      (forwardTo ? message) (retryTimeOut) onComplete {

        case Success(result) =>
          self ! Response(originalSender, result) // sending responses via self synchronises results from futures that may come potentially in any order. It also helps the case when the actor is stopped (in this case responses will become deadletters)

        case scala.util.Failure(ex) =>
          if (triesLeft - 1 == 0) {// In case of last try and we got a failure (timeout) lets send Retries exceeded error
            self ! Response(originalSender, akka.actor.Status.Failure(new Exception("Retries exceeded")))
          }
          else
            log.error("Error occurred: " + ex)
      }

      // Send one more retry after interval
      if (triesLeft - 1 > 0)
        context.system.scheduler.scheduleOnce(retryInterval, self, Retry(originalSender, message, triesLeft - 1))

    case m @ _ =>
      log.error("No handling defined for message: " + m)

  }

  // Initial receive loop
  def receive: Receive = {

    case message @ _ =>
      self ! Retry(sender, message, tries)

      // Lets swap to a retry loop here.
      context.become(retryLoop)

  }

}
