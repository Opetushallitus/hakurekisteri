package fi.vm.sade.hakurekisteri.audit

import fi.vm.sade.log.model.{Tapahtuma, LogEvent}
import java.io.{StringReader, ByteArrayInputStream, ByteArrayOutputStream}
import java.beans.{XMLDecoder, XMLEncoder}
import akka.camel.{Producer, CamelMessage}
import akka.actor.Actor
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.organization.{AuthorizedDelete, AuthorizedRead, AuthorizedQuery}
import fi.vm.sade.hakurekisteri.rest.support.{Resource, Query}
import java.util.{Date, UUID}
import akka.event.Logging
import java.nio.charset.Charset
import scala.reflect.ClassTag
import org.xml.sax.InputSource
import java.net.{UnknownHostException, InetAddress}


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

object CreateEvent extends AuditMessage[Resource] {
  override def tapahtuma(resource: String,original: Resource, user:String): Tapahtuma =  createCREATE("hakurekisteri", user, resource, original.toString)
}

object UpdateEvent extends AuditMessage[Resource with Identified] {
  import scala.reflect.runtime.universe._
  def casMap[T: ClassTag: TypeTag](value: T) = {
    val m = runtimeMirror(getClass.getClassLoader)
    val im = m.reflect(value)
    typeOf[T].members.collect{ case m:MethodSymbol if m.isCaseAccessor => m}.map(im.reflectMethod(_)).map((m) => m.symbol.name.toString -> m()).toMap
  }

  override def tapahtuma(resource: String,original: Resource with Identified, user:String): Tapahtuma =  {
    val event = createUPDATE("hakurekisteri", user, resource, original.id.toString)
    for ((field, value) <- casMap(original)) event.addValue(field, value.toString)
    event
  }
}

object UnknownEvent extends AuditMessage[Any] {

  def apply(msg:Any)(implicit system:String):CamelMessage = apply(msg, "")

  override def tapahtuma(resource: String, original: Any, user: String): Tapahtuma = {
    val t: Tapahtuma = new Tapahtuma
    t.setSystem("hakurekisteri")
    t.setTarget(original.toString)
    t.setTargetType(resource)
    t.setTimestamp(new Date)
    t.setType("UNKNOWN")
    t.setUser(null)
    t.setUserActsForUser(null)
    try {
      t.setHost(InetAddress.getLocalHost.getHostName)
    }
    catch {
      case ex: UnknownHostException => {
      }
    }


    t
  }
}


case class AuditEvent(host: String,system: String,targetType: String,target: String,timestamp: Date, etype: String, user: String, userActsForUser: String)

object AuditEvent {
  def apply(responseBody:String):AuditEvent = {
    val t = new XMLDecoder(new ByteArrayInputStream(responseBody.getBytes(Charset.defaultCharset()))  ).readObject().asInstanceOf[LogEvent].getTapahtuma
    AuditEvent(t.getHost, t.getSystem, t.getTargetType, t.getTarget, t.getTimestamp, t.getType, t.getUser, t.getUserActsForUser)

  }
}


case class AuditUri(uri:String)

object AuditUri {
  def apply(broker:String, queue:String):AuditUri = new AuditUri(s"$broker:$queue")

}

class AuditLog(resource:String)(implicit val audit:AuditUri) extends Actor with Producer  {


  val log = Logging(context.system, this)

  def endpointUri: String = audit.uri
  log.debug(s"Audit log for $resource initialized using endpoint $endpointUri")
  implicit val system = resource

  def createAuditMsg(original: Any) = original match {
    case AuthorizedQuery(q,orgs, user) => QueryEvent(q,user)
    case AuthorizedRead(id, orgs, user) => ReadEvent(id,user)
    case AuthorizedDelete(id, orgs, user) => DeleteEvent(id, user)
    case a => UnknownEvent(a)
  }

  override protected def transformOutgoingMessage(original: Any): Any ={
    val msg = createAuditMsg(original)
    log.debug(s"sending audit event: $msg for $original")
    msg
  }

  override protected def transformResponse(msg: Any): Any =  msg match {
    case CamelMessage(body:String, headers) => val t = AuditEvent(body).toString
    case a => a.getClass.getName
  }


  override protected def routeResponse(msg: Any): Unit =  log.debug(msg.toString)
}


