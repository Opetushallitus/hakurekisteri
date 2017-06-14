package fi.vm.sade.hakurekisteri.rest.support

import java.util.UUID

import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.oppija.{Todistus, Oppija}
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, VirallinenSuoritus}
import org.joda.time.{LocalDate, DateTime}
import org.scalatest.{FlatSpec, Matchers}
import org.json4s.jackson.Serialization._

class OppijaSerializerSpec extends FlatSpec with Matchers {

  implicit val formats = HakurekisteriJsonSupport.format

  behavior of "OppijaSerializer"

  it should "deserialize Oppija" in {
    read[Oppija]("""{"id":"foo","oppijanumero":"foo","opiskelu":[],"suoritukset":[],"opiskeluoikeudet":[]}""") should be (Oppija("foo", Seq(), Seq(), Seq(), None))
  }

  it should "serialize Oppija" in {
    write(Oppija("foo", Seq(), Seq(), Seq(), None)) should be ("""{"id":"foo","oppijanumero":"foo","opiskelu":[],"suoritukset":[],"opiskeluoikeudet":[]}""")
  }

  it should "serialize 'full' Oppija" in {
    write(Oppija(
      "foo",
      Seq(Opiskelija("bar", "9", "9A", "foo", new DateTime(2015, 1, 1, 0, 0, 0, 0), None, "test")),
      Seq(Todistus(
        VirallinenSuoritus("koulutus_123456", "bar", "VALMIS", new LocalDate(2013, 1, 1), "foo", yksilollistaminen.Ei, "FI", None, vahv = true, "test"),
        Seq(Arvosana(UUID.fromString("61ce569d-a56f-40df-91af-901152c4346e"), Arvio410("10"), "AI", Some("FI"), valinnainen = false, None, "test", Map())))),
      Seq(Opiskeluoikeus(new LocalDate(2014, 1, 1), None, "foo", "koulutus_123456", "bar", "test")),
      None
    )) should be ("""{"id":"foo","oppijanumero":"foo","opiskelu":[{"oppilaitosOid":"bar","luokkataso":"9","luokka":"9A","henkiloOid":"foo","alkuPaiva":"2014-12-31T22:00:00.000Z","source":"test"}],"suoritukset":[{"suoritus":{"henkiloOid":"foo","source":"test","vahvistettu":true,"komo":"koulutus_123456","myontaja":"bar","tila":"VALMIS","valmistuminen":"01.01.2013","yksilollistaminen":"Ei","suoritusKieli":"FI"},"arvosanat":[{"suoritus":"61ce569d-a56f-40df-91af-901152c4346e","arvio":{"arvosana":"10","asteikko":"4-10"},"aine":"AI","lisatieto":"FI","valinnainen":false,"source":"test","lahdeArvot":{}}]}],"opiskeluoikeudet":[{"aika":{"alku":"2014-01-01T00:00:00.000+02:00"},"henkiloOid":"foo","komo":"koulutus_123456","myontaja":"bar","source":"test"}]}""")
  }

  it should "serialize list of Oppijas from OppijaResource" in {
    val oppijas = read[Seq[Oppija]](jstr)
    oppijas.foreach(_.id != null)
  }


  val jstr = """[
               |  {
               |    "id": "1.2.246.562.24.80165256023",
               |    "oppijanumero": "1.2.246.562.24.80165256023",
               |    "opiskelu": [],
               |    "suoritukset": [
               |      {
               |        "suoritus": {
               |          "henkiloOid": "1.2.246.562.24.80165256023",
               |          "source": "1.2.246.562.24.85360401416",
               |          "vahvistettu": true,
               |          "komo": "1.2.246.562.13.62959769647",
               |          "myontaja": "1.2.246.562.10.75930879863",
               |          "tila": "VALMIS",
               |          "valmistuminen": "04.06.2016",
               |          "yksilollistaminen": "Ei",
               |          "suoritusKieli": "FI",
               |          "id": "02327dff-2ba2-4b12-bef1-efcce3184c99"
               |        },
               |        "arvosanat": [
               |          {
               |            "id": "396eb29a-e519-4356-a649-e76e56b4bde0",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "B2",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false,
               |            "lisatieto": "DE"
               |          },
               |          {
               |            "id": "be1fe207-2b1f-4d2f-864f-2acec75b3539",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "HI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "1bc045d3-7ec1-4dd5-8a18-cb1528c3e3f5",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "B1",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false,
               |            "lisatieto": "SV"
               |          },
               |          {
               |            "id": "ed1410a6-c848-4278-b271-8196c315b4ad",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KT",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "72e07b2d-d6db-430c-b88c-969687018ba5",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "LI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "a778e7b9-aa9d-41e0-95d7-99bdaf3f9303",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "MU",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "7bd34a34-08d8-4a1f-9231-227a6f6456c3",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KU",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "380fcd40-bc2d-48a4-98b6-6f5458c1572a",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "BI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "1e1b5231-be5a-4fd2-8e5d-1d7eb001168a",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "TE",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "8505ddca-9ac4-4a36-9ee7-e1e7dea524c2",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "FY",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "87cbd72b-9cb2-48d3-b6c8-9ca283afdaf1",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "MA",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "ef225c3d-9f21-4a60-9153-2ee944960a31",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "YH",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "7d40babd-4c0d-4296-94b0-7c855f98fea4",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "AI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false,
               |            "lisatieto": "FI"
               |          },
               |          {
               |            "id": "9bc6bef2-c9de-4830-8fc2-7df8cae445e9",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KE",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "900ff31a-87cd-489c-ba6d-36a5eb5af08d",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "A1",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": true,
               |            "lisatieto": "EN",
               |            "jarjestys": 0
               |          },
               |          {
               |            "id": "2bcd6d04-5a92-4d79-bb68-974b2cf11307",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KO",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": true,
               |            "jarjestys": 0
               |          },
               |          {
               |            "id": "21bc2c9c-a42b-4444-8282-dcbf4be5e5af",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KS",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "2310c806-4977-4f18-b137-e708a6ae75a6",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "A1",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false,
               |            "lisatieto": "EN"
               |          },
               |          {
               |            "id": "25fa23d8-043b-472a-8df5-3ce3a6903925",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "GE",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "8e34ca39-1003-4e3f-9984-3ee8e390c0c5",
               |            "suoritus": "02327dff-2ba2-4b12-bef1-efcce3184c99",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KO",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          }
               |        ]
               |      }
               |    ],
               |    "opiskeluoikeudet": []
               |  },
               |  {
               |    "id": "1.2.246.562.24.23813478220",
               |    "oppijanumero": "1.2.246.562.24.23813478220",
               |    "opiskelu": [],
               |    "suoritukset": [
               |      {
               |        "suoritus": {
               |          "henkiloOid": "1.2.246.562.24.23813478220",
               |          "source": "1.2.246.562.24.85360401416",
               |          "vahvistettu": true,
               |          "komo": "1.2.246.562.13.62959769647",
               |          "myontaja": "1.2.246.562.10.88357703458",
               |          "tila": "VALMIS",
               |          "valmistuminen": "04.06.2016",
               |          "yksilollistaminen": "Ei",
               |          "suoritusKieli": "FI",
               |          "id": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae"
               |        },
               |        "arvosanat": [
               |          {
               |            "id": "9d879122-de89-4082-9099-966d76897be0",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KT",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "fd50fd65-7ad2-4bb6-ace3-90c53264d989",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KU",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "1111cf8f-e13a-4e57-8024-da9ecab6de6f",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KE",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "90ca4756-07bd-43ee-9845-76124d83c2da",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "LI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "3288112b-4a0c-48d1-82d6-f924380c9fb3",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "HI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "790f2c40-d7ba-4ea1-a132-ad200899cf8e",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "TE",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "d7ddc1bf-8b92-41ed-af91-24046a1798b9",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "B1",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false,
               |            "lisatieto": "SV"
               |          },
               |          {
               |            "id": "4aa69a90-f6f0-4026-a47c-34191b685522",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "LI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": true,
               |            "jarjestys": 0
               |          },
               |          {
               |            "id": "6f39602c-d798-4736-bb21-3e760bbb4003",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "GE",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "d89cc577-1ff7-46f8-81d1-ecded46e1a5d",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "AI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false,
               |            "lisatieto": "FI"
               |          },
               |          {
               |            "id": "b5bd8eb2-0f82-481a-aa32-dd67a0539edc",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "FY",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "43d3749a-0e5d-440f-82e4-405df6db4fec",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KS",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "79a060b4-a198-4d25-859d-c4ae3c3b8123",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "YH",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "812a775a-ac32-419a-b112-738a18dfa94d",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "A1",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false,
               |            "lisatieto": "EN"
               |          },
               |          {
               |            "id": "0040c038-2188-420c-84ab-c5088699ac14",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "BI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "fe5e023f-afe9-443d-a049-1a717d21a057",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "MA",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "8d54df2a-7c94-4f26-adce-b1818ccb6155",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KO",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": true,
               |            "jarjestys": 0
               |          },
               |          {
               |            "id": "da191d93-b4be-4dff-bc65-4084ed67ac94",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KO",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "a4e6d070-8cb1-4655-9623-9d117277444b",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "MU",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "95516b90-651b-4273-af9d-9f53e29f1b70",
               |            "suoritus": "4f102fed-5d5a-47ee-a72b-25741bb3f4ae",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KO",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": true,
               |            "jarjestys": 1
               |          }
               |        ]
               |      }
               |    ],
               |    "opiskeluoikeudet": []
               |  },
               |  {
               |    "id": "1.2.246.562.24.89282522615",
               |    "oppijanumero": "1.2.246.562.24.89282522615",
               |    "opiskelu": [],
               |    "suoritukset": [
               |      {
               |        "suoritus": {
               |          "henkiloOid": "1.2.246.562.24.89282522615",
               |          "source": "1.2.246.562.24.85360401416",
               |          "vahvistettu": true,
               |          "komo": "1.2.246.562.13.62959769647",
               |          "myontaja": "1.2.246.562.10.75930879863",
               |          "tila": "VALMIS",
               |          "valmistuminen": "04.06.2016",
               |          "yksilollistaminen": "Ei",
               |          "suoritusKieli": "FI",
               |          "id": "888f32cf-d1bb-41ac-96af-86292d91fdf3"
               |        },
               |        "arvosanat": [
               |          {
               |            "id": "9ec75809-954c-4beb-9fde-94e2c539bd5d",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "YH",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "46024c5b-f3c8-4d17-8015-4fb603f1b799",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "TE",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "08161ac9-faea-4ab2-922f-b90c4640950e",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "MA",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "8aec06e8-c6cc-45cb-913f-a60178f854c0",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "A1",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false,
               |            "lisatieto": "EN"
               |          },
               |          {
               |            "id": "e163d9c3-47d2-49cf-a4de-6b343e97f7a5",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "BI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "2bdcc23b-59cc-456c-b715-20170a7b323f",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "MU",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "fe0e6b26-6441-433a-a984-80810bbfabae",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "HI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "d6247615-b08f-45b0-860a-6c7bbb8b05ad",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KS",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "dcba6be3-cb3f-405f-a73a-d40dd22e45bf",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "B1",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false,
               |            "lisatieto": "SV"
               |          },
               |          {
               |            "id": "1a531209-eb39-4d4b-9544-151d04c16376",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KU",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "901444a9-ec4f-4711-bfe8-42c0e72b03c6",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KE",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "01fe61b0-5cdd-4428-b337-905a15e1a386",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KS",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": true,
               |            "jarjestys": 0
               |          },
               |          {
               |            "id": "65d99e3e-9eee-4307-8b04-b92491453f16",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KO",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": true,
               |            "jarjestys": 0
               |          },
               |          {
               |            "id": "4a45eb4e-a25f-4773-9d47-4900695ad7d5",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "FY",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "e82eeed5-c86d-4756-9f5c-2985b721e431",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KT",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "ecb8aa70-a38e-4022-b258-0e0e8bd39a42",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "9",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "LI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "4fb718bc-40ca-4566-bdf0-e3525392e6d7",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KU",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": true,
               |            "jarjestys": 0
               |          },
               |          {
               |            "id": "c80a84e6-35cd-4c8b-9371-df10bb0ad91f",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "8",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "KO",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "03ac122b-9558-414a-aef9-f9532ec12f42",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "GE",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false
               |          },
               |          {
               |            "id": "8d51e5ab-3c05-4c85-90ef-42fc9fc82eef",
               |            "suoritus": "888f32cf-d1bb-41ac-96af-86292d91fdf3",
               |            "arvio": {
               |              "arvosana": "10",
               |              "asteikko": "4-10"
               |            },
               |            "aine": "AI",
               |            "source": "1.2.246.562.24.85360401416",
               |            "lahdeArvot": {},
               |            "valinnainen": false,
               |            "lisatieto": "FI"
               |          }
               |        ]
               |      }
               |    ],
               |    "opiskeluoikeudet": []
               |  }
               |]""".stripMargin
}
