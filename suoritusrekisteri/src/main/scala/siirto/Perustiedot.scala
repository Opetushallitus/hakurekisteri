package siirto

import fi.vm.sade.hakurekisteri.tools.SafeXML

import scala.xml.Elem

object Perustiedot extends SchemaDefinition {
  val schemaLocation = "perustiedot.xsd"
  val schema = <xs:schema attributeFormDefault="unqualified"
                          elementFormDefault="qualified"
                          xmlns:xs="http://www.w3.org/2001/XMLSchema"
                          xmlns:koodisto="http://service.koodisto.sade.vm.fi/types/koodisto">
    <xs:import schemaLocation="perustiedot-koodisto.xsd" namespace="http://service.koodisto.sade.vm.fi/types/koodisto"/>

    <xs:element name="perustiedot" type="perustiedotType"/>

    <xs:simpleType name="hetuType">
      <xs:restriction base="xs:string">
        <xs:pattern value="[0-9]{6}[-A][0-9]{3}[0123456789ABCDEFHJKLMNPRSTUVWXY]"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="oppijaNumeroType">
      <xs:restriction base="xs:string">
        <xs:pattern value="1\.2\.246\.562\.24\.[0-9]{11}"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:complexType name="henkiloType">
      <xs:sequence>
        <xs:choice>
          <xs:element type="hetuType" name="hetu"/>
          <xs:element type="oppijaNumeroType" name="oppijanumero"/>
          <xs:sequence>
            <xs:element type="xs:string" name="henkiloTunniste"/>
            <xs:element type="xs:date" name="syntymaAika"/>
            <xs:element type="koodisto:sukupuoli" name="sukupuoli"/>
          </xs:sequence>
        </xs:choice>
        <xs:element type="koodisto:oppilaitosnumero" name="lahtokoulu" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="luokka" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="sukunimi" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="etunimet" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="kutsumanimi" maxOccurs="1" minOccurs="1"/>
        <xs:element type="koodisto:kunta" name="kotikunta" maxOccurs="1" minOccurs="1"/>
        <xs:element type="koodisto:kieli" name="aidinkieli" maxOccurs="1" minOccurs="1"/>
        <xs:element type="koodisto:maatjavaltiot2" name="kansalaisuus" maxOccurs="1" minOccurs="0"/>
        <xs:element type="xs:string" name="lahiosoite" maxOccurs="1" minOccurs="0"/>
        <xs:element type="koodisto:posti" name="postinumero" maxOccurs="1" minOccurs="0"/>
        <xs:element type="koodisto:maatjavaltiot2" name="maa" maxOccurs="1" minOccurs="0"/>
        <xs:element type="xs:string" name="matkapuhelin" maxOccurs="1" minOccurs="0"/>
        <xs:element type="xs:string" name="muuPuhelin" maxOccurs="1" minOccurs="0"/>
        <xs:choice>
          <xs:group ref="PerusopetuksenKaynyt"/>
          <xs:group ref="UlkomainenKorvaava"/>
          <xs:element name="lukio" type="SuoritusType" minOccurs="1" maxOccurs="1"/>
          <xs:element name="ammatillinen" type="SuoritusType" minOccurs="1" maxOccurs="1"/>
        </xs:choice>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="perustiedotType">
      <xs:all>
        <xs:element type="xs:string" name="eranTunniste" maxOccurs="1" minOccurs="1"/>
        <xs:element type="henkilotType" name="henkilot" maxOccurs="1" minOccurs="1">
          <xs:unique name="uniqueHetu">
            <xs:selector xpath="henkilo"/>
            <xs:field xpath="hetu"/>
          </xs:unique>
          <xs:unique name="uniqueOid">
            <xs:selector xpath="henkilo"/>
            <xs:field xpath="oppijanumero"/>
          </xs:unique>
          <xs:unique name="uniqueTunniste">
            <xs:selector xpath="henkilo"/>
            <xs:field xpath="henkiloTunniste"/>
          </xs:unique>
        </xs:element>
      </xs:all>
    </xs:complexType>
    <xs:complexType name="henkilotType">
      <xs:sequence>
        <xs:element type="henkiloType" name="henkilo" maxOccurs="unbounded" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="SuoritusType">
      <xs:sequence>
        <xs:element name="valmistuminen" type="xs:date" maxOccurs="1" minOccurs="1"/>
        <xs:element name="myontaja" type="koodisto:oppilaitosnumero" maxOccurs="1" minOccurs="1"/>
        <xs:element name="suorituskieli" type="koodisto:kieli" maxOccurs="1" minOccurs="1"/>
        <xs:element name="tila" type="koodisto:suorituksentila" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="PerusopetusType">
      <xs:complexContent>
        <xs:extension base="SuoritusType">
          <xs:sequence>
            <xs:element name="yksilollistaminen" type="koodisto:yksilollistaminen" minOccurs="1" maxOccurs="1"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:group name="PerusopetuksenKaynyt">
      <xs:sequence>
        <xs:element name="perusopetus" type="PerusopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="perusopetuksenlisaopetus" type="PerusopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:group ref="LisapisteKoulutukset"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="LisapisteKoulutukset">
      <xs:sequence>
        <xs:element name="ammattistartti" type="SuoritusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valmentava" type="SuoritusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="SuoritusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="SuoritusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="UlkomainenKorvaava">
      <xs:sequence>
        <xs:element name="ulkomainen" type="SuoritusType" maxOccurs="1" minOccurs="1"/>
        <xs:group ref="LisapisteKoulutukset"/>
      </xs:sequence>
    </xs:group>

  </xs:schema>

}

object PerustiedotV2 extends SchemaDefinition {
  val schemaLocation = "perustiedot.xsd"
  val schema = <xs:schema attributeFormDefault="unqualified"
                          elementFormDefault="qualified"
                          xmlns:xs="http://www.w3.org/2001/XMLSchema"
                          xmlns:koodisto="http://service.koodisto.sade.vm.fi/types/koodisto">
    <xs:import schemaLocation="perustiedot-koodisto.xsd" namespace="http://service.koodisto.sade.vm.fi/types/koodisto"/>

    <xs:element name="perustiedot" type="perustiedotType"/>

    <xs:simpleType name="hetuType">
      <xs:restriction base="xs:string">
        <xs:pattern value="[0-9]{6}[-A][0-9]{3}[0123456789ABCDEFHJKLMNPRSTUVWXY]"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="oppijaNumeroType">
      <xs:restriction base="xs:string">
        <xs:pattern value="1\.2\.246\.562\.24\.[0-9]{11}"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:complexType name="henkiloType">
      <xs:sequence>
        <xs:choice>
          <xs:element type="hetuType" name="hetu"/>
          <xs:element type="oppijaNumeroType" name="oppijanumero"/>
          <xs:sequence>
            <xs:element type="xs:string" name="henkiloTunniste"/>
            <xs:element type="xs:date" name="syntymaAika"/>
            <xs:element type="koodisto:sukupuoli" name="sukupuoli"/>
          </xs:sequence>
        </xs:choice>
        <xs:element type="koodisto:oppilaitosnumero" name="lahtokoulu" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="luokka" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="sukunimi" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="etunimet" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="kutsumanimi" maxOccurs="1" minOccurs="1"/>
        <xs:element type="koodisto:kunta" name="kotikunta" maxOccurs="1" minOccurs="1"/>
        <xs:element type="koodisto:kieli" name="aidinkieli" maxOccurs="1" minOccurs="1"/>
        <xs:element type="koodisto:maatjavaltiot2" name="kansalaisuus" maxOccurs="1" minOccurs="0"/>
        <xs:element type="xs:string" name="lahiosoite" maxOccurs="1" minOccurs="0"/>
        <xs:element type="koodisto:posti" name="postinumero" maxOccurs="1" minOccurs="0"/>
        <xs:element type="koodisto:maatjavaltiot2" name="maa" maxOccurs="1" minOccurs="0"/>
        <xs:element type="xs:string" name="matkapuhelin" maxOccurs="1" minOccurs="0"/>
        <xs:element type="xs:string" name="muuPuhelin" maxOccurs="1" minOccurs="0"/>
        <xs:choice>
          <xs:group ref="PerusopetuksenKaynyt"/>
          <xs:group ref="UlkomainenKorvaava"/>
          <xs:element name="lukio" type="SuoritusType" minOccurs="1" maxOccurs="1"/>
          <xs:element name="ammatillinen" type="SuoritusType" minOccurs="1" maxOccurs="1"/>
        </xs:choice>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="perustiedotType">
      <xs:all>
        <xs:element type="xs:string" name="eranTunniste" maxOccurs="1" minOccurs="1"/>
        <xs:element type="henkilotType" name="henkilot" maxOccurs="1" minOccurs="1">
          <xs:unique name="uniqueHetu">
            <xs:selector xpath="henkilo"/>
            <xs:field xpath="hetu"/>
          </xs:unique>
          <xs:unique name="uniqueOid">
            <xs:selector xpath="henkilo"/>
            <xs:field xpath="oppijanumero"/>
          </xs:unique>
          <xs:unique name="uniqueTunniste">
            <xs:selector xpath="henkilo"/>
            <xs:field xpath="henkiloTunniste"/>
          </xs:unique>
        </xs:element>
      </xs:all>
    </xs:complexType>
    <xs:complexType name="henkilotType">
      <xs:sequence>
        <xs:element type="henkiloType" name="henkilo" maxOccurs="unbounded" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="SuoritusType">
      <xs:sequence>
        <xs:element name="valmistuminen" type="xs:date" maxOccurs="1" minOccurs="1"/>
        <xs:element name="myontaja" type="koodisto:oppilaitosnumero" maxOccurs="1" minOccurs="1"/>
        <xs:element name="suorituskieli" type="koodisto:kieli" maxOccurs="1" minOccurs="1"/>
        <xs:element name="tila" type="koodisto:suorituksentila" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="PerusopetusType">
      <xs:complexContent>
        <xs:extension base="SuoritusType">
          <xs:sequence>
            <xs:element name="yksilollistaminen" type="koodisto:yksilollistaminen" minOccurs="1" maxOccurs="1"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:group name="PerusopetuksenKaynyt">
      <xs:sequence>
        <xs:element name="perusopetus" type="PerusopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="perusopetuksenlisaopetus" type="PerusopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:group ref="LisapisteKoulutukset"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="LisapisteKoulutukset">
      <xs:sequence>
        <xs:element name="ammattistartti" type="SuoritusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valmentava" type="SuoritusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="SuoritusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="SuoritusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valma" type="SuoritusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="telma" type="SuoritusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="UlkomainenKorvaava">
      <xs:sequence>
        <xs:element name="ulkomainen" type="SuoritusType" maxOccurs="1" minOccurs="1"/>
        <xs:group ref="LisapisteKoulutukset"/>
      </xs:sequence>
    </xs:group>

  </xs:schema>

}

object PerustiedotKoodisto
    extends IncludeSchema(
      "http://service.koodisto.sade.vm.fi/types/koodisto",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/kunta.xsd",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/oppilaitosnumero.xsd",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/kieli.xsd",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/maatjavaltiot2.xsd",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/posti.xsd",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/sukupuoli.xsd",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/yksilollistaminen.xsd",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/suorituksentila.xsd"
    ) {
  override val schemaLocation: String = "perustiedot-koodisto.xsd"
}

abstract class IncludeSchema(namespace: String, remoteschemas: String*) extends SchemaWithRemotes {
  lazy val schema =
    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                               targetNamespace={namespace}
                               xmlns={namespace}
                               elementFormDefault="qualified">
      {includes}
    </xs:schema>

  def includes: Seq[Elem] = for (remote <- remoteschemas) yield <xs:include schemaLocation={
    remote
  }/>
}

trait RemoteSchema extends SchemaDefinition {
  lazy val schema: Elem = SafeXML.load(schemaLocation)
}

trait SchemaWithRemotes extends SchemaDefinition {
  lazy val remotes: Seq[SchemaDefinition] = (schema \\ "include" \\ "@schemaLocation").map((sl) =>
    new RemoteSchema {
      override val schemaLocation: String = sl.text
    }
  )
}

trait SchemaDefinition {
  val schemaLocation: String
  val schema: Elem
}
