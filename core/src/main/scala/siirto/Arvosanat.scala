package siirto

object Arvosanat extends SchemaDefinition {
  val schemaLocation = "arvosanat.xsd"
  val schema = <schema xmlns="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="http://www.example.org/arvosanat"
                       xmlns:arvosanat="http://www.example.org/arvosanat"
                       xmlns:koodisto="http://service.koodisto.sade.vm.fi/types/koodisto"
                       elementFormDefault="qualified">
    <xs:import schemaLocation="arvosanat-koodisto.xsd" namespace="http://service.koodisto.sade.vm.fi/types/koodisto"/>

    <element name="arvosanat" type="arvosanat:ArvosanatType">
      <unique name="uniqueHetu">
        <selector xpath="arvosanat:oppilas"/>
        <field xpath="arvosanat:hetu"/>
      </unique>
      <unique name="uniqueOid">
        <selector xpath="arvosanat:oppilas"/>
        <field xpath="arvosanat:oppilasnumero"/>
      </unique>
      <unique name="uniqueTunniste">
        <selector xpath="arvosanat:oppilas"/>
        <field xpath="arvosanat:tunniste"/>
      </unique>
    </element>

    <complexType name="ArvosanatType">
      <sequence>
        <element name="eranTunniste" minOccurs="1" maxOccurs="1"/>
        <element name="oppilas" type="arvosanat:OppilasType" maxOccurs="unbounded" minOccurs="1"/>
      </sequence>
    </complexType>

    <complexType name="OppilasType">
      <sequence>
        <choice maxOccurs="1" minOccurs="1">
          <element name="hetu" type="arvosanat:hetuType" maxOccurs="1" minOccurs="1"/>
          <element name="oppijanumero" type="arvosanat:oppijaNumeroType" maxOccurs="1" minOccurs="1"/>
          <group ref="arvosanat:SyntymaAjallinen"/>
        </choice>
        <element name="todistukset" type="arvosanat:TodistuksetType" maxOccurs="1" minOccurs="1"/>
      </sequence>
    </complexType>

    <complexType name="TodistuksetType">
      <choice>
        <group ref="arvosanat:PerusopetuksenKaynyt"/>
        <group ref="arvosanat:LukionKaynyt"/>
        <group ref="arvosanat:UlkomainenKorvaava"/>
        <group ref="arvosanat:AmmattikoulunKaynyt"/>
      </choice>
    </complexType>

    <complexType name="PerusopetusType">
      <complexContent>
        <extension base="arvosanat:SuoritusType">
          <choice>
            <sequence>
              <element name="AI" type="arvosanat:PerusOpetusAidinkieliType" maxOccurs="1" minOccurs="1"/>
              <element name="A1" type="arvosanat:PerusOpetusKieliType" maxOccurs="2" minOccurs="1"/>
              <choice>
                <sequence>
                  <element name="A2" type="arvosanat:PerusOpetusKieliType" maxOccurs="2" minOccurs="1"/>
                  <element name="B1" type="arvosanat:PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
                </sequence>
                <element name="B1" type="arvosanat:PerusOpetusKieliType" maxOccurs="1" minOccurs="1"/>
              </choice>
              <element name="B2" type="arvosanat:PerusOpetusKieliType" maxOccurs="3" minOccurs="0"/>
              <element name="MA" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="KS" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="KE" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="KU" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="KO" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="BI" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="MU" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="LI" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="HI" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="FY" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="YH" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="TE" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="KT" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
              <element name="GE" type="arvosanat:PerusOpetusAineType" maxOccurs="1" minOccurs="1"/>
            </sequence>
            <element name="eivalmistu" type="arvosanat:EiValmistuType"/>
          </choice>
        </extension>
      </complexContent>
    </complexType>

    <complexType name="LisaopetusType">
      <complexContent>
        <extension base="arvosanat:SuoritusType"/>
      </complexContent>
    </complexType>

    <complexType name="PerusOpetusAineType">
      <sequence>
        <element name="yhteinen" type="arvosanat:arvosana410" maxOccurs="1" minOccurs="1"/>
        <element name="valinnainen" type="arvosanat:arvosana410" maxOccurs="3" minOccurs="0"/>
      </sequence>
    </complexType>

    <complexType name="LukioAineType">
      <sequence>
        <element name="arvio" type="arvosanat:arvosana410" maxOccurs="1" minOccurs="1"/>
      </sequence>
    </complexType>

    <complexType name="LukioAineLaajuudellaType">
      <complexContent>
        <extension base="arvosanat:LukioAineType">
          <sequence>
            <element name="laajuus" type="arvosanat:aineenLaajuus"/>
          </sequence>
        </extension>
      </complexContent>
    </complexType>

    <simpleType name="aineenLaajuus">
      <restriction base="string">
        <enumeration value="lyhyt"/>
        <enumeration value="pitkä"/>
      </restriction>
    </simpleType>

    <simpleType name="arvosana410">
      <restriction base="string">
        <enumeration value="4"/>
        <enumeration value="5"/>
        <enumeration value="6"/>
        <enumeration value="7"/>
        <enumeration value="8"/>
        <enumeration value="9"/>
        <enumeration value="10"/>
        <enumeration value="S"/>
      </restriction>
    </simpleType>

    <complexType name="PerusOpetusAidinkieliType">
      <complexContent>
        <extension base="arvosanat:PerusOpetusAineType">
          <sequence>
            <element name="tyyppi" type="koodisto:aidinkielijakirjallisuus"/>
          </sequence>
        </extension>
      </complexContent>
    </complexType>

    <complexType name="LukioAidinkieliType">
      <complexContent>
        <extension base="arvosanat:LukioAineType">
          <sequence>
            <element name="tyyppi" type="koodisto:aidinkielijakirjallisuus"/>
          </sequence>
        </extension>
      </complexContent>
    </complexType>

    <simpleType name="EiValmistuType">
      <restriction base="string">
        <enumeration value="JAA LUOKALLE"/>
        <enumeration value="HYLATTY"/>
      </restriction>
    </simpleType>

    <group name="SyntymaAjallinen">
      <sequence>
        <element name="syntymaaika" type="date" maxOccurs="1" minOccurs="1"/>
        <element name="tunniste" type="string" maxOccurs="1" minOccurs="1"/>
      </sequence>
    </group>

    <complexType name="PerusOpetusKieliType">
      <complexContent>
        <extension base="arvosanat:PerusOpetusAineType">
          <sequence>
            <element name="kieli" type="string"/>
          </sequence>
        </extension>
      </complexContent>
    </complexType>

    <complexType name="LukioKieliType">
      <complexContent>
        <extension base="arvosanat:LukioAineType">
          <sequence>
            <element name="kieli" type="string"/>
          </sequence>
        </extension>
      </complexContent>
    </complexType>

    <simpleType name="hetuType">
      <restriction base="string">
        <pattern value="[0-9]{6}[-A][0-9]{3}[0123456789ABCDEFHJKLMNPRSTUVWXY]"/>
      </restriction>
    </simpleType>

    <simpleType name="syntymaAikaType">
      <restriction base="string">
        <pattern value="[0-9]{8}"/>
      </restriction>
    </simpleType>

    <simpleType name="oppijaNumeroType">
      <restriction base="string">
        <pattern value="1\.2\.246\.562\.24\.[0-9]{11}"/>
      </restriction>
    </simpleType>

    <group name="PerusopetuksenKaynyt">
      <sequence>
        <element name="perusopetus" type="arvosanat:PerusopetusType" maxOccurs="1" minOccurs="1"/>
        <element name="perusopetuksenlisaopetus" type="arvosanat:LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <element name="ammattistartti" type="arvosanat:SuoritusType" maxOccurs="1" minOccurs="0"/>
        <element name="valmentava" type="arvosanat:SuoritusType" maxOccurs="1" minOccurs="0"/>
        <element name="maahanmuuttajanlukioonvalmistava" type="arvosanat:SuoritusType" maxOccurs="1" minOccurs="0"/>
        <element name="maahanmuuttajanammattiinvalmistava" type="arvosanat:SuoritusType" maxOccurs="1" minOccurs="0"/>
      </sequence>
    </group>

    <group name="LukionKaynyt">
      <sequence>
        <element name="lukio" type="arvosanat:LukioType" maxOccurs="1" minOccurs="1"/>
      </sequence>
    </group>

    <complexType name="SuoritusType">
      <sequence>
        <element name="valmistuminen" type="date"/>
        <element name="myontaja" type="koodisto:oppilaitosnumero"/>
        <element name="suorituskieli" type="koodisto:kieli"/>
      </sequence>
    </complexType>

    <complexType name="LukioType">
      <complexContent>
        <extension base="arvosanat:SuoritusType">
          <sequence>
            <element name="AI" type="arvosanat:LukioAidinkieliType" maxOccurs="1" minOccurs="1"/>
            <element name="A1" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="1"/>
            <element name="A12" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <element name="A2" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="1"/>
            <element name="A22" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <element name="B1" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <element name="B2" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <element name="B22" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <element name="B23" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <element name="B3" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <element name="B32" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <element name="B33" type="arvosanat:LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <element name="MA" type="arvosanat:LukioAineLaajuudellaType" maxOccurs="1" minOccurs="1"/>
            <element name="BI" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="1"/>
            <element name="GE" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="1"/>
            <element name="FY" type="arvosanat:LukioAineLaajuudellaType" maxOccurs="1" minOccurs="1"/>
            <element name="KE" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="1"/>
            <element name="TE" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="1"/>
            <element name="KT" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="1"/>
            <element name="HI" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="1"/>
            <element name="YH" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="1"/>
            <element name="MU" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="1"/>
            <element name="KU" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="1"/>
            <element name="LI" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="1"/>
            <element name="PS" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="0"/>
            <element name="FI" type="arvosanat:LukioAineType" maxOccurs="1" minOccurs="0"/>
          </sequence>
        </extension>
      </complexContent>
    </complexType>

    <group name="UlkomainenKorvaava">
      <sequence>
        <element name="ulkomainen" type="arvosanat:SuoritusType" maxOccurs="1" minOccurs="1"/>
        <element name="ammattistartti" type="arvosanat:SuoritusType" maxOccurs="1" minOccurs="0"/>
        <element name="valmentava" type="arvosanat:SuoritusType" maxOccurs="1" minOccurs="0"/>
        <element name="maahanmuuttajanlukioonvalmistava" type="arvosanat:SuoritusType" maxOccurs="1" minOccurs="0"/>
        <element name="maahanmuuttajanammattiinvalmistava" type="arvosanat:SuoritusType" maxOccurs="1" minOccurs="0"/>
      </sequence>
    </group>

    <group name="AmmattikoulunKaynyt">
      <sequence>
        <element name="ammattikoulu" type="arvosanat:SuoritusType" maxOccurs="1" minOccurs="1"/>
      </sequence>
    </group>
  </schema>

}

object ArvosanatKoodisto extends IncludeSchema("http://service.koodisto.sade.vm.fi/types/koodisto", "https://virkailija.opintopolku.fi/koodisto-service/rest/oppilaitosnumero.xsd", "https://virkailija.opintopolku.fi/koodisto-service/rest/kieli.xsd", "https://virkailija.opintopolku.fi/koodisto-service/rest/aidinkielijakirjallisuus.xsd") {
  override val schemaLocation: String = "arvosanat-koodisto.xsd"
}