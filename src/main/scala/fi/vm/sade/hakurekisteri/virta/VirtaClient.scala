package fi.vm.sade.hakurekisteri.virta

import java.net.URL

import com.stackmob.newman.dsl._
import com.stackmob.newman.HttpClient
import com.stackmob.newman.response.HttpResponse
import fi.vm.sade.generic.common.HetuUtils
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{XML, Elem}

case class VirtaConfig(serviceUrl: String = "http://virtawstesti.csc.fi/luku/OpiskelijanTiedot",
                       jarjestelma: String = "",
                       tunnus: String = "",
                       avain: String = "salaisuus")

case class OpiskelijanTiedot(hetu: Option[String],
                             oppijanumero: Option[String],
                             suoritukset: Set[Suoritus])

class VirtaClient(config: VirtaConfig)(implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) {
  val logger = LoggerFactory.getLogger(getClass)

  def getOpiskelijanKaikkiTiedot(oppijanumero: Option[String] = None, hetu: Option[String] = None): Future[OpiskelijanTiedot] = {
    if ((oppijanumero.isEmpty && hetu.isEmpty) || (oppijanumero.isDefined && hetu.isDefined)) throw new IllegalArgumentException("either oppijanumero or hetu is required")
    if (hetu.isDefined && !HetuUtils.isHetuValid(hetu.get)) throw new IllegalArgumentException("hetu is not valid")

    val operation =
<OpiskelijanKaikkiTiedotRequest xmlns="http://tietovaranto.csc.fi/luku">
  <Kutsuja>
    <jarjestelma>{config.jarjestelma}</jarjestelma>
    <tunnus>{config.tunnus}</tunnus>
    <avain>{config.avain}</avain>
  </Kutsuja>
  <Hakuehdot>
    {if (oppijanumero.isDefined) <kansallinenOppijanumero>{oppijanumero.get}</kansallinenOppijanumero>}
    {if (hetu.isDefined) <henkilotunnus>{hetu.get}</henkilotunnus>}
  </Hakuehdot>
</OpiskelijanKaikkiTiedotRequest>

    val envelope = wrapSoapEnvelope(operation)
    logger.debug(s"POST url: ${config.serviceUrl}, body: $envelope")

    POST(new URL(config.serviceUrl)).
      setBodyString(envelope).
      apply.
      map((response) => {
        val envelope: Elem = XML.loadString(response.bodyString)

        val h = envelope \ "Body" \ "OpiskelijanKaikkiTiedotResponse" \ "Virta" \ "Opiskelija" \ "Henkilotunnus"
        logger.debug(s"h: $h")

        // TODO parse information from XML
        OpiskelijanTiedot(Some("111111-1975"), Some("1.2.3"), Set())
      })
  }

  def wrapSoapEnvelope(operation: Elem): String = {
    val buf = new StringBuilder
    buf.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
    buf.append("<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">\n")
    buf.append("<SOAP-ENV:Body>\n")
    buf.append(operation.toString)
    buf.append("\n</SOAP-ENV:Body>\n")
    buf.append("</SOAP-ENV:Envelope>\n")
    buf.toString
  }
}
