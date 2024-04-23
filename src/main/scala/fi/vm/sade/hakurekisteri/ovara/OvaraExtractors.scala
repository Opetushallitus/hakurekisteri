package fi.vm.sade.hakurekisteri.ovara

import slick.jdbc.GetResult

//case class SiirtotiedostoSuoritus(resource_id: String,
//                                  komo: String,
//                                  myontaja: String,
//                                  tila: String,
//                                  valmistuminen: String,
//                                  henkilo_oid: String,
//                                  yksilollistaminen: String,
//                                  suoritus_kieli: String,
//                                  source: String,
//                                  kuvaus: Option[String],
//                                  vuosi: Option[String],
//                                  tyyppi: Option[String],
//                                  index: Option[String],
//                                  vahvistettu: Boolean,
//                                  current: Boolean, //Käytännössä aina true, koska ei-currenteja ei ladota siirtotiedostoihin
//                                  lahde_arvot: Map[String, String]
//                                 )
trait OvaraExtractors {

  protected implicit val getSiirtotiedostoSuoritusResult: GetResult[SiirtotiedostoSuoritus] =
    GetResult(r =>
      SiirtotiedostoSuoritus(
        resource_id = r.nextString(),
        komo = r.nextString(),
        myontaja = r.nextString(),
        tila = r.nextString(),
        valmistuminen = r.nextString(),
        henkilo_oid = r.nextString(),
        yksilollistaminen = r.nextString(),
        suoritus_kieli = r.nextString(),
        source = r.nextString(),
        kuvaus = r.nextStringOption(),
        vuosi = r.nextStringOption(),
        tyyppi = r.nextStringOption(),
        index = r.nextStringOption(),
        vahvistettu = r.nextBoolean(),
        current = r.nextBoolean(),
        lahde_arvot = Map("foo" -> r.nextString()) //Todo, parsitaan kannan jsonb mapiksi
      )
    )
}
