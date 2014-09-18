package fi.vm.sade.hakurekisteri.integration.virta

import java.net.URL

import com.stackmob.newman.HttpClient
import com.stackmob.newman.dsl._
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.generic.common.HetuUtils
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, Node, NodeSeq, XML}

class VirtaClient(config: VirtaConfig = VirtaConfig(serviceUrl = "http://virtawstesti.csc.fi/luku/OpiskelijanTiedot",
                                                    jarjestelma = "",
                                                    tunnus = "",
                                                    avain = "salaisuus"))
                 (implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) {
  val logger = LoggerFactory.getLogger(getClass)

  def getOpiskelijanTiedot(oppijanumero: Option[String] = None, hetu: Option[String] = None): Future[Option[VirtaResult]] = {
    if (oppijanumero.isEmpty && hetu.isEmpty) throw new IllegalArgumentException("either oppijanumero or hetu is required")
    if (hetu.isDefined && !HetuUtils.isHetuValid(hetu.get)) throw new IllegalArgumentException("hetu is not valid")
    if (oppijanumero.isEmpty) throw new IllegalArgumentException("oppijanumero is always required")

    val operation =
<OpiskelijanKaikkiTiedotRequest xmlns="http://tietovaranto.csc.fi/luku">
  <Kutsuja>
    <jarjestelma>{config.jarjestelma}</jarjestelma>
    <tunnus>{config.tunnus}</tunnus>
    <avain>{config.avain}</avain>
  </Kutsuja>
  <Hakuehdot>
    {if (hetu.isDefined) <henkilotunnus>{hetu.get}</henkilotunnus> else <kansallinenOppijanumero>{oppijanumero.get}</kansallinenOppijanumero>}
  </Hakuehdot>
</OpiskelijanKaikkiTiedotRequest>

    val requestEnvelope = wrapSoapEnvelope(operation)
    logger.debug(s"POST url: ${config.serviceUrl}, body: $requestEnvelope")

    POST(new URL(config.serviceUrl)).setBodyString(requestEnvelope).apply.map((response) => {
      logger.debug(s"got response from url [${config.serviceUrl}], response: $response") //, body: ${response.bodyString}")
      if (response.code == HttpResponseCode.Ok) {
        val responseEnvelope: Elem = XML.loadString(response.bodyString)
        val opiskeluoikeudet = getOpiskeluoikeudet(responseEnvelope)
        val tutkinnot = getTutkinnot(responseEnvelope)
        (opiskeluoikeudet, tutkinnot) match {
          case (Seq(), Seq()) => logger.debug(s"empty result for oppijanumero: $oppijanumero, hetu $hetu"); None
          case _ => Some(VirtaResult(opiskeluoikeudet = opiskeluoikeudet, tutkinnot = tutkinnot))
        }
      } else {
        logger.error(s"got non-ok response from virta: ${response.code}, response: ${response.bodyString}")
        throw VirtaConnectionErrorException(s"got non-ok response from virta: ${response.code}, response: ${response.bodyString}")
      }
    })
  }

  def getOpiskeluoikeudet(response: NodeSeq): Seq[VirtaOpiskeluoikeus] = {
    val opiskeluoikeudet: NodeSeq = response \ "Body" \ "OpiskelijanKaikkiTiedotResponse" \ "Virta" \ "Opiskelija" \ "Opiskeluoikeudet" \ "Opiskeluoikeus"
    opiskeluoikeudet.map((oo: Node) => {
      val avain = oo.map(_ \ "@avain")

      VirtaOpiskeluoikeus(
        alkuPvm = Try(DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate((oo \ "AlkuPvm").head.text)).get,
        loppuPvm = Try(DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate((oo \ "LoppuPvm").head.text)).toOption,
        myontaja = extractTextOption(oo \ "Myontaja" \ "Koodi", avain, required = true).get,
        koulutuskoodit = Try((oo \ "Jakso" \ "Koulutuskoodi").map(_.text)).get,
        opintoala1995 = extractTextOption(oo \ "Opintoala1995", avain), // Universities use this
        koulutusala2002 = extractTextOption(oo \ "Koulutusala2002", avain), // AMK
        kieli = resolveKieli(oo \ "Jakso" \ "Kieli")
      )
    })
  }

  def getTutkinnot(response: NodeSeq): Seq[VirtaTutkinto] = {
    val opintosuoritukset: NodeSeq = response \ "Body" \ "OpiskelijanKaikkiTiedotResponse" \ "Virta" \ "Opiskelija" \ "Opintosuoritukset" \ "Opintosuoritus"
    opintosuoritukset.withFilter(filterTutkinto).map((os: Node) => {
      val avain = os.map(_ \ "@avain")

      VirtaTutkinto(
        suoritusPvm = Try(DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate((os \ "SuoritusPvm").head.text)).get,
        koulutuskoodi = extractTextOption(os \ "Koulutuskoodi", avain, required = true),
        opintoala1995 = extractTextOption(os \ "Opintoala1995", avain), // Universities use this
        koulutusala2002 = extractTextOption(os \ "Koulutusala2002", avain), // AMK
        myontaja = extractTextOption(os \ "Myontaja" \ "Koodi", avain, required = true).get,
        kieli = resolveKieli(os \ "Kieli")
      )
    })
  }

  def extractTextOption(n: NodeSeq, avain: Seq[NodeSeq], required: Boolean = false): Option[String] = {
    Try(Some(n.head.text)).orElse{ if (required) Failure(InvalidVirtaResponseException(s"element $n is missing from avain $avain")) else Success(None) }.get
  }

  def resolveKieli(n: NodeSeq): String = {
    Try(n.head.text.toUpperCase).getOrElse("FI") match {
      case "20" => "99"
      case k => k
    }
  }

  def filterTutkinto(n: Node): Boolean = {
    (n \ "Laji").head.text == "1"
  }

  def wrapSoapEnvelope(operation: Elem): String = {
    val buf = new StringBuilder
    buf.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
    buf.append("<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\">\n")
    buf.append("<SOAP-ENV:Body>\n")
    buf.append(operation.toString())
    buf.append("\n</SOAP-ENV:Body>\n")
    buf.append("</SOAP-ENV:Envelope>\n")
    buf.toString()
  }
}
