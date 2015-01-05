package fi.vm.sade.hakurekisteri

import java.io.InputStream
import java.nio.file.{Files, Paths}

import fi.vm.sade.hakurekisteri.integration.ServiceConfig
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusConfig
import fi.vm.sade.hakurekisteri.integration.virta.VirtaConfig
import fi.vm.sade.hakurekisteri.integration.ytl.YTLConfig
import org.joda.time.LocalTime
import org.slf4j.LoggerFactory

import scala.util.{Failure, Success, Try}

object Config {
  val log = LoggerFactory.getLogger(getClass)
  val homeDir = sys.props.getOrElse("user.home", "")
  val ophConfDir = Paths.get(homeDir, "/oph-configuration/")

  val propertyLocations = Seq("suoritusrekisteri.properties", "common.properties")

  val jndiName = "java:comp/env/jdbc/suoritusrekisteri"

  // by default the service urls point to QA
  val hostQa = "testi.virkailija.opintopolku.fi"
  val casUrlQa = s"https://$hostQa/cas"
  val organisaatioServiceUrlQa = s"https://$hostQa/organisaatio-service"
  val hakuappServiceUrlQa = s"https://$hostQa/haku-app"
  val koodistoServiceUrlQa = s"https://$hostQa/koodisto-service"
  val parameterServiceUrlQa = s"https://$hostQa/ohjausparametrit-service"
  val valintaTulosServiceUrlQa = s"https://$hostQa/valinta-tulos-service"

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
  lazy val properties: Map[String, String] = {
    val propertyFiles = resources.map(f => {
      val t = Try(Files.newInputStream(f))
      if (t.isFailure) log.error("could not load property file", t.failed.get)
      t
    }).collect {
      case Success(is) => is
    }
    loadProperties(propertyFiles)
  }

  // props
  val ophOrganisaatioOid = properties.getOrElse("suoritusrekisteri.organisaatio.oid.oph", "1.2.246.562.10.00000000001")
  val ytlOrganisaatioOid = properties.getOrElse("suoritusrekisteri.organisaatio.oid.ytl", "1.2.246.562.10.43628088406")
  val cscOrganisaatioOid = properties.getOrElse("suoritusrekisteri.organisaatio.oid.csc", "1.2.246.562.10.2013112012294919827487")
  val tuntematonOrganisaatioOid = properties.getOrElse("suoritusrekisteri.organisaatio.oid.tuntematon", "1.2.246.562.10.57118763579")

  val yotutkintoKomoOid = properties.getOrElse("suoritusrekisteri.komo.oid.yotutkinto", "1.2.246.562.5.2013061010184237348007")
  val perusopetusKomoOid = properties.getOrElse("suoritusrekisteri.komo.oid.perusopetus", "1.2.246.562.13.62959769647")
  val lisaopetusKomoOid = properties.getOrElse("suoritusrekisteri.komo.oid.lisaopetus", "1.2.246.562.5.2013112814572435044876")
  val ammattistarttiKomoOid = properties.getOrElse("suoritusrekisteri.komo.oid.ammattistartti", "1.2.246.562.5.2013112814572438136372")
  val valmentavaKomoOid = properties.getOrElse("suoritusrekisteri.komo.oid.valmentava", "1.2.246.562.5.2013112814572435755085")
  val ammatilliseenvalmistavaKomoOid = properties.getOrElse("suoritusrekisteri.komo.oid.ammatilliseenvalmistava", "1.2.246.562.5.2013112814572441001730")
  val ulkomainenkorvaavaKomoOid = properties.getOrElse("suoritusrekisteri.komo.oid.ulkomainenkorvaava", "1.2.246.562.13.86722481404")
  val lukioKomoOid = properties.getOrElse("suoritusrekisteri.komo.oid.lukio", "TODO lukio komo oid")
  val ammatillinenKomoOid = properties.getOrElse("suoritusrekisteri.komo.oid.ammatillinen", "TODO ammatillinen komo oid")
  val lukioonvalmistavaKomoOid = properties.getOrElse("suoritusrekisteri.komo.oid.lukioonvalmistava", "1.2.246.562.5.2013112814572429142840")

  val ensikertalainenCacheHours = properties.getOrElse("suoritusrekisteri.cache.hours.ensikertalainen", "6").toInt
  val koodistoCacheHours = properties.getOrElse("suoritusrekisteri.cache.hours.koodisto", "12").toInt
  val organisaatioCacheHours = properties.getOrElse("suoritusrekisteri.cache.hours.organisaatio", "12").toInt
  val tarjontaCacheHours = properties.getOrElse("suoritusrekisteri.cache.hours.tarjonta", "12").toInt
  val valintatulosCacheHours = properties.getOrElse("suoritusrekisteri.cache.hours.valintatulos", "4").toInt
  val hakuRefreshTimeHours = properties.getOrElse("suoritusrekisteri.refresh.time.hours.haku", "12").toInt
  val hakemusRefreshTimeHours = properties.getOrElse("suoritusrekisteri.refresh.time.hours.hakemus", "2").toInt
  val valintatulosRefreshTimeHours = properties.getOrElse("suoritusrekisteri.refresh.time.hours.valintatulos", "2").toInt

  val httpClientConnectionTimeout = properties.getOrElse("suoritusrekisteri.http.client.connection.timeout.ms", "10000").toInt
  val httpClientRequestTimeout = properties.getOrElse("suoritusrekisteri.http.client.request.timeout.ms", "180000").toInt
  val httpClientMaxRetries = properties.getOrElse("suoritusrekisteri.http.client.max.retries", "1").toInt
  val httpClientSlowRequest = properties.getOrElse("suoritusrekisteri.http.client.slow.request.ms", "1000").toLong

  val tiedonsiirtoStorageDir = properties.getOrElse("suoritusrekisteri.tiedonsiirto.storage.dir", System.getProperty("java.io.tmpdir"))

  val serviceUser = properties.get("suoritusrekisteri.app.username")
  val servicePassword = properties.get("suoritusrekisteri.app.password")

  val casUrl = Some(properties.getOrElse("web.url.cas", casUrlQa))
  val sijoitteluServiceUrl = properties.getOrElse("cas.service.sijoittelu-service", sijoitteluServiceUrlQa)
  val tarjontaServiceUrl = properties.getOrElse("cas.service.tarjonta-service", tarjontaServiceUrlQa)
  val henkiloServiceUrl = properties.getOrElse("cas.service.authentication-service", henkiloServiceUrlQa)
  val hakuappServiceUrl = properties.getOrElse("cas.service.haku-service", hakuappServiceUrlQa)
  val koodistoServiceUrl = properties.getOrElse("cas.service.koodisto-service", koodistoServiceUrlQa)
  val parameterServiceUrl = properties.getOrElse("cas.service.ohjausparametrit-service", parameterServiceUrlQa)
  val organisaatioServiceUrl = properties.getOrElse("cas.service.organisaatio-service", organisaatioServiceUrlQa)
  val valintaTulosServiceUrl = properties.getOrElse("cas.service.valintatulos-service", valintaTulosServiceUrlQa)
  val organisaatioSoapServiceUrl = properties.getOrElse("cas.service.organisaatio-service", organisaatioServiceUrlQa) + "/services/organisaatioService"
  val maxApplications = properties.getOrElse("suoritusrekisteri.hakijat.max.applications", "2000").toInt
  val virtaServiceUrl = properties.getOrElse("suoritusrekisteri.virta.service.url", virtaServiceUrlTest)
  val virtaJarjestelma = properties.getOrElse("suoritusrekisteri.virta.jarjestelma", virtaJarjestelmaTest)
  val virtaTunnus = properties.getOrElse("suoritusrekisteri.virta.tunnus", virtaTunnusTest)
  val virtaAvain = properties.getOrElse("suoritusrekisteri.virta.avain", virtaAvainTest)

  val virtaConfig = VirtaConfig(virtaServiceUrl, virtaJarjestelma, virtaTunnus, virtaAvain)
  val henkiloConfig = ServiceConfig(casUrl, henkiloServiceUrl, serviceUser, servicePassword)
  val sijoitteluConfig = ServiceConfig(casUrl, sijoitteluServiceUrl, serviceUser, servicePassword)
  val parameterConfig = ServiceConfig(serviceUrl = parameterServiceUrl)
  val hakemusConfig = HakemusConfig(ServiceConfig(casUrl, hakuappServiceUrl, serviceUser, servicePassword), maxApplications)
  val tarjontaConfig = ServiceConfig(serviceUrl = tarjontaServiceUrl)
  val koodistoConfig = ServiceConfig(serviceUrl = koodistoServiceUrl)
  val organisaatioConfig = ServiceConfig(serviceUrl = organisaatioServiceUrl)
  val valintaTulosConfig = ServiceConfig(serviceUrl = valintaTulosServiceUrl)

  import org.scalatra.util.RicherString._

  val ytlConfig = for (
    host <- properties.get("suoritusrekisteri.ytl.host").flatMap(_.blankOption);
    user <- properties.get("suoritusrekisteri.ytl.user").flatMap(_.blankOption);
    password <- properties.get("suoritusrekisteri.ytl.password").flatMap(_.blankOption);
    inbox <- properties.get("suoritusrekisteri.ytl.inbox").flatMap(_.blankOption);
    outbox <- properties.get("suoritusrekisteri.ytl.outbox").flatMap(_.blankOption);
    poll <- properties.get("suoritusrekisteri.ytl.poll").flatMap(_.blankOption);
    localStore <- properties.get("suoritusrekisteri.ytl.localstore").flatMap(_.blankOption)
  ) yield YTLConfig(host, user, password, inbox, outbox, poll.split(";").map(LocalTime.parse), localStore)

  // val amqUrl = OPHSecurity.config.properties.get("activemq.brokerurl").getOrElse("failover:tcp://luokka.hard.ware.fi:61616")
  // val amqQueue = properties.getOrElse("activemq.queue.name.log", "Sade.Log")

  def loadProperties(resources: Seq[InputStream]): Map[String, String] = {
    import scala.collection.JavaConversions._
    val rawMap = resources.map((reader) => {val prop = new java.util.Properties; prop.load(reader); Map(prop.toList: _*)}).foldLeft(Map[String, String]())(_ ++ _)

    resolve(rawMap)
  }

  def resolve(source: Map[String, String]): Map[String, String] = {
    val converted = source.mapValues(_.replace("${","€{"))
    val unResolved = Set(converted.map((s) => (for (found <- "€\\{(.*?)\\}".r findAllMatchIn s._2) yield found.group(1)).toList).foldLeft(List[String]())(_ ++ _):_*)
    val unResolvable = unResolved.filter((s) => converted.get(s).isEmpty)
    if ((unResolved -- unResolvable).isEmpty)
      converted.mapValues(_.replace("€{","${"))
    else
      resolve(converted.mapValues((s) => "€\\{(.*?)\\}".r replaceAllIn (s, m => {converted.getOrElse(m.group(1), "€{" + m.group(1) + "}") })))
  }
}
