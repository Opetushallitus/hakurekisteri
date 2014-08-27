import java.io.InputStream
import java.nio.file.{Files, Paths}


object Config {

  val homeDir = sys.props.get("user.home").getOrElse("")
  val ophConfDir = Paths.get(homeDir, "/oph-configuration/")

  val propertyLocations = Seq("override.properties", "suoritusrekisteri.properties", "common.properties")

  val jndiName = "java:comp/env/jdbc/suoritusrekisteri"
  val OPH = "1.2.246.562.10.00000000001"

  // by default the service urls point to QA
  val hostQa = "testi.virkailija.opintopolku.fi"
  val organisaatioServiceUrlQa = s"https://$hostQa/organisaatio-service"
  val hakuappServiceUrlQa = s"https://$hostQa/haku-app"
  val koodistoServiceUrlQa = s"https://$hostQa/koodisto-service"
  val sijoitteluServiceUrlQa = s"https://$hostQa/sijoittelu-service"
  val tarjontaServiceUrlQa = s"https://$hostQa/tarjonta-service"
  val henkiloServiceUrlQa = s"https://$hostQa/authentication-service"
  val virtaServiceUrlTest = "http://virtawstesti.csc.fi/luku/OpiskelijanTiedot"
  val virtaJarjestelmaTest = ""
  val virtaTunnusTest = ""
  val virtaAvainTest = "salaisuus"

  // props
  val serviceUser = properties("tiedonsiirto.app.username.to.suoritusrekisteri")
  val servicePassword = properties("tiedonsiirto.app.password.to.suoritusrekisteri")
  val serviceAccessUrl = "https://" + properties.getOrElse("host.virkailija", hostQa) + "/service-access"
  val sijoitteluServiceUrl = properties.getOrElse("cas.service.sijoittelu-service", sijoitteluServiceUrlQa)
  val tarjontaServiceUrl = properties.getOrElse("cas.service.tarjonta-service", tarjontaServiceUrlQa)
  val henkiloServiceUrl = properties.getOrElse("cas.service.authentication-service", henkiloServiceUrlQa)
  val hakuappServiceUrl = properties.getOrElse("cas.service.haku-service", hakuappServiceUrlQa)
  val koodistoServiceUrl = properties.getOrElse("cas.service.koodisto-service", koodistoServiceUrlQa)
  val organisaatioServiceUrl = properties.getOrElse("cas.service.organisaatio-service", organisaatioServiceUrlQa)
  val organisaatioSoapServiceUrl = properties.getOrElse("cas.service.organisaatio-service", organisaatioServiceUrlQa) + "/services/organisaatioService"
  val maxApplications = properties.getOrElse("suoritusrekisteri.hakijat.max.applications", "2000").toInt
  val virtaServiceUrl = properties.getOrElse("suoritusrekisteri.virta.service.url", virtaServiceUrlTest)
  val virtaJarjestelma = properties.getOrElse("suoritusrekisteri.virta.jarjestelma", virtaJarjestelmaTest)
  val virtaTunnus = properties.getOrElse("suoritusrekisteri.virta.tunnus", virtaTunnusTest)
  val virtaAvain = properties.getOrElse("suoritusrekisteri.virta.avain", virtaAvainTest)
  //val amqUrl = OPHSecurity.config.properties.get("activemq.brokerurl").getOrElse("failover:tcp://luokka.hard.ware.fi:61616")
  //implicit val audit = AuditUri(broker, OPHSecurity.config.properties.getOrElse("activemq.queue.name.log", "Sade.Log"))


  val resources = for {
    file <- propertyLocations.reverse
  } yield ophConfDir.resolve(file)

  def loadProperties(resources:Seq[InputStream]):Map[String, String] = {
    import scala.collection.JavaConversions._
    val rawMap = resources.map((reader) => {val prop = new java.util.Properties; prop.load(reader); Map(prop.toList: _*)}).
      reduce(_ ++ _)

    resolve(rawMap)

  }

  def properties: Map[String, String] =  {
    loadProperties(resources.map(Files.newInputStream(_)))
  }

  def resolve(source: Map[String, String]):Map[String,String] = {
    val converted = source.mapValues(_.replace("${","€{"))
    val unResolved = Set(converted.map((s) => (for (found <- "€\\{(.*?)\\}".r findAllMatchIn s._2) yield found.group(1)).toList).reduce(_ ++ _):_*)
    val unResolvable = unResolved.filter((s) => converted.get(s).isEmpty)
    if ((unResolved -- unResolvable).isEmpty)
      converted.mapValues(_.replace("€{","${"))
    else
      resolve(converted.mapValues((s) => "€\\{(.*?)\\}".r replaceAllIn (s, m => {converted.getOrElse(m.group(1), "€{" + m.group(1) + "}") })))
  }

}
