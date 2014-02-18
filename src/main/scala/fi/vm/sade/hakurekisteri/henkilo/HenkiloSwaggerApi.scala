package fi.vm.sade.hakurekisteri.henkilo

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import scala.Some
import java.util.{UUID, Date}

trait HenkiloSwaggerApi
    { this: HakurekisteriResource[Henkilo, CreateHenkiloCommand] =>

  override protected val applicationName = Some("henkilot")
  protected val applicationDescription = "henkilötietojen rajapinta."

  read(apiOperation[Seq[Henkilo]]("henkilot")
    summary "Näytä kaikki opiskelijatiedot"
    notes "Näyttää kaikki opiskelijatiedot. Voit myös hakea eri parametreillä."
    parameter queryParam[Option[String]]("henkilo").description("haetun henkilon oid")
    parameter queryParam[Option[String]]("kausi").description("kausi jonka tietoja haetaan").allowableValues("S", "K")
    parameter queryParam[Option[String]]("vuosi").description("vuosi jonka tietoja haetaan")
    parameter queryParam[Option[Date]]("paiva").description("päivä jonka tietoja haetaan")
    parameter queryParam[Option[String]]("oppilaitosOid").description("haetun oppilaitoksen oid")
    parameter queryParam[Option[String]]("luokka").description("haetun luokan nimi")
  )

  create(apiOperation[Henkilo]("lisääOpiskelija")
    .parameter(bodyParam[Henkilo]("lisääOpiskelija").description("Uusi opiskelija").required)
    .summary("luo Opiskelijan ja palauttaa sen tiedot"))

  update(apiOperation[Henkilo]("päivitäHenkilöä")) // parameter pathParam[UUID]("id").description("päivitettävän henkilön id")
}
