package siirto

import akka.event.{Logging, LoggingAdapter}
import akka.actor.ActorSystem
import org.scalatra.{MovedPermanently, Ok, NotFound}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack

class SchemaServlet(schemas: SchemaDefinition*)(implicit val system: ActorSystem)
    extends HakuJaValintarekisteriStack {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  val schemaCache = schemas.map((sd) => sd.schemaLocation -> sd.schema).toMap

  get("/:schema") {
    params.get("schema") match {
      case Some("perustiedot.xsd") =>
        MovedPermanently("../rest/v1/siirto/perustiedot/schema/perustiedot.xsd")
      case Some("perustiedot-koodisto.xsd") =>
        MovedPermanently("../rest/v1/siirto/perustiedot/schema/perustiedot-koodisto.xsd")
      case Some("arvosanat.xsd") =>
        MovedPermanently("../rest/v1/siirto/arvosanat/schema/arvosanat.xsd")
      case Some("arvosanat-koodisto.xsd") =>
        MovedPermanently("../rest/v1/siirto/arvosanat/schema/arvosanat-koodisto.xsd")
      case Some(s) =>
        schemaCache.get(params("schema")).fold(NotFound()) {
          contentType = "application/xml"
          Ok(_)
        }
      case None => NotFound()
    }

  }

}
