package fi.vm.sade.hakurekisteri.acceptance

import fi.vm.sade.hakurekisteri.acceptance.tools.HakurekisteriContainer
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.tools.Peruskoulu
import org.joda.time.{DateTime, LocalDate}
import org.junit.runner.RunWith
import org.scalatest.GivenWhenThen
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TallennaSuoritusSpec extends HakurekisteriContainer with GivenWhenThen {

  info(
    "Koulun virkailijana tallennan kouluni oppilaiden tutkintosuoritukset jotta niitä voi hyödyntää haussa ja valinnassa"
  )

  Feature("Suorituksen tallentaminen") {
    Scenario("Esitäytetyn lomakkeen lähettäminen tuottaa suorituksen") {
      Given("Koulu lähettää Mikon ja Matin esitäytetyt kaavakkeet")
      koulu lahettaa
        <ROWSET>
          <ROW>
            <VUOSI>2014</VUOSI>
            <KAUSI>S</KAUSI>
            <LAHTOKOULU>{koulu.koodi}</LAHTOKOULU>
            <POHJAKOULUTUS>1</POHJAKOULUTUS>
            <OPETUSKIELI>FI</OPETUSKIELI>
            <LUOKKA>9A</LUOKKA>
            <LUOKKATASO>9</LUOKKATASO>
            <HETU>{Mikko.hetu}</HETU>
            <SUKUPUOLI>1</SUKUPUOLI>
            <SUKUNIMI>Möttönen</SUKUNIMI>
            <ETUNIMET>Mikko Valtteri</ETUNIMET>
            <KUTSUMANIMI>Mikko</KUTSUMANIMI>
            <KOTIKUNTA>240</KOTIKUNTA>
            <AIDINKIELI>FI</AIDINKIELI>
            <KANSALAISUUS>246</KANSALAISUUS>
            <LAHIOSOITE>Kaduntie 156</LAHIOSOITE>
            <POSTINUMERO>20520</POSTINUMERO>
            <MAA>246</MAA>
            <MATKAPUHELIN>047 1234567</MATKAPUHELIN>
            <MUUPUHELIN>5278091</MUUPUHELIN>
            <ERA>PKERA1_2014S_{koulu.koodi}</ERA>
          </ROW>
          <ROW>
            <VUOSI>2014</VUOSI>
            <KAUSI>S</KAUSI>
            <LAHTOKOULU>{koulu.koodi}</LAHTOKOULU>
            <POHJAKOULUTUS>1</POHJAKOULUTUS>
            <OPETUSKIELI>FI</OPETUSKIELI>
            <LUOKKA>9A</LUOKKA>
            <LUOKKATASO>9</LUOKKATASO>
            <HETU>{Matti.hetu}</HETU>
            <SUKUPUOLI>1</SUKUPUOLI>
            <SUKUNIMI>Virtanen</SUKUNIMI>
            <ETUNIMET>Matti Petteri</ETUNIMET>
            <KUTSUMANIMI>Matti</KUTSUMANIMI>
            <KOTIKUNTA>240</KOTIKUNTA>
            <AIDINKIELI>FI</AIDINKIELI>
            <KANSALAISUUS>246</KANSALAISUUS>
            <LAHIOSOITE>Kaduntie 158</LAHIOSOITE>
            <POSTINUMERO>20520</POSTINUMERO>
            <MAA>246</MAA>
            <MATKAPUHELIN>047 2345678</MATKAPUHELIN>
            <MUUPUHELIN>5278091</MUUPUHELIN>
            <ERA>PKERA1_2014S_{koulu.koodi}</ERA>
          </ROW>
        </ROWSET>

      When("Haetaan koulun suorituksia")
      val haetut =
        hae(
          suoritukset
            koululle koulu.id
        )

      Then("Molemmille löytyvät peruskoulun keskeneräiset suoritukset arvioidulla valmistumisella")
      haetut should contain(
        Peruskoulu(koulu.id, "KESKEN", s"04.06.${new LocalDate().year().get}", Mikko.oid)
          .asInstanceOf[Suoritus]
      )
      haetut should contain(
        Peruskoulu(koulu.id, "KESKEN", s"04.06.${new LocalDate().year().get}", Matti.oid)
          .asInstanceOf[Suoritus]
      )

    }

    Scenario("Esitäytetyn lomakkeen lähettäminen tuottaa opiskelijatiedon") {
      Given("Koulu lähettää Mikon ja Matin esitäytetyt kaavaakkeet")
      koulu lahettaa
        <ROWSET>
          <ROW>
            <VUOSI>2014</VUOSI>
            <KAUSI>S</KAUSI>
            <LAHTOKOULU>{koulu.koodi}</LAHTOKOULU>
            <POHJAKOULUTUS>1</POHJAKOULUTUS>
            <OPETUSKIELI>FI</OPETUSKIELI>
            <LUOKKA>9A</LUOKKA>
            <LUOKKATASO>9</LUOKKATASO>
            <HETU>{Mikko.hetu}</HETU>
            <SUKUPUOLI>1</SUKUPUOLI>
            <SUKUNIMI>Möttönen</SUKUNIMI>
            <ETUNIMET>Mikko Valtteri</ETUNIMET>
            <KUTSUMANIMI>Mikko</KUTSUMANIMI>
            <KOTIKUNTA>240</KOTIKUNTA>
            <AIDINKIELI>FI</AIDINKIELI>
            <KANSALAISUUS>246</KANSALAISUUS>
            <LAHIOSOITE>Kaduntie 156</LAHIOSOITE>
            <POSTINUMERO>20520</POSTINUMERO>
            <MAA>246</MAA>
            <MATKAPUHELIN>047 1234567</MATKAPUHELIN>
            <MUUPUHELIN>5278091</MUUPUHELIN>
            <ERA>PKERA1_2014S_{koulu.koodi}</ERA>
          </ROW>
          <ROW>
            <VUOSI>2014</VUOSI>
            <KAUSI>S</KAUSI>
            <LAHTOKOULU>{koulu.koodi}</LAHTOKOULU>
            <POHJAKOULUTUS>1</POHJAKOULUTUS>
            <OPETUSKIELI>FI</OPETUSKIELI>
            <LUOKKA>9A</LUOKKA>
            <LUOKKATASO>9</LUOKKATASO>
            <HETU>{Matti.hetu}</HETU>
            <SUKUPUOLI>1</SUKUPUOLI>
            <SUKUNIMI>Virtanen</SUKUNIMI>
            <ETUNIMET>Matti Petteri</ETUNIMET>
            <KUTSUMANIMI>Matti</KUTSUMANIMI>
            <KOTIKUNTA>240</KOTIKUNTA>
            <AIDINKIELI>FI</AIDINKIELI>
            <KANSALAISUUS>246</KANSALAISUUS>
            <LAHIOSOITE>Kaduntie 158</LAHIOSOITE>
            <POSTINUMERO>20520</POSTINUMERO>
            <MAA>246</MAA>
            <MATKAPUHELIN>047 2345678</MATKAPUHELIN>
            <MUUPUHELIN>5278091</MUUPUHELIN>
            <ERA>PKERA1_2014S_{koulu.koodi}</ERA>
          </ROW>
        </ROWSET>

      When("Haetaan koulun suorituksia")
      val haetut =
        hae(
          opiskelijat
            koululle koulu.id
        )

      Then("Molemmille löytyvät opiskelijatiedot")
      haetut should contain(
        Opiskelija(koulu.id, "9", "9A", Mikko.oid, "01.01.2014", None, source = "Test")
      )
      haetut should contain(
        Opiskelija(koulu.id, "9", "9A", Matti.oid, "01.01.2014", None, source = "Test")
      )

    }

    Scenario("Tallennetaan suoritus tyhjään kantaan") {
      Given("kanta on tyhjä")
      db is empty

      When("suoritus luodaan järjestelmään")

      create(suoritus)

      Then("löytyy kannasta ainoastaan tallennettu suoritus")
      // testidatassa ainoastaan peruskoulusuorituksia ja ilman kriteerejä ei voi hakea
      peruskouluSuoritukset should equal(Seq(suoritus))
    }

    Scenario("Tallennettaan kantaan jossa on tietoa") {
      Given("kannassa on suorituksia")
      db has (suoritus, suoritus2)

      When("uusi suoritus luodaan järjestelmään")

      create(suoritus3)

      Then("löytyy kannasta  tallennettu suoritus")
      peruskouluSuoritukset should contain(suoritus3.asInstanceOf[Suoritus])
    }

    Scenario("Vanhat tiedot säilyvät") {
      Given("kannassa on suorituksia")
      db has (suoritus, suoritus2)

      When("uusi suoritus luodaan järjestelmään")

      create(suoritus3)

      Then("löytyy kannasta  tallennettu suoritus")
      peruskouluSuoritukset should (contain(suoritus.asInstanceOf[Suoritus]) and contain(
        suoritus2.asInstanceOf[Suoritus]
      ))
    }

  }
}
