package fi.vm.sade.hakurekisteri.rest


import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.domain.{Suoritus, Opiskelija}
import org.scalatra._
import fi.vm.sade.hakurekisteri.query.OpiskelijaQuery
import org.slf4j.LoggerFactory
import fi.vm.sade.hakurekisteri.domain.Opiskelija
import scala.Some

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
