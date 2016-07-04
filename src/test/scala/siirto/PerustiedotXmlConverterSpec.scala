package siirto

import fi.vm.sade.hakurekisteri.rest.support.Workbook
import fi.vm.sade.hakurekisteri.tools.{ExcelTools, XmlEquality}
import org.scalatest.{FlatSpec, Matchers}

class PerustiedotXmlConverterSpec extends FlatSpec with Matchers with XmlEquality with ExcelTools {
   behavior of "PerustiedotXMLConverter"

  it should "convert a perustiedot row with hetu into valid xml" in {
    val wb = WorkbookData(
      "henkilotiedot" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|SYNTYMAAIKA|SUKUPUOLI|LAHTOKOULU|LUOKKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|KOTIKUNTA|AIDINKIELI|KANSALAISUUS|LAHIOSOITE|POSTINUMERO|MAA|MATKAPUHELIN|MUUPUHELIN
          |111111-1975|            |               |           |         |05127     |9A    |Testi   |Test A  |Test       |211      |FI        |246         |Katu 1    |00100      |246|0401234567  |
        """,
      "perusopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|VALMISTUMINEN|MYONTAJA|SUORITUSKIELI|TILA  |YKSILOLLISTAMINEN
          |111111-1975|            |               |1.6.2014     |05127   |FI           |VALMIS|EI
        """,
      "perusopetuksenlisaopetus" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|VALMISTUMINEN|MYONTAJA|SUORITUSKIELI|TILA  |YKSILOLLISTAMINEN
          |111111-1975|            |               |1.6.2015     |05127   |FI           |VALMIS|EI
        """,
      "valma" ->
        """
          |HETU       |OPPIJANUMERO|HENKILOTUNNISTE|VALMISTUMINEN|MYONTAJA|SUORITUSKIELI|TILA  |YKSILOLLISTAMINEN
          |111111-1975|            |               |1.6.2016     |05127   |FI           |KESKEN|EI
        """
    ).toExcel

    val valid = <perustiedot>
      <henkilo>
        <hetu>111111-1975</hetu>
        <lahtokoulu>05127</lahtokoulu>
        <luokka>9A</luokka>
        <sukunimi>Testi</sukunimi>
        <etunimet>Test A</etunimet>
        <kutsumanimi>Test</kutsumanimi>
        <kotikunta>211</kotikunta>
        <aidinkieli>FI</aidinkieli>
        <kansalaisuus>246</kansalaisuus>
        <lahiosoite>Katu 1</lahiosoite>
        <postinumero>00100</postinumero>
        <maa>246</maa>
        <matkapuhelin>0401234567</matkapuhelin>
        <perusopetus>
          <valmistuminen>2014-06-01</valmistuminen>
          <myontaja>05127</myontaja>
          <suorituskieli>FI</suorituskieli>
          <tila>VALMIS</tila>
          <yksilollistaminen>EI</yksilollistaminen>
        </perusopetus>
        <perusopetuksenlisaopetus>
          <valmistuminen>2015-06-01</valmistuminen>
          <myontaja>05127</myontaja>
          <suorituskieli>FI</suorituskieli>
          <tila>VALMIS</tila>
          <yksilollistaminen>EI</yksilollistaminen>
        </perusopetuksenlisaopetus>
        <valma>
          <valmistuminen>2016-06-01</valmistuminen>
          <myontaja>05127</myontaja>
          <suorituskieli>FI</suorituskieli>
          <tila>KESKEN</tila>
        </valma>
      </henkilo>
    </perustiedot>

    PerustiedotXmlConverter.converter.set(<perustiedot/>, Workbook(wb)) should equal (valid)(after being normalized)
  }

  it should "convert a perustiedot row with oppijanumero into valid xml" in {
    val wb = WorkbookData(
      "henkilotiedot" ->
        """
          |HETU|OPPIJANUMERO              |HENKILOTUNNISTE|SYNTYMAAIKA|SUKUPUOLI|LAHTOKOULU|LUOKKA|SUKUNIMI|ETUNIMET|KUTSUMANIMI|KOTIKUNTA|AIDINKIELI|KANSALAISUUS|LAHIOSOITE|POSTINUMERO|MAA|MATKAPUHELIN|MUUPUHELIN
          |    |1.2.246.562.24.00000000001|               |           |         |05127     |9A    |Testi   |Test A  |Test       |211      |FI        |246         |Katu 1    |00100      |246|0401234567  |
        """,
      "perusopetus" ->
        """
          |HETU|OPPIJANUMERO              |HENKILOTUNNISTE|VALMISTUMINEN|MYONTAJA|SUORITUSKIELI|TILA  |YKSILOLLISTAMINEN
          |    |1.2.246.562.24.00000000001|               |1.6.2015     |05127   |FI           |KESKEN|EI
        """
    ).toExcel

    val valid = <perustiedot>
      <henkilo>
        <oppijanumero>1.2.246.562.24.00000000001</oppijanumero>
        <lahtokoulu>05127</lahtokoulu>
        <luokka>9A</luokka>
        <sukunimi>Testi</sukunimi>
        <etunimet>Test A</etunimet>
        <kutsumanimi>Test</kutsumanimi>
        <kotikunta>211</kotikunta>
        <aidinkieli>FI</aidinkieli>
        <kansalaisuus>246</kansalaisuus>
        <lahiosoite>Katu 1</lahiosoite>
        <postinumero>00100</postinumero>
        <maa>246</maa>
        <matkapuhelin>0401234567</matkapuhelin>
        <perusopetus>
          <valmistuminen>2015-06-01</valmistuminen>
          <myontaja>05127</myontaja>
          <suorituskieli>FI</suorituskieli>
          <tila>KESKEN</tila>
          <yksilollistaminen>EI</yksilollistaminen>
        </perusopetus>
      </henkilo>
    </perustiedot>

    PerustiedotXmlConverter.converter.set(<perustiedot/>, Workbook(wb)) should equal (valid)(after being normalized)
  }
 }
