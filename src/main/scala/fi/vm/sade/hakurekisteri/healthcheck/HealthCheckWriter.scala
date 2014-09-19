package fi.vm.sade.hakurekisteri.healthcheck

import akka.actor.{ActorRef, Actor}
import scala.concurrent.{ExecutionContext, Future}
import akka.dispatch.ExecutionContexts
import java.util.concurrent.Executors
import org.json4s.Extraction
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import akka.event.Logging

class HealthCheckWriter(health: ActorRef) extends Actor with HakurekisteriJsonSupport {

  val ioEc = ExecutionContexts.fromExecutor(Executors.newSingleThreadExecutor())

  import org.json4s.jackson.JsonMethods._

  val logger = Logging(context.system, this)


  val tmpDir = Paths.get(System.getProperty("java.io.tmpdir"))

  def save(healthcheck: Healhcheck) = Future {
    val json = Extraction.decompose(healthcheck)
    import scala.collection.JavaConversions._
    val serialized: java.lang.Iterable[String] = pretty(json).lines.toList
    Files.write(tmpDir.resolve("healthcheck.json"), serialized, StandardCharsets.UTF_8)


  }(ioEc)

  override def receive: Actor.Receive = {
    case Query => health ! "healthcheck"
    case h:Healhcheck => save(h).onFailure{
      case e:Throwable => logger.error(e, "error writing health check to temp dir")
    }

  }
}


object Query
