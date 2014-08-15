package fi.vm.sade.hakurekisteri.virta

import java.net.URL

import com.stackmob.newman.dsl._
import com.stackmob.newman.HttpClient
import fi.vm.sade.generic.common.HetuUtils
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, Suoritus}
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
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

    val requestEnvelope = wrapSoapEnvelope(operation)
    logger.debug(s"POST url: ${config.serviceUrl}, body: $requestEnvelope")

    POST(new URL(config.serviceUrl)).setBodyString(requestEnvelope).apply.map((response) => {
      Try({
        val responseEnvelope: Elem = XML.loadString(response.bodyString)

        val opiskeluoikeudet: NodeSeq = responseEnvelope \ "Body" \ "OpiskelijanKaikkiTiedotResponse" \ "Virta" \ "Opiskelija" \ "Opiskeluoikeudet" \ "Opiskeluoikeus"
        val opintosuoritukset: NodeSeq = responseEnvelope \ "Body" \ "OpiskelijanKaikkiTiedotResponse" \ "Virta" \ "Opiskelija" \ "Opintosuoritukset" \ "Opintosuoritus"

        // FIXME cleanup code
        val keskeneraisetTutkinnot: Seq[Suoritus] = opiskeluoikeudet.map((oo: Node) => {
          logger.debug(s"opiskeluoikeus: $oo")

          val avain = oo.map(_ \ "@avain")
          val opiskelijaAvain = oo.map(_ \ "@opiskelijaAvain")

          val oppilaitoskoodi = Try((oo \ "Myontaja" \ "Koodi").head.text).toOption
          if (oppilaitoskoodi.isEmpty) throw new IllegalArgumentException(s"myontaja is missing from opiskeluoikeus $avain, opiskelija $opiskelijaAvain")

          val koulutuskoodit = Try((oo \ "Jakso" \ "Koulutuskoodi").map(_.text)).toOption
          // should suoritus be splitted into multiple suoritus according to koulutuskoodi?

          val valmistuminen = Try(DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate((oo \ "LoppuPvm").head.text)).toOption

          val tila = valmistuminen match {
            case Some(l) if l.isBefore(new LocalDate()) => "KESKEN"
            case None => "KESKEN"
            case _ => "VALMIS"
          }

          val kieli = Some("FI") // FIXME resolve kieli from Jakso if exists? what is the default value?

          Suoritus(komo = koulutuskoodit.get.head, // FIXME fetch komo oid from tarjonta
            myontaja = oppilaitoskoodi.get, // FIXME fetch myontaja oid from organisaatio
            tila = tila,
            valmistuminen = valmistuminen.getOrElse(new LocalDate(new LocalDate().getYear() + 1900, 12, 31)), // FIXME what is the default value?
            henkiloOid = oppijanumero.get, // FIXME what to do if called with hetu instead?
            yksilollistaminen = yksilollistaminen.Ei,
            suoritusKieli = kieli.get)
        })

        // FIXME cleanup code
        val suoritetutTutkinnot: Seq[Suoritus] = opintosuoritukset.withFilter(filterTutkinto).map((os: Node) => {
          logger.debug(s"opintosuoritus: $os")

          val avain = os.map(_ \ "@avain")
          val opiskelijaAvain = os.map(_ \ "@opiskelijaAvain")

          val oppilaitoskoodi = Try((os \ "Myontaja" \ "Koodi").head.text).toOption
          if (oppilaitoskoodi.isEmpty) throw new IllegalArgumentException(s"myontaja is missing from opintosuoritus $avain, opiskelija $opiskelijaAvain")

          val koulutuskoodi = Try((os \ "Koulutuskoodi").head.text).toOption
          val opintoala1995 = Try((os \ "Opintoala1995").head.text).toOption
          if (koulutuskoodi.isEmpty) throw new IllegalArgumentException(s"koulutuskoodi is missing from opintosuoritus $avain, opiskelija $opiskelijaAvain")

          val valmistuminen = Try(DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate((os \ "SuoritusPvm").head.text)).toOption
          if (valmistuminen.isEmpty) throw new IllegalArgumentException(s"suoritusPvm is missing from opintosuoritus $avain, opiskelija $opiskelijaAvain")

          val tila = valmistuminen match {
            case Some(l) if l.isBefore(new LocalDate()) => "KESKEN"
            case _ => "VALMIS"
          }

          val kieli = Try((os \ "Kieli").head.text.toUpperCase).toOption.map {
            case "20" => "99" // FIXME 20 = "vieraskielinen" - switch to 99 ("unknown")?
            case k => k
          }

          Suoritus(komo = koulutuskoodi.get, // FIXME fetch komo oid from tarjonta
                   myontaja = oppilaitoskoodi.get, // FIXME fetch myontaja oid from organisaatio
                   tila = tila,
                   valmistuminen = valmistuminen.get,
                   henkiloOid = oppijanumero.get, // FIXME what to do if called with hetu instead?
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
