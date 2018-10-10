package fi.vm.sade.hakurekisteri

import java.net.InetAddress

import fi.vm.sade.auditlog.{Audit, Logger, Operation, User}
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

case object HenkilonTiedotVirrasta extends Operation {
  def name: String = "HENKILON_TIEDOT_VIRRASTA"
}

case object ResourceCreate extends Operation {
  def name: String = "RESOURCE_CREATE"
}

case object ResourceUpdate extends Operation {
  def name: String = "RESOURCE_UPDATE"
}

case object ResourceDelete extends Operation {
  def name: String = "RESOURCE_DELETE"
}

class AuditUtil {

  private val LOG = LoggerFactory.getLogger(classOf[AuditUtil])

  //Jos requestia ei jostain syystä ole saatavilla, käytetään tätä.
  def getUserWithoutRequest(oidToUse: String): User = {
    val userOid = oidToUse
    val userAgent = "-"
    val session = "-"
    val ip = InetAddress.getLocalHost
    getUser(userOid, ip, session, userAgent)
  }

  def getUser(request: HttpServletRequest, userName: String = null): User = {
    LOG.info("Creating user, username: " + userName)
    if (request == null) {
      LOG.info("No request available, creating user without request information")
      getUserWithoutRequest(userName)
    } else {
      val userAgent = Option(request.getHeader("User-Agent")).getOrElse("Unknown user agent")
      val session = getSession(request)
      val ip = getInetAddress(request)
      LOG.info(s"Request available as planned, creating user with agent: $userAgent, session: $session, ip: $ip")
      getUser(userName, ip, session, userAgent)
    }
  }

  private def getUser(userOid: String, ip: InetAddress, session: String, userAgent: String) = try
    new User(new Oid(userOid), ip, session, userAgent)
  catch {
    case e: GSSException =>
      LOG.warn(s"Warning: userOid ($userOid) does not match Oid requirements, creating user without Oid")
      new User(ip, session, userAgent)
  }

  private def getSession(request: HttpServletRequest) = try
    Option(request.getSession.getId).getOrElse("no session")
  catch {
    case e: Exception =>
      LOG.error(s"Couldn't log session for request ${request}")
      throw new RuntimeException(e)
  }

  private def getInetAddress(implicit request: HttpServletRequest) = try
    InetAddress.getByName(HttpServletRequestUtils.getRemoteAddress(request))
  catch {
    case e: Exception =>
      LOG.error(s"Couldn't log InetAddress for log entry ${e}")
      throw new RuntimeException(e)
  }
}

class LoggerForAudit extends Logger {
  private val LOGGER = LoggerFactory.getLogger(classOf[Audit])
  def log(msg: String): Unit = {
    LOGGER.info(msg)
  }
}

