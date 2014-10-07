package fi.vm.sade.hakurekisteri.integration.audit

import fi.vm.sade.log.model.{Tapahtuma, LogEvent}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.beans.{XMLDecoder, XMLEncoder}
import akka.camel.{Producer, CamelMessage}
import akka.actor.Actor
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.organization._
import fi.vm.sade.hakurekisteri.rest.support.{Resource, Query}
import java.util.Date
import akka.event.Logging
import java.nio.charset.Charset
import scala.reflect.ClassTag
import java.net.{UnknownHostException, InetAddress}
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.organization.AuthorizedRead
import fi.vm.sade.hakurekisteri.organization.AuthorizedCreate
import fi.vm.sade.hakurekisteri.organization.AuthorizedDelete


case class AuditUri(uri:String)

object AuditUri {
  def apply(broker:String, queue:String):AuditUri = new AuditUri(s"$broker:$queue")

}


import scala.reflect.runtime.universe._

class AuditLog[A <: Resource[I], I](resource:String)(implicit val audit:AuditUri, ct: ClassTag[A], tt: TypeTag[A], cti: ClassTag[I], tti: TypeTag[I]) extends Actor with Producer  {


  sealed trait AuditMessage[T] {

    def encode(event:LogEvent ):String = {
      if (event == null) {
        return null
      }

      val baos = new ByteArrayOutputStream
      val xmlEncoder = new XMLEncoder(baos)
      xmlEncoder.writeObject(event)
      xmlEncoder.close()

      baos.toString
    }

    def apply(original:T, user:String)(implicit system:String) = CamelMessage(encode(new LogEvent(tapahtuma(system, original, user))), Map[String,Any]())



    def tapahtuma(resource: String, original:T, user:String): Tapahtuma
  }

  import Tapahtuma._

  object QueryEvent extends AuditMessage[Query[_]] {
    override def tapahtuma(resource: String,original: Query[_], user:String): Tapahtuma =  createREAD("hakurekisteri", user, resource, original.toString)
  }

  object ReadEvent extends AuditMessage[I] {
    override def tapahtuma(resource: String,original: I, user:String): Tapahtuma =  createREAD("hakurekisteri", user, resource, original.toString)
  }

  object DeleteEvent extends AuditMessage[I] {
    override def tapahtuma(resource: String,original: I, user:String): Tapahtuma =  createDELETE("hakurekisteri", user, resource, original.toString)
  }

  object CreateEvent extends AuditMessage[A] {
    override def tapahtuma(resource: String,original: A, user:String): Tapahtuma =  createCREATE("hakurekisteri", user, resource, original.toString)
  }

  object UpdateEvent extends AuditMessage[A with Identified[I]] {
    def casMap[T: ClassTag: TypeTag](value: T) = {
      val m = runtimeMirror(getClass.getClassLoader)
      val im = m.reflect(value)
      typeOf[T].members.collect{ case m:MethodSymbol if m.isCaseAccessor => m}.map(im.reflectMethod).map((m) => m.symbol.name.toString -> m()).toMap
    }

    override def tapahtuma(resource: String,original: A with Identified[I], user:String): Tapahtuma =  {
      val event = createUPDATE("hakurekisteri", user, resource, original.id.toString)
      log.debug(s"creating tapahtuma for: $original")
      try {
        val caseMap = casMap(original)

        for ((field, value) <- caseMap) event.addValue(field, value.toString)
      } catch { case t:Throwable => log.error (s"error adding value for update event for $original", t)}
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
        case ex: UnknownHostException =>
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

  val log = Logging(context.system, this)

  def endpointUri: String = audit.uri
  log.debug(s"Audit log for $resource initialized using endpoint $endpointUri")
  implicit val system = resource

  def createAuditMsg(original: Any) = original match {
    case AuthorizedQuery(q,user) => QueryEvent(q,user.username)
    case AuthorizedRead(id: I, user) => ReadEvent(id,user.username)
    case AuthorizedDelete(id: I, user) => DeleteEvent(id, user.username)
    case AuthorizedCreate(res : A, user) => CreateEvent(res, user.username)
    case AuthorizedUpdate(res: A with Identified[I], user) => UpdateEvent(res, user.username)

    case a => UnknownEvent(a)
  }

  override protected def transformOutgoingMessage(original: Any): Any ={
    val msg = createAuditMsg(original)
    log.debug(s"sending audit event: $msg for $original")
    msg
  }

  override protected def transformResponse(msg: Any): Any =  msg match {
    case CamelMessage(body:String, headers) => AuditEvent(body).toString
    case a => a.getClass.getName
  }


  override protected def routeResponse(msg: Any): Unit =  log.debug(msg.toString)
}


