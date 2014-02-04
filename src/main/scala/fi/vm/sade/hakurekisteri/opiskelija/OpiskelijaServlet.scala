package fi.vm.sade.hakurekisteri.opiskelija

import _root_.akka.actor.{ActorRef, ActorSystem}
import org.scalatra.swagger.Swagger
import scala.Some
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource

class OpiskelijaServlet(opiskelijaActor: ActorRef)(implicit val swagger: Swagger, system: ActorSystem) extends HakurekisteriResource[Opiskelija](opiskelijaActor)  {
  override protected val applicationName = Some("opiskelijat")
  protected val applicationDescription = "Opiskelijatietojen rajapinta."

  val basicGet = apiOperation[Seq[Opiskelija]]("opiskelijat").
  summary("Näytä kaikki opiskelijatiedot").
  notes("Näyttää kaikki opiskelijatiedot. Voit myös hakea eri parametreillä.").
  parameter(queryParam[Option[String]]("henkilo").description("suorittaneen henkilon oid")).
  parameter(queryParam[Option[String]]("kausi").description("suorituksen päättymisen kausi").allowableValues("S","K")).
  parameter(queryParam[Option[String]]("vuosi").description("suorituksen päättymisen vuosi"))

  readOperation(basicGet, OpiskelijaQuery(_))

}
