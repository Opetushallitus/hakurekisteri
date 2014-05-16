package fi.vm.sade.hakurekisteri.audit

import fi.vm.sade.log.model.{Tapahtuma, LogEvent}
import java.io.ByteArrayOutputStream
import java.beans.XMLEncoder
import akka.camel.{Producer, CamelMessage}
import akka.actor.Actor
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified}
import fi.vm.sade.hakurekisteri.organization.{AuthorizedRead, AuthorizedQuery}
import fi.vm.sade.hakurekisteri.rest.support.Query
import java.util.UUID
import akka.event.Logging


sealed trait AuditMessage[T] {

  def encode(event:LogEvent ):String = {
    if (event == null) {
      return null;
    }

    val baos = new ByteArrayOutputStream();
    val xmlEncoder = new XMLEncoder(baos);
    xmlEncoder.writeObject(event);
    xmlEncoder.close();

    return baos.toString();
  }

  def apply(original:T, user:String)(implicit system:String) = CamelMessage(encode(new LogEvent(tapahtuma(system, original, user))), Map[String,Any]())



  def tapahtuma(resource: String, original:T, user:String): Tapahtuma
}

import Tapahtuma._

object QueryEvent extends AuditMessage[Query[_]] {
  override def tapahtuma(resource: String,original: Query[_], user:String): Tapahtuma =  createREAD("hakurekisteri", user, resource, original.toString)
}

object ReadEvent extends AuditMessage[UUID] {
  override def tapahtuma(resource: String,original: UUID, user:String): Tapahtuma =  createREAD("hakurekisteri", user, resource, original.toString)
}

case class AuditUri(uri:String)

class AuditLog(resource:String)(implicit val audit:AuditUri) extends Actor with Producer  {

  val log = Logging(context.system, this)

  def endpointUri: String = audit.uri
  implicit val system = resource
  override protected def transformOutgoingMessage(original: Any): Any = original match {
    case AuthorizedQuery(q,orgs, user) => QueryEvent(q,user)
    case AuthorizedRead(id, orgs, user) => ReadEvent(id,user)

  }

  override protected def routeResponse(msg: Any): Unit = {
    log.debug(msg.toString)

  }
}


