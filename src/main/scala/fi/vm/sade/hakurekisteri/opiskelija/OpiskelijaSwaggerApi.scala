package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import scala.Some
import java.util.{UUID, Date}

trait OpiskelijaSwaggerApi
    { this: HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand] =>

  override protected val applicationName = Some("opiskelijat")
  protected val applicationDescription = "Opiskelijatietojen rajapinta."

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

  create(apiOperation[Opiskelija]("lisääOpiskelija")
    .parameter(bodyParam[Opiskelija]("lisääOpiskelija").description("Uusi opiskelija").required)
    .summary("luo Opiskelijan ja palauttaa sen tiedot"))

  update(apiOperation[Opiskelija]("päivitäOpiskelijaa")) // parameter pathParam[UUID]("id").description("päivitettävän opiskelijan id")
}
