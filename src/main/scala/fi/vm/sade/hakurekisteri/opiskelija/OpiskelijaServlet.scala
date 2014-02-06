package fi.vm.sade.hakurekisteri.opiskelija

import _root_.akka.actor.{ActorRef, ActorSystem}
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.rest.support.{Query, HakurekisteriResource}
import scala.Some
import java.util.Date

class OpiskelijaServlet(opiskelijaActor: ActorRef)(implicit val swagger: Swagger, system: ActorSystem) extends HakurekisteriResource[Opiskelija](opiskelijaActor)  {
  override protected val applicationName = Some("opiskelijat")
  protected val applicationDescription = "Opiskelijatietojen rajapinta."

  implicit val query: (Map[String,String]) => Query[Opiskelija] = OpiskelijaQuery(_)

  read(apiOperation[Seq[Opiskelija]]("opiskelijat")
    summary "Näytä kaikki opiskelijatiedot"
    notes "Näyttää kaikki opiskelijatiedot. Voit myös hakea eri parametreillä."
    parameter queryParam[Option[String]]("henkilo").description("haetun henkilon oid")
    parameter queryParam[Option[String]]("kausi").description("kausi jonka tietoja haetaan").allowableValues("S", "K")
    parameter queryParam[Option[String]]("vuosi").description("vuosi jonka tietoja haetaan")
    parameter queryParam[Option[Date]]("paiva").description("päivä jonka tietoja haetaan")
    parameter queryParam[Option[String]]("oppilaitosOid").description("haetun oppilaitoksen oid")
    parameter queryParam[Option[String]]("luokka").description("haetun luokan nimi")
  )

}
