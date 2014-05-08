package fi.vm.sade.hakurekisteri.henkilo

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriResource
import scala.Some
import java.util.{UUID, Date}
import fi.vm.sade.hakurekisteri.suoritus.Suoritus

trait HenkiloSwaggerApi
    { this: HakurekisteriResource[Henkilo, CreateHenkiloCommand] =>

  override protected val applicationName = Some("henkilot")
  protected val applicationDescription = "Henkilötietojen rajapinta"

  val query = (apiOperation[Seq[Henkilo]]("henkilot")
    .summary("näyttää kaikki henkilötiedot")
    .notes("Näyttää kaikki henkilötiedot. Voit myös hakea oid-parametrillä.")
    .parameter(queryParam[Option[String]]("oid").description("haetun henkilon oid"))
  )

  val create = apiOperation[Henkilo]("lisääHenkilo")
    .summary("luo henkilön ja palauttaa sen tiedot")
    .parameter(bodyParam[Henkilo]("henkilo").description("uusi henkilö").required)

  val update = apiOperation[Henkilo]("päivitäHenkilö")
    .summary("päivittää olemassa olevaa henkilöä ja palauttaa sen tiedot")
    .parameter(pathParam[String]("id").description("henkilön uuid").required)
    .parameter(bodyParam[Henkilo]("henkilo").description("päivitettävä henkilö").required)

  val read = apiOperation[Henkilo]("haeHenkilo")
    .summary("hakee henkilön tiedot")
    .parameter(pathParam[String]("id").description("henkilön uuid").required)
}
