package fi.vm.sade.hakurekisteri.henkilo

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import scala.Some
import java.util.{UUID, Date}
import fi.vm.sade.hakurekisteri.suoritus.Suoritus

trait HenkiloSwaggerApi
    { this: HakurekisteriResource[Henkilo, CreateHenkiloCommand] =>

  override protected val applicationName = Some("henkilot")
  protected val applicationDescription = "Henkilötietojen rajapinta."

  val query = (apiOperation[Seq[Henkilo]]("henkilot")
    summary "Näytä kaikki henkilötiedot"
    notes "Näyttää kaikki henkilötiedot. Voit myös hakea oid-parametrillä."
    parameter queryParam[Option[String]]("oid").description("haetun henkilon oid")
  )

  val create = apiOperation[Henkilo]("lisääHenkilo")
    .parameter(bodyParam[Henkilo]("lisääHenkilo").description("Uusi henkilö").required)
    .summary("luo henkilön ja palauttaa sen tiedot")

  val update = apiOperation[Henkilo]("päivitäHenkilöä") // parameter pathParam[UUID]("id").description("päivitettävän henkilön id")

  val read = apiOperation[Henkilo]("haeHenkilo")
    .parameter(pathParam[String]("id").description("henkilön uuid"))
}
