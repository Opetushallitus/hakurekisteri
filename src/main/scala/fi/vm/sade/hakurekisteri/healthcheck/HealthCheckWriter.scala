package fi.vm.sade.hakurekisteri.healthcheck

import akka.actor.{ActorLogging, ActorRef, Actor}
import scala.concurrent.Future
import akka.dispatch.ExecutionContexts
import java.util.concurrent.Executors
import org.json4s.Extraction
import org.json4s.jackson.JsonMethods._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import java.nio.file.{Paths, Files}
import java.nio.charset.StandardCharsets
import scala.compat.Platform

class HealthCheckWriter(health: ActorRef) extends Actor with HakurekisteriJsonSupport with ActorLogging {

  val ioEc = ExecutionContexts.fromExecutor(Executors.newSingleThreadExecutor())
  val tmpDir = Paths.get(System.getProperty("java.io.tmpdir"))

  def save(healthcheck: Healhcheck) = Future {
    val json = Extraction.decompose(healthcheck)
    import scala.collection.JavaConversions._
    val serialized: java.lang.Iterable[String] = pretty(json).lines.toList
    Files.write(tmpDir.resolve(s"healthcheck${Platform.currentTime}.json"), serialized, StandardCharsets.UTF_8)
  }(ioEc)

  override def receive: Actor.Receive = {
    case Query => health ! "healthcheck"
    case h:Healhcheck => save(h).onFailure{
      case e:Throwable => log.error(e, "error writing health check to temp dir")
    }(context.dispatcher)
  }
}

object Query
