package fi.vm.sade.hakurekisteri.hakija

import scala.concurrent.{ExecutionContext, Future}
import org.slf4j.LoggerFactory
import com.stackmob.newman.ApacheHttpClient
import org.json4s.{DefaultFormats, Formats}
import fi.vm.sade.hakurekisteri.rest.support.User
import java.net.URL
import com.stackmob.newman.dsl._
import fi.vm.sade.hakurekisteri.hakija.HakemusSijoittelu
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.hakija.HakutoiveSijoittelu
import com.stackmob.newman.response.HttpResponseCode

case class HakutoiveSijoittelu(hakutoive: Option[Short], hakukohdeOid: Option[String], tarjoajaOid: Option[String], hakutoiveenValintatapajonot: Option[Seq[Map[String, String]]])

case class HakemusSijoittelu(hakemusOid: Option[String], hakutoiveet: Option[Seq[HakutoiveSijoittelu]])

trait Valintapalvelu {

  def getSijoitteluTila(hakuOid: String, hakemusOid: String, user: Option[User]): Future[Option[HakemusSijoittelu]]

}

class RestValintapalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/sijoittelu-service")(implicit val ec: ExecutionContext) extends Valintapalvelu {
  val logger = LoggerFactory.getLogger(getClass)
  implicit val httpClient = new ApacheHttpClient()()
  protected implicit def jsonFormats: Formats = DefaultFormats

  def getProxyTicket(user: Option[User]): String = {
    user.flatMap(_.attributePrincipal.map(_.getProxyTicketFor(serviceUrl + "/j_spring_cas_security_check"))).getOrElse("")
  }

  override def getSijoitteluTila(hakuOid: String, hakemusOid: String, user: Option[User]): Future[Option[HakemusSijoittelu]] = {
    val url = new URL(serviceUrl + "/resources/sijoittelu/" + hakuOid + "/sijoitteluajo/latest/hakemus/" + hakemusOid)
    val ticket = getProxyTicket(user)
    logger.debug("calling sijoittelu-service [url={}, ticket={}]", Array(url, ticket))
    GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {
        val hakemus = response.bodyAsCaseClass[HakemusSijoittelu].toOption
        logger.debug("got response: [{}]", hakemus)
        hakemus
      } else {
        logger.error("call to sijoittelu-service [url={}, ticket={}] failed: {}", url, ticket, response.code)
        throw new RuntimeException("virhe kutsuttaessa sijoittelupalvelua: %s".format(response.code))
      }
    })
  }

}

