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
    read[Oppija](
      """{"id":"foo","oppijanumero":"foo","opiskelu":[],"suoritukset":[],"opiskeluoikeudet":[]}"""
    ) should be(Oppija("foo", Seq(), Seq(), Seq(), None))
  }

  it should "serialize Oppija" in {
    write(Oppija("foo", Seq(), Seq(), Seq(), None)) should be(
      """{"id":"foo","oppijanumero":"foo","opiskelu":[],"suoritukset":[],"opiskeluoikeudet":[]}"""
    )
  }

  it should "serialize 'full' Oppija" in {
    write(
      Oppija(
        "foo",
        Seq(
          Opiskelija("bar", "9", "9A", "foo", new DateTime(2015, 1, 1, 0, 0, 0, 0), None, "test")
        ),
        Seq(
          Todistus(
            VirallinenSuoritus(
              "koulutus_123456",
              "bar",
              "VALMIS",
              new LocalDate(2013, 1, 1),
              "foo",
              yksilollistaminen.Ei,
              "FI",
              None,
              vahv = true,
              "test"
            ),
            Seq(
              Arvosana(
                UUID.fromString("61ce569d-a56f-40df-91af-901152c4346e"),
                Arvio410("10"),
                "AI",
                Some("FI"),
                valinnainen = false,
                None,
                "test",
                Map()
              )
            )
          )
        ),
        Seq(
          Opiskeluoikeus(new LocalDate(2014, 1, 1), None, "foo", "koulutus_123456", "bar", "test")
        ),
        None
      )
    ) should be(
      """{"id":"foo","oppijanumero":"foo","opiskelu":[{"oppilaitosOid":"bar","luokkataso":"9","luokka":"9A","henkiloOid":"foo","alkuPaiva":"2014-12-31T22:00:00.000Z","source":"test"}],"suoritukset":[{"suoritus":{"henkiloOid":"foo","source":"test","vahvistettu":true,"komo":"koulutus_123456","myontaja":"bar","tila":"VALMIS","valmistuminen":"01.01.2013","yksilollistaminen":"Ei","suoritusKieli":"FI"},"arvosanat":[{"suoritus":"61ce569d-a56f-40df-91af-901152c4346e","arvio":{"arvosana":"10","asteikko":"4-10"},"aine":"AI","lisatieto":"FI","valinnainen":false,"source":"test","lahdeArvot":{}}]}],"opiskeluoikeudet":[{"aika":{"alku":"2014-01-01T00:00:00.000+02:00"},"henkiloOid":"foo","komo":"koulutus_123456","myontaja":"bar","source":"test"}]}"""
    )
  }

}
