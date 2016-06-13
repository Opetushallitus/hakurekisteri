package fi.vm.sade.hakurekisteri.integration.virta

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.ActorSystem
import akka.event.Logging
import com.ning.http.client._
import dispatch.Http
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.xml.{Elem, Node, NodeSeq, XML}


case class VirtaValidationError(m: String) extends Exception(m)

object VirtaClient {
  val version105 = "1.05"
  val version106 = "1.06"
}

class VirtaClient(config: VirtaConfig = VirtaConfig(serviceUrl = "http://virtawstesti.csc.fi/luku/OpiskelijanTiedot",
                                                    jarjestelma = "",
                                                    tunnus = "",
                                                    avain = "salaisuus", Map.empty),
                  aClient: Option[AsyncHttpClient] = None,
                  var apiVersion: String = VirtaClient.version105)(implicit val ec: ExecutionContext, system: ActorSystem) {

  private val defaultClient = Http.configure(_
    .setConnectionTimeoutInMs(config.httpClientConnectionTimeout)
    .setRequestTimeoutInMs(120000)
    .setIdleConnectionTimeoutInMs(120000)
    .setFollowRedirects(true)
    .setMaxRequestRetry(2)
  )

  val client: Http = aClient.map(Http(_)).getOrElse(defaultClient)

  val logger = Logging.getLogger(system, this)
  val maxRetries = config.httpClientMaxRetries

  val tallennettavatOpiskeluoikeustyypit = Seq("1", "2", "3", "4", "6", "7")

  def getSoapOperationEnvelope(oppijanumero: String, hetu: Option[String] = None): Elem =
    <OpiskelijanKaikkiTiedotRequest xmlns="http://tietovaranto.csc.fi/luku">
      <Kutsuja>
        <jarjestelma>{config.jarjestelma}</jarjestelma>
        <tunnus>{config.tunnus}</tunnus>
        <avain>{config.avain}</avain>
      </Kutsuja>
      <Hakuehdot>
        {
          if (hetu.isDefined) <henkilotunnus>{hetu.get}</henkilotunnus>
          else <kansallinenOppijanumero>{oppijanumero}</kansallinenOppijanumero>
        }
      </Hakuehdot>
    </OpiskelijanKaikkiTiedotRequest>

  logger.info(s"created Virta client for API version $apiVersion and serviceUrl ${config.serviceUrl}")

  def setApiVersion(version: String): Unit = {
    logger.info(s"set API version to $version, was previously $apiVersion")
    apiVersion = version
  }

  def getOpiskelijanTiedot(oppijanumero: String, hetu: Option[String] = None): Future[Option[VirtaResult]] = {
    val retryCount = new AtomicInteger(1)
    tryPost(config.serviceUrl, wrapSoapEnvelope(getSoapOperationEnvelope(oppijanumero, hetu)), oppijanumero, retryCount)
  }

  def parseFault(response: String): Unit = {
    Try(XML.loadString(response)) match {
      case Success(xml) =>
        val fault: NodeSeq = xml \ "Body" \ "Fault"
        if (fault.nonEmpty) {
          val faultstring = fault \ "faultstring"
          val faultdetail = fault \ "detail" \ "ValidationError"
          if (faultstring.text.toLowerCase == "validation error") {
            logger.warning(s"validation error: ${faultdetail.text}")
            throw VirtaValidationError(s"validation error: ${faultdetail.text}")
          }
        }
      case _ =>
    }
  }

  def tryPost(requestUrl: String,
              requestEnvelope: String,
              oppijanumero: String,
              retryCount: AtomicInteger): Future[Option[VirtaResult]] = {
    val t0 = Platform.currentTime

    import dispatch._

    object VirtaHandler extends AsyncCompletionHandler[Option[VirtaResult]]{
      override def onCompleted(response: Response): Option[VirtaResult] = {

        if (response.getStatusCode == 200) {
          val responseEnvelope: Elem = XML.loadString(response.getResponseBody)

          val opiskeluoikeudet = getOpiskeluoikeudet(responseEnvelope)
          val tutkinnot = getTutkinnot(responseEnvelope)
          val suoritukset = getOpintosuoritukset(responseEnvelope)

          (opiskeluoikeudet, tutkinnot, suoritukset) match {
            case (Seq(), Seq(), Seq()) => None
            case _ => Some(VirtaResult(
              oppijanumero = oppijanumero,
              opiskeluoikeudet = opiskeluoikeudet,
              tutkinnot = tutkinnot,
              suoritukset = suoritukset))
          }
        } else {
          val bodyString = response.getResponseBody

          parseFault(bodyString)

          logger.error(s"got non-ok response from virta: ${response.getStatusCode}, response: $bodyString")
          throw VirtaConnectionErrorException(s"got non-ok response from virta: ${response.getStatusCode}, response: $bodyString")
        }
      }
    }

    def result(t: Try[_]): String = t match {
      case Success(_) => "success"
      case Failure(e) => s"failure: $e"
    }

    val res = client((url(requestUrl) << requestEnvelope).setContentType("text/xml", "UTF-8") > VirtaHandler)
    res.onComplete(t => logger.info(s"virta query for $oppijanumero took ${Platform.currentTime - t0} ms, result ${result(t)}"))
    res
  }

  def parseLocalDate(s: String): LocalDate =
    if (s.length() > 10) {
      DateTimeFormat.forPattern("yyyy-MM-ddZ").parseLocalDate(s)
    } else {
      DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate(s)
    }

  def parseLocalDateOption(s: Option[String]): Option[LocalDate] = {
    import fi.vm.sade.hakurekisteri.tools.RicherString._
    s.flatMap(_.blankOption).map(d => parseLocalDate(d))
  }

  private def myontaja(oo: Node): NodeSeq = apiVersion match {
    case VirtaClient.version105 => oo \ "Myontaja" \ "Koodi"
    case VirtaClient.version106 => oo \ "Myontaja"
    case default => throw new NotImplementedError(s"version $default not implemented")
  }

  private def getOpintosuorituksetNodeSeq(nodeSeq: NodeSeq): NodeSeq = {
    nodeSeq \ "Body" \ "OpiskelijanKaikkiTiedotResponse" \ "Virta" \ "Opiskelija" \ "Opintosuoritukset" \ "Opintosuoritus"
  }

  def getOpiskeluoikeudet(response: NodeSeq): Seq[VirtaOpiskeluoikeus] = {
    val opiskeluoikeudet: NodeSeq = response \ "Body" \ "OpiskelijanKaikkiTiedotResponse" \ "Virta" \ "Opiskelija" \ "Opiskeluoikeudet" \ "Opiskeluoikeus"
    opiskeluoikeudet.withFilter((oo: Node) => tallennettavatOpiskeluoikeustyypit.contains((oo \ "Tyyppi").text)).map((oo: Node) => {
      val avain = oo.map(_ \ "@avain")
      VirtaOpiskeluoikeus(
        alkuPvm = parseLocalDate((oo \ "AlkuPvm").head.text),
        loppuPvm = parseLocalDateOption((oo \ "LoppuPvm").headOption.map(_.text)),
        myontaja = extractTextOption(myontaja(oo), avain, required = true).get,
        koulutuskoodit = Try((oo \ "Jakso" \ "Koulutuskoodi").map(_.text)).get,
        kieli = resolveKieli(oo \ "Jakso" \ "Kieli")
      )
    })
  }

  def getTutkinnot(response: NodeSeq): Seq[VirtaTutkinto] = {
    val opintosuoritukset: NodeSeq = getOpintosuorituksetNodeSeq(response)
    opintosuoritukset.withFilter(filterTutkinto).map((os: Node) => {
      val avain = os.map(_ \ "@avain")
      VirtaTutkinto(
        suoritusPvm = parseLocalDate((os \ "SuoritusPvm").head.text),
        koulutuskoodi = extractTextOption(os \ "Koulutuskoodi", avain), // not available in every tutkinto
        myontaja = extractTextOption(myontaja(os), avain, required = true).get,
        kieli = resolveKieli(os \ "Kieli")
      )
    })
  }

  def getOpintosuoritukset(response: NodeSeq): Seq[VirtaOpintosuoritus] = {
    val opintosuoritukset: NodeSeq = getOpintosuorituksetNodeSeq(response)
    opintosuoritukset.map((os: Node) => {
      val avain = os.map(_ \ "@avain")
      VirtaOpintosuoritus(
        suoritusPvm = parseLocalDate((os \ "SuoritusPvm").head.text),
        nimi = extractTextOption(os \ "Nimi", avain),
        koulutuskoodi = extractTextOption(os \ "Koulutuskoodi", avain),
        arvosana = parseArvosana(os \ "Arvosana", avain),
        myontaja = extractTextOption(myontaja(os), avain, required = true).get,
        laji = extractTextOption(os \ "Laji", avain)
      )
    })
  }

  // TODO parse asteikko together with arvosana
  def parseArvosana(arvosanaNode: NodeSeq, avain: Seq[NodeSeq]): Option[String] = {
    if ((arvosanaNode \ "Muu").length > 0) {
      extractTextOption(arvosanaNode \ "Muu" \ "Koodi", avain).map(_.trim)
    } else {
      extractTextOption(arvosanaNode, avain).map(_.trim)
    }
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
