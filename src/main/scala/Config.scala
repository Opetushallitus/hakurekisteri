import fi.vm.sade.hakurekisteri.integration.ServiceConfig
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusConfig
import fi.vm.sade.hakurekisteri.integration.virta.VirtaConfig
import fi.vm.sade.hakurekisteri.integration.ytl.YTLConfig
import java.io.InputStream
import java.nio.file.{Files, Paths}

import org.joda.time.LocalTime
import org.slf4j.LoggerFactory


object Config {
  val log = LoggerFactory.getLogger(getClass)
  val homeDir = sys.props.get("user.home").getOrElse("")
  val ophConfDir = Paths.get(homeDir, "/oph-configuration/")

  val propertyLocations = Seq("override.properties", "suoritusrekisteri.properties", "common.properties")

  val jndiName = "java:comp/env/jdbc/suoritusrekisteri"

  // by default the service urls point to QA
  val hostQa = "testi.virkailija.opintopolku.fi"
  val organisaatioServiceUrlQa = s"https://$hostQa/organisaatio-service"
  val hakuappServiceUrlQa = s"https://$hostQa/haku-app"
  val koodistoServiceUrlQa = s"https://$hostQa/koodisto-service"
  val parameterServiceUrlQa = s"https://$hostQa/ohjausparametrit-service"

  val sijoitteluServiceUrlQa = s"https://$hostQa/sijoittelu-service"
  val tarjontaServiceUrlQa = s"https://$hostQa/tarjonta-service"
  val henkiloServiceUrlQa = s"https://$hostQa/authentication-service"
  val virtaServiceUrlTest = "http://virtawstesti.csc.fi/luku/OpiskelijanTiedot"
  val virtaJarjestelmaTest = ""
  val virtaTunnusTest = ""
  val virtaAvainTest = "salaisuus"

  val resources = for {
    file <- propertyLocations.reverse
  } yield ophConfDir.resolve(file)

  log.info(s"lazy loading properties from paths $resources")
  lazy val properties: Map[String, String] = loadProperties(resources.map(Files.newInputStream(_)))

  // props
  val serviceUser = Some(properties("tiedonsiirto.app.username.to.suoritusrekisteri"))
  val servicePassword = Some(properties("tiedonsiirto.app.password.to.suoritusrekisteri"))
  val serviceAccessUrl = Some("https://" + properties.getOrElse("host.virkailija", hostQa) + "/service-access")
  val sijoitteluServiceUrl = properties.getOrElse("cas.service.sijoittelu-service", sijoitteluServiceUrlQa)
  val tarjontaServiceUrl = properties.getOrElse("cas.service.tarjonta-service", tarjontaServiceUrlQa)
  val henkiloServiceUrl = properties.getOrElse("cas.service.authentication-service", henkiloServiceUrlQa)
  val hakuappServiceUrl = properties.getOrElse("cas.service.haku-service", hakuappServiceUrlQa)
  val koodistoServiceUrl = properties.getOrElse("cas.service.koodisto-service", koodistoServiceUrlQa)
  val parameterServiceUrl = properties.getOrElse("cas.service.ohjausparametrit-service", parameterServiceUrlQa)
  val organisaatioServiceUrl = properties.getOrElse("cas.service.organisaatio-service", organisaatioServiceUrlQa)
  val organisaatioSoapServiceUrl = properties.getOrElse("cas.service.organisaatio-service", organisaatioServiceUrlQa) + "/services/organisaatioService"
  val maxApplications = properties.getOrElse("suoritusrekisteri.hakijat.max.applications", "2000").toInt
  val virtaServiceUrl = properties.getOrElse("suoritusrekisteri.virta.service.url", virtaServiceUrlTest)
  val virtaJarjestelma = properties.getOrElse("suoritusrekisteri.virta.jarjestelma", virtaJarjestelmaTest)
  val virtaTunnus = properties.getOrElse("suoritusrekisteri.virta.tunnus", virtaTunnusTest)
  val virtaAvain = properties.getOrElse("suoritusrekisteri.virta.avain", virtaAvainTest)

  val virtaConfig = VirtaConfig(serviceUrl = virtaServiceUrl, jarjestelma = virtaJarjestelma, tunnus = virtaTunnus, avain = virtaAvain)
  val henkiloConfig = ServiceConfig(serviceAccessUrl = serviceAccessUrl, serviceUrl = henkiloServiceUrl, user = serviceUser, password = servicePassword)
  val sijoitteluConfig = ServiceConfig(serviceAccessUrl, sijoitteluServiceUrl, serviceUser, servicePassword)
  val parameterConfig = ServiceConfig(serviceUrl = parameterServiceUrl)
  val hakemusConfig = HakemusConfig(ServiceConfig(serviceAccessUrl, hakuappServiceUrl, serviceUser, servicePassword), maxApplications)
  val tarjontaConfig = ServiceConfig(serviceUrl = tarjontaServiceUrl)
  val koodistoConfig = ServiceConfig(serviceUrl = koodistoServiceUrl)
  val organisaatioConfig = ServiceConfig(serviceUrl = organisaatioServiceUrl)

  val ytlConfig = for (
    host <- properties.get("suoritusrekisteri.ytl.host");
    user <- properties.get("suoritusrekisteri.ytl.user");
    password <- properties.get("suoritusrekisteri.ytl.password");
    inbox <- properties.get("suoritusrekisteri.ytl.inbox");
    outbox <- properties.get("suoritusrekisteri.ytl.outbox");
    poll <- properties.get("suoritusrekisteri.ytl.poll");
    localStore <- properties.get("suoritusrekisteri.ytl.localstore")
  ) yield YTLConfig(host, user, password, inbox, outbox, poll.split(";").map(LocalTime.parse), localStore)

  //val amqUrl = OPHSecurity.config.properties.get("activemq.brokerurl").getOrElse("failover:tcp://luokka.hard.ware.fi:61616")
  // val amqQueue = properties.getOrElse("activemq.queue.name.log", "Sade.Log")

  def loadProperties(resources: Seq[InputStream]): Map[String, String] = {
    import scala.collection.JavaConversions._
    val rawMap = resources.map((reader) => {val prop = new java.util.Properties; prop.load(reader); Map(prop.toList: _*)}).
      reduce(_ ++ _)

    resolve(rawMap)
  }

  def resolve(source: Map[String, String]): Map[String, String] = {
    val converted = source.mapValues(_.replace("${","€{"))
    val unResolved = Set(converted.map((s) => (for (found <- "€\\{(.*?)\\}".r findAllMatchIn s._2) yield found.group(1)).toList).reduce(_ ++ _):_*)
    val unResolvable = unResolved.filter((s) => converted.get(s).isEmpty)
    if ((unResolved -- unResolvable).isEmpty)
      converted.mapValues(_.replace("€{","${"))
    else
      resolve(converted.mapValues((s) => "€\\{(.*?)\\}".r replaceAllIn (s, m => {converted.getOrElse(m.group(1), "€{" + m.group(1) + "}") })))
  }
}
