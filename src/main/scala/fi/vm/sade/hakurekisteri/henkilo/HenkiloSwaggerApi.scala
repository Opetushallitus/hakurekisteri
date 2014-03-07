package fi.vm.sade.hakurekisteri.henkilo

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import scala.Some
import java.util.{UUID, Date}

trait HenkiloSwaggerApi
    { this: HakurekisteriResource[Henkilo, CreateHenkiloCommand] =>

  override protected val applicationName = Some("henkilot")
  protected val applicationDescription = "Henkilötietojen rajapinta."

  val query = (apiOperation[Seq[Henkilo]]("henkilot")
    summary "Näytä kaikki opiskelijatiedot"
    notes "Näyttää kaikki opiskelijatiedot. Voit myös hakea eri parametreillä."
    parameter queryParam[Option[String]]("henkilo").description("haetun henkilon oid")
    parameter queryParam[Option[String]]("kausi").description("kausi jonka tietoja haetaan").allowableValues("S", "K")
    parameter queryParam[Option[String]]("vuosi").description("vuosi jonka tietoja haetaan")
    parameter queryParam[Option[Date]]("paiva").description("päivä jonka tietoja haetaan")
    parameter queryParam[Option[String]]("oppilaitosOid").description("haetun oppilaitoksen oid")
    parameter queryParam[Option[String]]("luokka").description("haetun luokan nimi")
  )

  val create = apiOperation[Henkilo]("lisääHenkilo")
    .parameter(bodyParam[Henkilo]("lisääHenkilo").description("Uusi henkilö").required)
    .summary("luo henkilön ja palauttaa sen tiedot")

  val update = apiOperation[Henkilo]("päivitäHenkilöä") // parameter pathParam[UUID]("id").description("päivitettävän henkilön id")
}
