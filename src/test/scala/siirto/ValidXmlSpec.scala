package siirto

import org.scalatest.{Matchers, FlatSpec}
import org.xml.sax.InputSource
import scala.xml.Elem
import java.io.StringReader
import scala.xml.Source._

import scala.language.implicitConversions


class ValidXmlSpec extends FlatSpec with Matchers {

  implicit def elemToInputSource(elem:Elem): InputSource = {
    fromReader(new StringReader(elem.toString))
  }

  behavior of "Xml Validation"

  val testiKoodisto =  Seq(Kunnat, Oppilaitokset, Kielet, MaatJaValtiot, Posti, Sukupuoli, YksilollistysKoodisto, SuorituksenTila)

  val validator = new ValidXml(Perustiedot,  (PerustiedotKoodisto +: testiKoodisto):_*)


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
            <yksilollistaminen>EI</yksilollistaminen>
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
            <yksilollistaminen>EI</yksilollistaminen>
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



object Kunnat extends SchemaDefinition {
  override val schema: Elem =
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
               targetNamespace="http://service.koodisto.sade.vm.fi/types/koodisto"
               xmlns="http://service.koodisto.sade.vm.fi/types/koodisto"
               elementFormDefault="qualified">

    <xs:simpleType name="kunta">
      <xs:restriction base="xs:string">
        <xs:enumeration value="020">
          <xs:annotation>
            <xs:documentation xml:lang="sv">Akaa</xs:documentation>
            <xs:documentation xml:lang="fi">Akaa</xs:documentation>
          </xs:annotation>
        </xs:enumeration>
      </xs:restriction>
    </xs:simpleType>
  </xs:schema>

  override val schemaLocation: String = "https://virkailija.opintopolku.fi/koodisto-service/rest/kunta.xsd"
}

object Oppilaitokset extends SchemaDefinition {
  override val schemaLocation: String = "https://virkailija.opintopolku.fi/koodisto-service/rest/oppilaitosnumero.xsd"
  override val schema: Elem =
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                                         targetNamespace="http://service.koodisto.sade.vm.fi/types/koodisto"
                                         xmlns="http://service.koodisto.sade.vm.fi/types/koodisto"
                                         elementFormDefault="qualified">

    <xs:simpleType name="oppilaitosnumero">
      <xs:restriction base="xs:string">
        <xs:enumeration value="05127">
          <xs:annotation>
            <xs:documentation xml:lang="fi">Pikkolan koulu</xs:documentation>
            <xs:documentation xml:lang="sv">Pikkolan koulu</xs:documentation>
          </xs:annotation>
        </xs:enumeration>
      </xs:restriction>
    </xs:simpleType>

  </xs:schema>

        }

object Kielet extends SchemaDefinition {
  override val schemaLocation: String = "https://virkailija.opintopolku.fi/koodisto-service/rest/kieli.xsd"

  override val schema: Elem =
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
               targetNamespace="http://service.koodisto.sade.vm.fi/types/koodisto"
               xmlns="http://service.koodisto.sade.vm.fi/types/koodisto"
               elementFormDefault="qualified">

      <xs:simpleType name="kieli">
        <xs:restriction base="xs:string">
          <xs:enumeration value="FI">
            <xs:annotation>
              <xs:documentation xml:lang="en">Finnish</xs:documentation>
              <xs:documentation xml:lang="fi">suomi</xs:documentation>
              <xs:documentation xml:lang="sv">finska</xs:documentation>
            </xs:annotation>
          </xs:enumeration>

        </xs:restriction>
      </xs:simpleType>

    </xs:schema>

}

object MaatJaValtiot extends SchemaDefinition {
  override val schemaLocation: String ="https://virkailija.opintopolku.fi/koodisto-service/rest/maatjavaltiot2.xsd"

  override val schema: Elem =
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
               targetNamespace="http://service.koodisto.sade.vm.fi/types/koodisto"
               xmlns="http://service.koodisto.sade.vm.fi/types/koodisto"
               elementFormDefault="qualified">

      <xs:simpleType name="maatjavaltiot2">
        <xs:restriction base="xs:string">
          <xs:enumeration value="246">
            <xs:annotation>
              <xs:documentation xml:lang="fi">Suomi</xs:documentation>
              <xs:documentation xml:lang="en">Finland</xs:documentation>
              <xs:documentation xml:lang="sv">Finland</xs:documentation>
            </xs:annotation>
          </xs:enumeration>

        </xs:restriction>
      </xs:simpleType>

    </xs:schema>

}

object Posti extends SchemaDefinition {
  override val schemaLocation: String = "https://virkailija.opintopolku.fi/koodisto-service/rest/posti.xsd"

  override val schema: Elem =
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
               targetNamespace="http://service.koodisto.sade.vm.fi/types/koodisto"
               xmlns="http://service.koodisto.sade.vm.fi/types/koodisto"
               elementFormDefault="qualified">

      <xs:simpleType name="posti">
        <xs:restriction base="xs:string">
          <xs:enumeration value="00100">
            <xs:annotation>
              <xs:documentation xml:lang="fi">HELSINKI</xs:documentation>
              <xs:documentation xml:lang="sv">HELSINGFORS</xs:documentation>
            </xs:annotation>
          </xs:enumeration>

        </xs:restriction>
      </xs:simpleType>

    </xs:schema>
}

object Sukupuoli extends SchemaDefinition {
  override val schemaLocation: String = "https://virkailija.opintopolku.fi/koodisto-service/rest/sukupuoli.xsd"

  override val schema: Elem =
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
               targetNamespace="http://service.koodisto.sade.vm.fi/types/koodisto"
               xmlns="http://service.koodisto.sade.vm.fi/types/koodisto"
               elementFormDefault="qualified">

      <xs:simpleType name="sukupuoli">
        <xs:restriction base="xs:string">
          <xs:enumeration value="2">
            <xs:annotation>
              <xs:documentation xml:lang="fi">nainen</xs:documentation>
              <xs:documentation xml:lang="sv">kvinna</xs:documentation>
              <xs:documentation xml:lang="en">woman</xs:documentation>
            </xs:annotation>
          </xs:enumeration>

          <xs:enumeration value="1">
            <xs:annotation>
              <xs:documentation xml:lang="en">man</xs:documentation>
              <xs:documentation xml:lang="sv">man</xs:documentation>
              <xs:documentation xml:lang="fi">mies</xs:documentation>
            </xs:annotation>
          </xs:enumeration>

        </xs:restriction>
      </xs:simpleType>


    </xs:schema>



}

object YksilollistysKoodisto extends SchemaDefinition {
  override val schemaLocation: String ="https://virkailija.opintopolku.fi/koodisto-service/rest/yksilollistaminen.xsd"

  override val schema: Elem =
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
               targetNamespace="http://service.koodisto.sade.vm.fi/types/koodisto"
               xmlns="http://service.koodisto.sade.vm.fi/types/koodisto"
               elementFormDefault="qualified">

      <xs:simpleType name="yksilollistaminen">
        <xs:restriction base="xs:string">
          <xs:enumeration value="EI">

          </xs:enumeration>

          <xs:enumeration value="OSITTAIN">
          </xs:enumeration>

          <xs:enumeration value="ALUEITTAIN">
          </xs:enumeration>

          <xs:enumeration value="KOKONAAN">
          </xs:enumeration>

        </xs:restriction>
      </xs:simpleType>

    </xs:schema>
}

object SuorituksenTila extends  SchemaDefinition {
  override val schemaLocation: String ="https://virkailija.opintopolku.fi/koodisto-service/rest/suorituksentila.xsd"

  override val schema: Elem =
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
               targetNamespace="http://service.koodisto.sade.vm.fi/types/koodisto"
               xmlns="http://service.koodisto.sade.vm.fi/types/koodisto"
               elementFormDefault="qualified">

      <xs:simpleType name="suorituksentila">
        <xs:restriction base="xs:string">
          <xs:enumeration value="KESKEN">
          </xs:enumeration>

          <xs:enumeration value="KESKEYTYNYT">
          </xs:enumeration>

          <xs:enumeration value="VALMIS">
          </xs:enumeration>

        </xs:restriction>
      </xs:simpleType>

    </xs:schema>
}
}


