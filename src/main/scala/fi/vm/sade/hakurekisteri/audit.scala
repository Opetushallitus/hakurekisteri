package fi.vm.sade.hakurekisteri

import java.net.InetAddress

import fi.vm.sade.auditlog._
import fi.vm.sade.javautils.http.HttpServletRequestUtils
import javax.servlet.http.HttpServletRequest
import org.ietf.jgss.{GSSException, Oid}
import org.scalatra.Params
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

case object HakijatLuku extends Operation {
  def name: String = "HAKIJAT_LUKU"
}

case object KKHakijatLuku extends Operation {
  def name: String = "KK_HAKIJAT_LUKU"
}

case object AsiakirjaLuku extends Operation {
  def name: String = "ASIAKIRJA_LUKU"
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
  def name: String = "READ_VIRTA_TIEDOT"
}

case object ResourceCreate extends Operation {
  def name: String = "RESOURCE_CREATE"
}

case object ResourceRead extends Operation {
  def name: String = "RESOURCE_READ"
}

case object ResourceReadByQuery extends Operation {
  def name: String = "RESOURCE_READ_BY_QUERY"
}

case object KaikkiHaunEnsikertalaiset extends Operation {
  def name: String = "KAIKKI_HAUN_ENSIKERTALAISET_READ"
}

case object EnsikertalainenHaussaQuery extends Operation {
  def name: String = "ONKO_HENKILO_ENSIKERTALAINEN_HAUSSA_QUERY"
}

case object RekisteritiedotRead extends Operation {
  def name: String = "REKISTERITIEDOT_READ"
}

case object RekisteritiedotReadLight extends Operation {
  def name: String = "REKISTERITIEDOT_READ_LIGHT"
}

case object ResourceUpdate extends Operation {
  def name: String = "RESOURCE_UPDATE"
}

case object ResourceDelete extends Operation {
  def name: String = "RESOURCE_DELETE"
}

case object ReprocessHaunHakemukset extends Operation {
  def name: String = "REPROCESS_HAUN_HAKEMUKSET"
}

case object SiirtotiedostoQueryWithExistingAsiakirja extends Operation {
  def name: String = "SIIRTOTIEDOSTO_EXISTING_ASIAKIRJA"
}

object SuoritusAuditVirkailija {
  private val auditLogger: Logger = LoggerForAudit
  val audit = new Audit(auditLogger, "hakurekisteri", ApplicationType.VIRKAILIJA)
}

object SuoritusAuditBackend {
  private val auditLogger: Logger = LoggerForAudit
  val audit = new Audit(auditLogger, "hakurekisteri", ApplicationType.BACKEND)
}

object LoggerForAudit extends Logger {
  private val LOGGER = LoggerFactory.getLogger(classOf[Audit])
  def log(msg: String): Unit = {
    LOGGER.info(msg)
  }
}

class AuditUtil {}

object AuditUtil {
  private val logger = LoggerFactory.getLogger(classOf[Audit])

  def parseUser(request: HttpServletRequest, userOid: String): User = {
    try {
      val userAgent = Option(request.getHeader("User-Agent")).getOrElse("Unknown user agent")
      val session = request.getSession(false).getId
      val ip = InetAddress.getByName(HttpServletRequestUtils.getRemoteAddress(request))
      new User(new Oid(userOid), ip, session, userAgent)
    } catch {
      case e: Throwable =>
        logger.error("Error while parsing auditUser: " + e)
        throw AuditException(e.getMessage)
    }
  }

  def targetFromParams(params: Params): Target.Builder = {
    new Target.Builder().setField("params", params.keySet.map(k => k + ":" + params(k)).toString())
  }
}

case class AuditException(message: String) extends Exception(message)
