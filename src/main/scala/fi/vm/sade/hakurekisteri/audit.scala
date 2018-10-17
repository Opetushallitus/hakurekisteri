package fi.vm.sade.hakurekisteri

import java.net.InetAddress

import fi.vm.sade.auditlog.{ApplicationType, Audit, Logger, Operation, User}
import fi.vm.sade.javautils.http.HttpServletRequestUtils
import javax.servlet.http.HttpServletRequest
import org.ietf.jgss.{GSSException, Oid}
import org.slf4j.LoggerFactory

case object OppijanTietojenPaivitysKoskesta extends Operation {
  def name: String = "OPPIJAN_TIETOJEN_PAIVITYS_KOSKESTA"
}

case object OppijoidenTietojenPaivitysKoskesta extends Operation {
  def name: String = "OPPIJOIDEN_TIETOJEN_PAIVITYS_KOSKESTA"
}

case object HaunHakijoidenTietojenPaivitysKoskesta extends Operation {
  def name: String = "HAUN_HAKIJOIDEN_TIETOJEN_PAIVITYS_KOSKESTA"
}

case object YTLSync extends Operation {
  def name: String = "YTL_SYNC"
}

case object YTLSyncForAll extends Operation {
  def name: String = "REQUEST_YTL_SYNC_FOR_ALL"
}

case object YTLSyncForPerson extends Operation {
  def name: String = "REQUEST_YTL_SYNC_FOR_PERSON"
}

case object HenkilonTiedotVirrasta extends Operation {
  def name: String = "HENKILON_TIEDOT_VIRRASTA"
}

case object ResourceCreate extends Operation {
  def name: String = "RESOURCE_CREATE"
}

case object ResourceRead extends Operation {
  def name: String = "RESOURCE_READ"
}

case object ResourceUpdate extends Operation {
  def name: String = "RESOURCE_UPDATE"
}

case object ResourceDelete extends Operation {
  def name: String = "RESOURCE_DELETE"
}

object SuoritusAudit {
  private val auditLogger: Logger = LoggerForAudit
  val audit = new Audit(auditLogger, "hakurekisteri", ApplicationType.VIRKAILIJA)
}

object SuoritusAuditBackend {
  private val auditLogger: Logger = LoggerForAudit
  val audit = new Audit(auditLogger, "hakurekisteri", ApplicationType.BACKEND)
}

object LoggerForAudit extends Logger {
  private val LOGGER = LoggerFactory.getLogger(classOf[UserParser])
  def log(msg: String): Unit = {
    LOGGER.info(msg)
  }
}

class UserParser {

}

object UserParser {
  private val LOG_FOR_DEBUG = LoggerFactory.getLogger(classOf[UserParser])

  def getUserWithoutRequest(userOid: String): User = {
    //LOG_FOR_DEBUG.info("Creating user without request, userOid: " + userOid)
    val userAgent = "-"
    val session = "-"
    val ip = InetAddress.getLocalHost
    createUser(userOid, ip, session, userAgent)
  }

  def parseUser(request: HttpServletRequest, userOid: String = null): User = {
    //LOG_FOR_DEBUG.info("Creating user, userOid: " + userOid)
    if (request == null) {
      //LOG_FOR_DEBUG.info("No request available, creating user without request information")
      getUserWithoutRequest(userOid)
    } else {
      val userAgent = Option(request.getHeader("User-Agent")).getOrElse("Unknown user agent")
      val session = getSession(request)
      val ip = getInetAddress(request)
      //LOG_FOR_DEBUG.info(s"Request available as planned, creating user with agent: $userAgent, session: $session, ip: $ip")
      createUser(userOid, ip, session, userAgent)
    }
  }

  private def createUser(userOid: String, ip: InetAddress, session: String, userAgent: String) = try
    new User(new Oid(userOid), ip, session, userAgent)
  catch {
    case e: GSSException =>
      //LOG_FOR_DEBUG.warn(s"Warning: userOid ($userOid) does not match Oid requirements, creating audit user without Oid")
      new User(ip, session, userAgent)
  }

  private def getSession(request: HttpServletRequest) = try {
    val ses = request.getSession(false)
    if (ses != null)
      ses.getId
    else
      "no session"
  } catch {
    case e: Exception =>
      LOG_FOR_DEBUG.error(s"Couldn't log session for request $request")
      throw new RuntimeException(e)
  }

  private def getInetAddress(implicit request: HttpServletRequest) = try
    InetAddress.getByName(HttpServletRequestUtils.getRemoteAddress(request))
  catch {
    case e: Exception =>
      LOG_FOR_DEBUG.error(s"Couldn't log InetAddress for log entry $e")
      throw new RuntimeException(e)
  }

}
