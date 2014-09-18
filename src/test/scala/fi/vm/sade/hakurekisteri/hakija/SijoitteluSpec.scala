package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.integration.sijoittelu.SijoitteluPagination
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

class SijoitteluSpec extends FlatSpec with ShouldMatchers {
  behavior of "Sijoittelu parsinta"

  trait Parsed extends HakurekisteriJsonSupport {
    def casMap[T: ClassTag: TypeTag](value: T) = {
      val m = runtimeMirror(getClass.getClassLoader)
      val im = m.reflect(value)
      typeOf[T].members.collect{ case m:MethodSymbol if m.isCaseAccessor => m}.map(im.reflectMethod).map((m) => m.symbol.name.toString -> m()).toMap
    }

    import org.json4s.jackson.Serialization.read
    val resource = read[SijoitteluPagination](json)
  }

  it should "find pisteet" in new Parsed() {
    resource.results.head.hakutoiveet.get.flatMap(_.hakutoiveenValintatapajonot.get.map(_.pisteet.get)) should be(List(26.0, 24.0, 24.0, 24.0, 24.0))
  }

  val json = """{"totalCount": 4932, "results": [
               |    {
               |        "hakemusOid": "1.2.246.562.11.00000826624",
               |        "etunimi": "Johanna III",
               |        "sukunimi": "Karhuvaara",
               |        "hakutoiveet": [
               |            {
               |                "hakutoive": 1,
               |                "hakukohdeOid": "1.2.246.562.5.787057838810",
               |                "tarjoajaOid": "1.2.246.562.10.61998115317",
               |                "pistetiedot": [
               |                    {
               |                        "tunniste": "kielikoe_fi",
               |                        "arvo": null,
               |                        "laskennallinenArvo": "false",
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    },
               |                    {
               |                        "tunniste": "Turvallisuusalan perustutkinto, pk, pääsy- ja soveltuvuuskoe",
               |                        "arvo": null,
               |                        "laskennallinenArvo": null,
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    },
               |                    {
               |                        "tunniste": "1_2_246_562_5_787057838810_urheilija_lisapiste",
               |                        "arvo": null,
               |                        "laskennallinenArvo": "0.0",
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    }
               |                ],
               |                "hakutoiveenValintatapajonot": [
               |                    {
               |                        "valintatapajonoPrioriteetti": 1,
               |                        "valintatapajonoOid": "1397648604188-8547754549723323482",
               |                        "valintatapajonoNimi": "Varsinaisen valinnanvaiheen valintatapajono",
               |                        "jonosija": 2,
               |                        "paasyJaSoveltuvuusKokeenTulos": null,
               |                        "varasijanNumero": null,
               |                        "tila": "HYLATTY",
               |                        "tilanKuvaukset": {
               |                            "FI": "Pakollisen syötettävän kentän arvo on merkitsemättä (tunniste Turvallisuusalan perustutkinto, pk, pääsy- ja soveltuvuuskoe)"
               |                        },
               |                        "vastaanottotieto": null,
               |                        "hyvaksyttyHarkinnanvaraisesti": false,
               |                        "tasasijaJonosija": 1,
               |                        "pisteet": 26.0,
               |                        "alinHyvaksyttyPistemaara": null,
               |                        "hakeneet": 14,
               |                        "hyvaksytty": 0,
               |                        "varalla": 0
               |                    }
               |                ]
               |            },
               |            {
               |                "hakutoive": 2,
               |                "hakukohdeOid": "1.2.246.562.5.58562531218",
               |                "tarjoajaOid": "1.2.246.562.10.75537153407",
               |                "pistetiedot": [
               |                    {
               |                        "tunniste": "1_2_246_562_5_58562531218_urheilija_lisapiste",
               |                        "arvo": null,
               |                        "laskennallinenArvo": "0.0",
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    },
               |                    {
               |                        "tunniste": "kielikoe_fi",
               |                        "arvo": null,
               |                        "laskennallinenArvo": "false",
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    }
               |                ],
               |                "hakutoiveenValintatapajonot": [
               |                    {
               |                        "valintatapajonoPrioriteetti": 2,
               |                        "valintatapajonoOid": "13964193758182595465253599062906",
               |                        "valintatapajonoNimi": "Varsinaisen valinnanvaiheen valintatapajono",
               |                        "jonosija": 3,
               |                        "paasyJaSoveltuvuusKokeenTulos": null,
               |                        "varasijanNumero": null,
               |                        "tila": "HYVAKSYTTY",
               |                        "tilanKuvaukset": {},
               |                        "vastaanottotieto": null,
               |                        "hyvaksyttyHarkinnanvaraisesti": false,
               |                        "tasasijaJonosija": 1,
               |                        "pisteet": 24.0,
               |                        "alinHyvaksyttyPistemaara": 9.0,
               |                        "hakeneet": 15,
               |                        "hyvaksytty": 3,
               |                        "varalla": 0
               |                    }
               |                ]
               |            },
               |            {
               |                "hakutoive": 3,
               |                "hakukohdeOid": "1.2.246.562.5.46411660551",
               |                "tarjoajaOid": "1.2.246.562.10.96247340308",
               |                "pistetiedot": [
               |                    {
               |                        "tunniste": "kielikoe_fi",
               |                        "arvo": null,
               |                        "laskennallinenArvo": "false",
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    },
               |                    {
               |                        "tunniste": "1_2_246_562_5_46411660551_urheilija_lisapiste",
               |                        "arvo": null,
               |                        "laskennallinenArvo": "0.0",
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    }
               |                ],
               |                "hakutoiveenValintatapajonot": [
               |                    {
               |                        "valintatapajonoPrioriteetti": 3,
               |                        "valintatapajonoOid": "13976482222459142791852981024059",
               |                        "valintatapajonoNimi": "Varsinaisen valinnanvaiheen valintatapajono",
               |                        "jonosija": 9,
               |                        "paasyJaSoveltuvuusKokeenTulos": null,
               |                        "varasijanNumero": null,
               |                        "tila": "PERUUNTUNUT",
               |                        "tilanKuvaukset": {},
               |                        "vastaanottotieto": null,
               |                        "hyvaksyttyHarkinnanvaraisesti": false,
               |                        "tasasijaJonosija": 1,
               |                        "pisteet": 24.0,
               |                        "alinHyvaksyttyPistemaara": 8.0,
               |                        "hakeneet": 40,
               |                        "hyvaksytty": 21,
               |                        "varalla": 0
               |                    }
               |                ]
               |            },
               |            {
               |                "hakutoive": 4,
               |                "hakukohdeOid": "1.2.246.562.5.35230716843",
               |                "tarjoajaOid": "1.2.246.562.10.16538823663",
               |                "pistetiedot": [
               |                    {
               |                        "tunniste": "kielikoe_fi",
               |                        "arvo": null,
               |                        "laskennallinenArvo": "false",
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    },
               |                    {
               |                        "tunniste": "1_2_246_562_5_35230716843_urheilija_lisapiste",
               |                        "arvo": null,
               |                        "laskennallinenArvo": "0.0",
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    },
               |                    {
               |                        "tunniste": "Turvallisuusalan perustutkinto, pk, pääsy- ja soveltuvuuskoe",
               |                        "arvo": null,
               |                        "laskennallinenArvo": null,
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    }
               |                ],
               |                "hakutoiveenValintatapajonot": [
               |                    {
               |                        "valintatapajonoPrioriteetti": 4,
               |                        "valintatapajonoOid": "13964192843861986400019103948473",
               |                        "valintatapajonoNimi": "Varsinaisen valinnanvaiheen valintatapajono",
               |                        "jonosija": 4,
               |                        "paasyJaSoveltuvuusKokeenTulos": null,
               |                        "varasijanNumero": null,
               |                        "tila": "HYLATTY",
               |                        "tilanKuvaukset": {
               |                            "FI": "Pakollisen syötettävän kentän arvo on merkitsemättä (tunniste Turvallisuusalan perustutkinto, pk, pääsy- ja soveltuvuuskoe)"
               |                        },
               |                        "vastaanottotieto": null,
               |                        "hyvaksyttyHarkinnanvaraisesti": false,
               |                        "tasasijaJonosija": 1,
               |                        "pisteet": 24.0,
               |                        "alinHyvaksyttyPistemaara": null,
               |                        "hakeneet": 18,
               |                        "hyvaksytty": 0,
               |                        "varalla": 0
               |                    }
               |                ]
               |            },
               |            {
               |                "hakutoive": 5,
               |                "hakukohdeOid": "1.2.246.562.5.45005150524",
               |                "tarjoajaOid": "1.2.246.562.10.16538823663",
               |                "pistetiedot": [
               |                    {
               |                        "tunniste": "1_2_246_562_5_45005150524_urheilija_lisapiste",
               |                        "arvo": null,
               |                        "laskennallinenArvo": "0.0",
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    },
               |                    {
               |                        "tunniste": "kielikoe_fi",
               |                        "arvo": null,
               |                        "laskennallinenArvo": "false",
               |                        "osallistuminen": "MERKITSEMATTA"
               |                    }
               |                ],
               |                "hakutoiveenValintatapajonot": [
               |                    {
               |                        "valintatapajonoPrioriteetti": 5,
               |                        "valintatapajonoOid": "1396419354584-2673393002705591451",
               |                        "valintatapajonoNimi": "Varsinaisen valinnanvaiheen valintatapajono",
               |                        "jonosija": 7,
               |                        "paasyJaSoveltuvuusKokeenTulos": null,
               |                        "varasijanNumero": null,
               |                        "tila": "PERUUNTUNUT",
               |                        "tilanKuvaukset": {},
               |                        "vastaanottotieto": null,
               |                        "hyvaksyttyHarkinnanvaraisesti": false,
               |                        "tasasijaJonosija": 1,
               |                        "pisteet": 24.0,
               |                        "alinHyvaksyttyPistemaara": 8.0,
               |                        "hakeneet": 29,
               |                        "hyvaksytty": 9,
               |                        "varalla": 0
               |                    }
               |                ]
               |            }
               |        ]
               |    }
               |]}""".stripMargin
}
