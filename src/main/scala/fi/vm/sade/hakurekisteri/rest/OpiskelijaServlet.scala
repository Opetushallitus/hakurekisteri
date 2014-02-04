package fi.vm.sade.hakurekisteri.rest


import akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.domain.{Suoritus, Opiskelija}
import org.scalatra.{FutureSupport, AsyncResult}
import fi.vm.sade.hakurekisteri.query.OpiskelijaQuery
import org.slf4j.LoggerFactory

class OpiskelijaServlet(system: ActorSystem, opiskelijaActor: ActorRef)(implicit val swagger: Swagger) extends HakurekisteriResource(system) with FutureSupport {
  override protected val applicationName = Some("opiskelijat")
  protected val applicationDescription = "Opiskelijatietojen rajapinta."


  val haeOpiskelijat =
    (apiOperation[Seq[Opiskelija]]("opsikelijat")
      summary "Näytä kaikki opiskelijatiedot"
      notes "Näyttää kaikki opiskelijatiedot. Voit myös hakea eri parametreillä."
      parameter queryParam[Option[String]]("henkilo").description("suorittaneen henkilon oid")
      parameter queryParam[Option[String]]("kausi").description("suorituksen päättymisen kausi").allowableValues("S","K")
      parameter queryParam[Option[String]]("vuosi").description("suorituksen päättymisen vuosi"))

  get("/", operation(haeOpiskelijat)) {
    new AsyncResult() {
      val is = opiskelijaActor ? OpiskelijaQuery(params)
    }
  }

  post("/") {
    new AsyncResult() {
      val is = opiskelijaActor ? parsedBody.extract[Opiskelija]
    }
  }
}
