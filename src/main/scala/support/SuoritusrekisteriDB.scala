package support

import java.util.Properties

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import org.slf4j.LoggerFactory


class SuoritusrekisteriDB(config: Config)(implicit val system: ActorSystem) {
  val logger = LoggerFactory.getLogger(getClass)

  def start(): Database = {
    val database: Database = {
      import collection.JavaConverters._
      logger.info("Profile: " + config.profile)
      config.profile match {
        case "it" | "dev" => {
          Database.forURL(config.databaseUrl, user=config.postgresUser, password=config.postgresPassword, driver = "org.postgresql.Driver")
        }
        case "default" => {
          val javaProperties = new Properties
          javaProperties.putAll(config.properties.asJava)
          Database.forConfig("suoritusrekisteri.db", ConfigFactory.parseProperties(javaProperties))
        }
        case _ => throw new RuntimeException("Unsupported hakurekisteri.profile value " + config.profile)
      }
    }
    system.registerOnTermination(database.close())
    database
  }

}
