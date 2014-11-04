package siirto

import org.scalatest.{Matchers, FlatSpec}
import org.xml.sax.InputSource
import scala.xml.Elem
import java.io.StringReader
import scala.xml.Source._


class ValidXmlSpec extends FlatSpec with Matchers {

  implicit def elemToInputSource(elem:Elem): InputSource = {
    fromReader(new StringReader(elem.toString))
  }

  behavior of "Xml Validation"


  val validator = new ValidXml(Perustiedot, PerustiedotKoodisto)


  it should "find invalid xml" in {
    validator.load(<perustiedot></perustiedot>).isFailure should be (true)
  }

  it should "parse valid Xml" in {
    validator.load(valid).isSuccess should be (true)
  }

  it should "disregard conflicting schema in root element" in {
    val path = getClass.getClassLoader.getResource("simple.xml")
    validator.load(path).isSuccess should be (false)

  }


  val valid =
    <perustiedot>
      <eranTunniste>eranTunniste</eranTunniste>
      <henkilot>
        <henkilo>
          <hetu>131094-919B</hetu>
          <lahtokoulu>05127</lahtokoulu>
          <luokka>9A</luokka>
          <sukunimi>Testinen</sukunimi>
          <etunimet>Juha Jaakko</etunimet>
          <kutsumanimi>Jaakko</kutsumanimi>
          <kotikunta>020</kotikunta>
          <aidinkieli>FI</aidinkieli>
          <kansalaisuus>246</kansalaisuus>
          <lahiosoite>Katu 1 A 1</lahiosoite>
          <postinumero>00100</postinumero>
          <matkapuhelin>040 1234 567</matkapuhelin>
          <muuPuhelin>09 1234 567</muuPuhelin>
          <perusopetus>
            <valmistuminen>2015-06-04</valmistuminen>
            <myontaja>05127</myontaja>
            <suorituskieli>FI</suorituskieli>
            <tila>KESKEN</tila>
            <yksilollistaminen>ALUEITTAIN</yksilollistaminen>
          </perusopetus>
        </henkilo>
        <henkilo>
          <henkiloTunniste>TUNNISTE</henkiloTunniste>
          <syntymaAika>1999-03-29</syntymaAika>
          <sukupuoli>1</sukupuoli>
          <lahtokoulu>05127</lahtokoulu>
          <luokka>9A</luokka>
          <sukunimi>Testinen</sukunimi>
          <etunimet>Juha Jaakko</etunimet>
          <kutsumanimi>Jaakko</kutsumanimi>
          <kotikunta>020</kotikunta>
          <aidinkieli>FI</aidinkieli>
          <kansalaisuus>246</kansalaisuus>
          <lahiosoite>Katu 1 A 1</lahiosoite>
          <postinumero>00100</postinumero>
          <matkapuhelin>040 1234 567</matkapuhelin>
          <muuPuhelin>09 1234 567</muuPuhelin>
          <ulkomainen>

            <valmistuminen>2014-06-04</valmistuminen>
            <myontaja>05127</myontaja>
            <suorituskieli>FI</suorituskieli>
            <tila>KESKEN</tila>

          </ulkomainen>
          <maahanmuuttajienammvalmistava>
            <valmistuminen>2015-06-04</valmistuminen>
            <myontaja>05127</myontaja>
            <suorituskieli>FI</suorituskieli>
            <tila>VALMIS</tila>

          </maahanmuuttajienammvalmistava>

        </henkilo>

        <henkilo>
          <oppijanumero>1.2.246.562.24.12345678901</oppijanumero>
          <lahtokoulu>05127</lahtokoulu>
          <luokka>9A</luokka>
          <sukunimi>Testinen</sukunimi>
          <etunimet>Juha Jaakko</etunimet>
          <kutsumanimi>Jaakko</kutsumanimi>
          <kotikunta>020</kotikunta>
          <aidinkieli>FI</aidinkieli>
          <kansalaisuus>246</kansalaisuus>
          <lahiosoite>Katu 1 A 1</lahiosoite>
          <postinumero>00100</postinumero>
          <matkapuhelin>040 1234 567</matkapuhelin>
          <muuPuhelin>09 1234 567</muuPuhelin>
          <perusopetus>
            <valmistuminen>2014-06-04</valmistuminen>
            <myontaja>05127</myontaja>
            <suorituskieli>FI</suorituskieli>
            <tila>VALMIS</tila>
          </perusopetus>
          <perusopetuksenlisaopetus>
            <valmistuminen>2015-06-04</valmistuminen>
            <myontaja>05127</myontaja>
            <suorituskieli>FI</suorituskieli>
            <tila>KESKEN</tila>

          </perusopetuksenlisaopetus>
        </henkilo>
      </henkilot>
    </perustiedot>
}


