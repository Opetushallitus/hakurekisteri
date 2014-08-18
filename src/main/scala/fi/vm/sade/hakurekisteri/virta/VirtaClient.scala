package fi.vm.sade.hakurekisteri.virta

import java.net.URL

import com.stackmob.newman.dsl._
import com.stackmob.newman.HttpClient
import fi.vm.sade.generic.common.HetuUtils
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, Suoritus}
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Failure, Try}
import scala.xml.{Node, NodeSeq, XML, Elem}

case class VirtaConfig(serviceUrl: String = "http://virtawstesti.csc.fi/luku/OpiskelijanTiedot",
                       jarjestelma: String = "",
                       tunnus: String = "",
                       avain: String = "salaisuus")

case class OpiskelijanTiedot(hetu: Option[String],
                             oppijanumero: Option[String],
                             suoritukset: Set[Suoritus])

class VirtaClient(config: VirtaConfig)(implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) {
  val logger = LoggerFactory.getLogger(getClass)

  def getOpiskelijanTiedot(oppijanumero: Option[String] = None, hetu: Option[String] = None): Future[Option[OpiskelijanTiedot]] = {
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
      Try({
        val responseEnvelope: Elem = XML.loadString(response.bodyString)

        val opiskeluoikeudet: NodeSeq = responseEnvelope \ "Body" \ "OpiskelijanKaikkiTiedotResponse" \ "Virta" \ "Opiskelija" \ "Opiskeluoikeudet" \ "Opiskeluoikeus"
        val opintosuoritukset: NodeSeq = responseEnvelope \ "Body" \ "OpiskelijanKaikkiTiedotResponse" \ "Virta" \ "Opiskelija" \ "Opintosuoritukset" \ "Opintosuoritus"

        def extractText(n: NodeSeq, avain: Seq[NodeSeq], required: Boolean = false): String = {
          Try(n.head.text).orElse{if (required) Failure(new IllegalArgumentException(s"element $n is missing from avain $avain")) else Success("")}.get
        }

        // FIXME cleanup code
        val keskeneraisetTutkinnot: Seq[Suoritus] = opiskeluoikeudet.map((oo: Node) => {
          logger.debug(s"opiskeluoikeus: $oo")

          val avain = oo.map(_ \ "@avain")
          val oppilaitoskoodi = extractText(oo \ "Myontaja" \ "Koodi", avain, required = true)
          val koulutuskoodit = Try((oo \ "Jakso" \ "Koulutuskoodi").map(_.text)).toOption
          val opintoala1995 = extractText(oo \ "Opintoala1995", avain) // Universities use this
          val koulutusala2002 = extractText(oo \ "Koulutusala2002", avain) // AMK
          if (opintoala1995 == "" && koulutusala2002 == "") throw new IllegalArgumentException(s"opintoala1995 and koulutusala2002 are both missing from avain $avain")
          // should suoritus be splitted into multiple suoritus according to koulutuskoodi?
          val valmistuminen = Try(DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate((oo \ "LoppuPvm").head.text)).toOption
          val tila = valmistuminen match {
            case Some(l) if l.isBefore(new LocalDate()) => "KESKEN"
            case None => "KESKEN"
            case _ => "VALMIS"
          }
          val kieli = Some("FI") // FIXME resolve kieli from Jakso if exists? what is the default value?

          Suoritus(komo = koulutuskoodit.get.head, // FIXME fetch komo oid from tarjonta
            myontaja = oppilaitoskoodi, // FIXME fetch myontaja oid from organisaatio
            tila = tila,
            valmistuminen = valmistuminen.getOrElse(new LocalDate(new LocalDate().getYear() + 1900, 12, 31)), // FIXME what is the default value?
            henkiloOid = oppijanumero.get,
            yksilollistaminen = yksilollistaminen.Ei,
            suoritusKieli = kieli.get)
        })

        // FIXME cleanup code
        val suoritetutTutkinnot: Seq[Suoritus] = opintosuoritukset.withFilter(filterTutkinto).map((os: Node) => {
          logger.debug(s"opintosuoritus: $os")

          val avain = os.map(_ \ "@avain")
          val oppilaitoskoodi = extractText(os \ "Myontaja" \ "Koodi", avain, required = true)
          val koulutuskoodi = extractText(os \ "Koulutuskoodi", avain, required = true)
          val opintoala1995 = extractText(os \ "Opintoala1995", avain) // Universities use this
          val koulutusala2002 = extractText(os \ "Koulutusala2002", avain) // AMK
          if (opintoala1995 == "" && koulutusala2002 == "") throw new IllegalArgumentException(s"neither opintoala1995 or koulutusala2002 are present in avain $avain")
          val valmistuminen = Try(DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate((os \ "SuoritusPvm").head.text)).toOption
          if (valmistuminen.isEmpty) throw new IllegalArgumentException(s"suoritusPvm is missing from opintosuoritus $avain")
          val tila = valmistuminen match {
            case Some(l) if l.isBefore(new LocalDate()) => "KESKEN"
            case _ => "VALMIS"
          }
          val kieli = Try((os \ "Kieli").head.text.toUpperCase).toOption.map {
            case "20" => "99" // FIXME 20 = "vieraskielinen" - switch to 99 ("unknown")?
            case k => k
          }

          Suoritus(komo = koulutuskoodi, // FIXME fetch komo oid from tarjonta
                   myontaja = oppilaitoskoodi, // FIXME fetch myontaja oid from organisaatio
                   tila = tila,
                   valmistuminen = valmistuminen.get,
                   henkiloOid = oppijanumero.get,
                   yksilollistaminen = yksilollistaminen.Ei,
                   suoritusKieli = kieli.get)
        })

        OpiskelijanTiedot(hetu = Some("111111-1975"), oppijanumero = Some("1.2.3"), suoritukset = (keskeneraisetTutkinnot ++ suoritetutTutkinnot).toSet)
      }).toOption
    })
  }

  def filterTutkinto(n: Node): Boolean = {
    (n \ "Laji").head.text == "1"
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
