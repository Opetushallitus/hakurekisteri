package fi.vm.sade.hakurekisteri.audit

import fi.vm.sade.log.model.{Tapahtuma, LogEvent}
import java.io.{ByteArrayInputStream, StringReader, ByteArrayOutputStream}
import java.beans.{XMLDecoder, XMLEncoder}
import akka.camel.{Producer, CamelMessage}
import akka.actor.Actor
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified}
import fi.vm.sade.hakurekisteri.organization.{AuthorizedDelete, AuthorizedRead, AuthorizedQuery}
import fi.vm.sade.hakurekisteri.rest.support.{Resource, Query}
import java.util.{Date, UUID}
import akka.event.Logging
import org.xml.sax.InputSource
import java.nio.charset.Charset
import scala.reflect.api.JavaUniverse
import scala.reflect.ClassTag


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

object DeleteEvent extends AuditMessage[UUID] {
  override def tapahtuma(resource: String,original: UUID, user:String): Tapahtuma =  createDELETE("hakurekisteri", user, resource, original.toString)
}

case class AuditEvent(host: String,system: String,targetType: String,target: String,timestamp: Date, etype: String, user: String, userActsForUser: String)

object AuditEvent {
  def apply(responseBody:String):AuditEvent = {
    val t = new XMLDecoder(new ByteArrayInputStream(responseBody.getBytes(Charset.defaultCharset()))  ).readObject().asInstanceOf[LogEvent].getTapahtuma
    AuditEvent(t.getHost, t.getSystem, t.getTargetType, t.getTarget, t.getTimestamp, t.getType, t.getUser, t.getUserActsForUser)

  }
}


case class AuditUri(uri:String)

class AuditLog(resource:String)(implicit val audit:AuditUri) extends Actor with Producer  {

  override def receive = {
    case msg =>
      log.debug(msg.toString)
      super.produce(msg)

  }
  val log = Logging(context.system, this)

  def endpointUri: String = audit.uri
  log.debug(s"Audit log for $resource initialized using endpoint $endpointUri")
  implicit val system = resource

  def createAuditMsg(original: Any) = original match {
    case AuthorizedQuery(q,orgs, user) => QueryEvent(q,user)
    case AuthorizedRead(id, orgs, user) => ReadEvent(id,user)
    case AuthorizedDelete(id, orgs, user) => DeleteEvent(id, user)
  }

  override protected def transformOutgoingMessage(original: Any): Any ={
    val msg = createAuditMsg(original)
    log.debug(s"sending audit event: $msg for $original")
    msg
  }

  override protected def routeResponse(msg: Any): Unit =  msg match {
    case xml:String => log.debug(AuditEvent(xml).toString)

  }
}


