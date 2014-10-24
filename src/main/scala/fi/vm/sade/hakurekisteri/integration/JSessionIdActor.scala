package fi.vm.sade.hakurekisteri.integration

import akka.actor.{ActorLogging, Actor}
import akka.pattern.pipe

import scala.concurrent.{ExecutionContext, Future}

case class JSessionKey(serviceUrl: String)
case class JSessionId(created: Long, sessionId: String)

case class SaveJSessionId(key: JSessionKey, sessionId: JSessionId)

class JSessionIdActor() extends Actor with ActorLogging {
  var sessionIdCache: Map[JSessionKey, JSessionId] = Map()

  override def receive: Receive = {
    case key: JSessionKey =>
      sender ! sessionIdCache.get(key)

    case SaveJSessionId(key, sessionId) =>
      sessionIdCache = sessionIdCache + (key -> sessionId)
  }
}
