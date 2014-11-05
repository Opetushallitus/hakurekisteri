package siirto

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import akka.event.{Logging, LoggingAdapter}
import akka.actor.ActorSystem
import org.scalatra.{Ok, NotFound}


class SchemaServlet(schemas: SchemaDefinition*)(implicit val system: ActorSystem) extends HakuJaValintarekisteriStack {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  val schemaCache = schemas.map((sd) => sd.schemaLocation -> sd.schema).toMap

  get("/:schema"){
    schemaCache.get(params("schema")).fold(NotFound()){
      contentType = "application/xml"
      Ok(_)
    }
  }



}
