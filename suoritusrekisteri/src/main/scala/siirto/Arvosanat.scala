package siirto

import fi.vm.sade.hakurekisteri.suoritus.DayFinder.saturdayOfWeek22
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat

object Arvosanat extends SchemaDefinition {
  val schemaLocation = "arvosanat.xsd"
  val schema = <xs:schema attributeFormDefault="unqualified"
                          elementFormDefault="qualified"
                          xmlns:xs="http://www.w3.org/2001/XMLSchema"
                          xmlns:koodisto="http://service.koodisto.sade.vm.fi/types/koodisto">
    <xs:import schemaLocation="arvosanat-koodisto.xsd" namespace="http://service.koodisto.sade.vm.fi/types/koodisto"/>

    <xs:element name="arvosanat" type="ArvosanatType">

    </xs:element>

    <xs:complexType name="ArvosanatType">
      <xs:sequence>
        <xs:element name="eranTunniste" minOccurs="1" maxOccurs="1"/>
        <xs:element type="HenkilotType" name="henkilot" maxOccurs="1" minOccurs="1">
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
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="HenkilotType">
      <xs:sequence>
        <xs:element type="HenkiloType" name="henkilo" maxOccurs="unbounded" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="HenkiloType">
      <xs:sequence>
        <xs:choice maxOccurs="1" minOccurs="1">
          <xs:element name="hetu" type="hetuType" maxOccurs="1" minOccurs="1"/>
          <xs:element name="oppijanumero" type="oppijaNumeroType" maxOccurs="1" minOccurs="1"/>
          <xs:group ref="SyntymaAjallinen"/>
        </xs:choice>
        <xs:element type="xs:string" name="sukunimi" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="etunimet" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="kutsumanimi" maxOccurs="1" minOccurs="1"/>
        <xs:element name="todistukset" type="TodistuksetType" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="TodistuksetType">
      <xs:choice>
        <xs:group ref="PerusopetuksenKaynyt"/>
        <xs:group ref="LisaopetuksenKaynyt"/>
        <xs:group ref="AmmattistartinKaynyt"/>
        <xs:group ref="ValmentavanKaynyt"/>
        <xs:group ref="MaahanmuuttajienLukioonValmistavanKaynyt"/>
        <xs:group ref="MaahanmuuttajienValmistavanKaynyt"/>
        <xs:group ref="LukionKaynyt"/>
        <xs:group ref="UlkomainenKorvaava"/>
        <xs:group ref="AmmattikoulunKaynyt"/>
      </xs:choice>
    </xs:complexType>

    <xs:complexType name="PerusopetusType">
      <xs:complexContent>
        <xs:extension base="SuoritusWithoutDateType">
          <xs:choice>
            <xs:sequence>
              <xs:element name="valmistuminen" type="valmistuvanPaivaysType" maxOccurs="1" minOccurs="1"/>
              <xs:element name="AI" type="PerusOpetusAidinkieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="A1" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="A12" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="A2" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="A22" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="B1" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="B2" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="B22" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="B23" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="MA" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="KS" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="KE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="KU" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="KO" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="BI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="MU" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="LI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="HI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="FY" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="YH" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="TE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="KT" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="GE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            </xs:sequence>
            <xs:sequence>
              <xs:element name="opetuspaattynyt" type="valmistuvanPaivaysType" maxOccurs="1" minOccurs="1"/>
              <xs:element name="eivalmistu" type="EiValmistuType" maxOccurs="1" minOccurs="1"/>
            </xs:sequence>
            <xs:sequence>
              <xs:element name="oletettuvalmistuminen" type="luokalleJaavanPaivaysType" maxOccurs="1" minOccurs="1"/>
              <xs:element name="valmistuminensiirtyy" type="EiValmistuJaaLuokalleType"  maxOccurs="1" minOccurs="1"/>
            </xs:sequence>
          </xs:choice>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:complexType name="LisaopetusType">
      <xs:complexContent>
        <xs:extension base="SuoritusType">
          <xs:sequence>
            <xs:element name="AI" type="PerusOpetusAidinkieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A1" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A12" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A2" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A22" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B1" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B2" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B22" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B23" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="MA" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KS" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KU" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KO" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="BI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="MU" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="LI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="HI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="FY" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="YH" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="TE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KT" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="GE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="eivalmistu" type="EiValmistuLisaType" maxOccurs="1" minOccurs="0"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:complexType name="PerusOpetusAineType">
      <xs:sequence>
        <xs:element name="yhteinen" type="arvosana410" maxOccurs="1" minOccurs="1"/>
        <xs:element name="valinnainen" type="arvosana410" maxOccurs="3" minOccurs="0"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="LukioAineType">
      <xs:sequence>
        <xs:element name="yhteinen" type="arvosana410" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="LukioAineLaajuudellaType">
      <xs:complexContent>
        <xs:extension base="LukioAineType">
          <xs:sequence>
            <xs:element name="laajuus" type="aineenLaajuus"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:simpleType name="aineenLaajuus">
      <xs:restriction base="xs:string">
        <xs:enumeration value="lyhyt"/>
        <xs:enumeration value="pitka"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="arvosana410">
      <xs:restriction base="xs:string">
        <xs:enumeration value="4"/>
        <xs:enumeration value="5"/>
        <xs:enumeration value="6"/>
        <xs:enumeration value="7"/>
        <xs:enumeration value="8"/>
        <xs:enumeration value="9"/>
        <xs:enumeration value="10"/>
        <xs:enumeration value="S"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:complexType name="PerusOpetusAidinkieliType">
      <xs:complexContent>
        <xs:extension base="PerusOpetusAineType">
          <xs:sequence>
            <xs:element name="tyyppi" type="koodisto:aidinkielijakirjallisuus"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:complexType name="LukioAidinkieliType">
      <xs:complexContent>
        <xs:extension base="LukioAineType">
          <xs:sequence>
            <xs:element name="tyyppi" type="koodisto:aidinkielijakirjallisuus"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:simpleType name="EiValmistuType">
      <xs:restriction base="xs:string">
        <xs:enumeration value="PERUSOPETUS PAATTYNYT VALMISTUMATTA"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="EiValmistuJaaLuokalleType">
      <xs:restriction base="xs:string">
        <xs:enumeration value="JAA LUOKALLE"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="EiValmistuLisaType">
      <xs:restriction base="xs:string">
        <xs:enumeration value="SUORITUS HYLATTY"/>
      </xs:restriction>
    </xs:simpleType>



    <xs:group name="SyntymaAjallinen">
      <xs:sequence>
        <xs:element name="henkiloTunniste" type="xs:string" maxOccurs="1" minOccurs="1"/>
        <xs:element name="syntymaAika" type="xs:date" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:group>

    <xs:complexType name="PerusOpetusKieliType">
      <xs:complexContent>
        <xs:extension base="PerusOpetusAineType">
          <xs:sequence>
            <xs:element name="kieli" type="koodisto:kieli"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:complexType name="LukioKieliType">
      <xs:complexContent>
        <xs:extension base="LukioAineType">
          <xs:sequence>
            <xs:element name="kieli" type="koodisto:kieli"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:simpleType name="hetuType">
      <xs:restriction base="xs:string">
        <xs:pattern value="[0-9]{6}[-A][0-9]{3}[0123456789ABCDEFHJKLMNPRSTUVWXY]"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="valmistuvanPaivaysType">
      <xs:restriction base="xs:date">
        <xs:maxInclusive value={
    DateTimeFormat.forPattern("yyyy-MM-dd").print(saturdayOfWeek22(LocalDate.now().getYear))
  }/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="luokalleJaavanPaivaysType">
      <xs:restriction base="xs:date">
        <xs:minInclusive value={
    DateTimeFormat
      .forPattern("yyyy-MM-dd")
      .print(LocalDate.now().withMonthOfYear(8).withDayOfMonth(1))
  }/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="oppijaNumeroType">
      <xs:restriction base="xs:string">
        <xs:pattern value="1\.2\.246\.562\.24\.[0-9]{11}"/>
      </xs:restriction>
    </xs:simpleType>



    <xs:group name="PerusopetuksenKaynyt">
      <xs:sequence>
        <xs:element name="perusopetus" type="PerusopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="perusopetuksenlisaopetus" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="ammattistartti" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valmentava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>


      <xs:group name="LisaopetuksenKaynyt">
      <xs:sequence>
        <xs:element name="perusopetuksenlisaopetus" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="ammattistartti" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valmentava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="AmmattistartinKaynyt">
      <xs:sequence>
        <xs:element name="ammattistartti" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="valmentava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>


      <xs:group name="ValmentavanKaynyt">
      <xs:sequence>
        <xs:element name="valmentava" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="MaahanmuuttajienLukioonValmistavanKaynyt">
      <xs:sequence>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="MaahanmuuttajienValmistavanKaynyt">
      <xs:sequence>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:group>


    <xs:group name="LukionKaynyt">
      <xs:sequence>
        <xs:element name="lukio" type="LukioType" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:group>

    <xs:complexType name="SuoritusWithoutDateType">
      <xs:sequence>
        <xs:element name="myontaja" type="koodisto:oppilaitosnumero" maxOccurs="1" minOccurs="1"/>
        <xs:element name="suorituskieli" type="koodisto:kieli" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="SuoritusType">
      <xs:complexContent>
        <xs:extension base="SuoritusWithoutDateType">
          <xs:sequence>
            <xs:element name="valmistuminen" type="valmistuvanPaivaysType" maxOccurs="1" minOccurs="1"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>


    <xs:complexType name="LukioType">
      <xs:complexContent>
        <xs:extension base="SuoritusType">
          <xs:sequence>
            <xs:element name="AI" type="LukioAidinkieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A1" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A12" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A2" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A22" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B1" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B2" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B22" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B23" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B3" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B32" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B33" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="MA" type="LukioAineLaajuudellaType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="BI" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="GE" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="FY" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KE" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="TE" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KT" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="HI" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="YH" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="MU" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KU" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="LI" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="PS" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="FI" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:group name="UlkomainenKorvaava">
      <xs:sequence>
        <xs:element name="ulkomainen" type="SuoritusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="ammattistartti" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valmentava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="telma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="AmmattikoulunKaynyt">
      <xs:sequence>
        <xs:element name="ammatillinen" type="SuoritusType" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:group>
  </xs:schema>

}

object ArvosanatV2 extends SchemaDefinition {
  val schemaLocation = "arvosanat.xsd"
  val schema = <xs:schema attributeFormDefault="unqualified"
                          elementFormDefault="qualified"
                          xmlns:xs="http://www.w3.org/2001/XMLSchema"
                          xmlns:koodisto="http://service.koodisto.sade.vm.fi/types/koodisto">
    <xs:import schemaLocation="arvosanat-koodisto.xsd" namespace="http://service.koodisto.sade.vm.fi/types/koodisto"/>

    <xs:element name="arvosanat" type="ArvosanatType">

    </xs:element>

    <xs:complexType name="ArvosanatType">
      <xs:sequence>
        <xs:element name="eranTunniste" minOccurs="1" maxOccurs="1"/>
        <xs:element type="HenkilotType" name="henkilot" maxOccurs="1" minOccurs="1">
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
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="HenkilotType">
      <xs:sequence>
        <xs:element type="HenkiloType" name="henkilo" maxOccurs="unbounded" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="HenkiloType">
      <xs:sequence>
        <xs:choice maxOccurs="1" minOccurs="1">
          <xs:element name="hetu" type="hetuType" maxOccurs="1" minOccurs="1"/>
          <xs:element name="oppijanumero" type="oppijaNumeroType" maxOccurs="1" minOccurs="1"/>
          <xs:group ref="SyntymaAjallinen"/>
        </xs:choice>
        <xs:element type="xs:string" name="sukunimi" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="etunimet" maxOccurs="1" minOccurs="1"/>
        <xs:element type="xs:string" name="kutsumanimi" maxOccurs="1" minOccurs="1"/>
        <xs:element name="todistukset" type="TodistuksetType" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="TodistuksetType">
      <xs:choice>
        <xs:group ref="PerusopetuksenKaynyt"/>
        <xs:group ref="LisaopetuksenKaynyt"/>
        <xs:group ref="AmmattistartinKaynyt"/>
        <xs:group ref="ValmentavanKaynyt"/>
        <xs:group ref="MaahanmuuttajienLukioonValmistavanKaynyt"/>
        <xs:group ref="MaahanmuuttajienValmistavanKaynyt"/>
        <xs:group ref="Valma"/>
        <xs:group ref="Telma"/>
        <xs:group ref="LukionKaynyt"/>
        <xs:group ref="UlkomainenKorvaava"/>
        <xs:group ref="AmmattikoulunKaynyt"/>
      </xs:choice>
    </xs:complexType>

    <xs:complexType name="PerusopetusType">
      <xs:complexContent>
        <xs:extension base="SuoritusWithoutDateType">
          <xs:choice>
            <xs:sequence>
              <xs:element name="valmistuminen" type="valmistuvanPaivaysType" maxOccurs="1" minOccurs="1"/>
              <xs:element name="AI" type="PerusOpetusAidinkieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="A1" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="A12" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="A2" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="A22" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="B1" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="B2" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="B22" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="B23" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="MA" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="KS" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="KE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="KU" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="KO" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="BI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="MU" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="LI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="HI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="FY" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="YH" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="TE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="KT" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
              <xs:element name="GE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            </xs:sequence>
            <xs:sequence>
              <xs:element name="opetuspaattynyt" type="valmistuvanPaivaysType" maxOccurs="1" minOccurs="1"/>
              <xs:element name="eivalmistu" type="EiValmistuType" maxOccurs="1" minOccurs="1"/>
            </xs:sequence>
          </xs:choice>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:complexType name="LisaopetusType">
      <xs:complexContent>
        <xs:extension base="SuoritusType">
          <xs:sequence>
            <xs:element name="AI" type="PerusOpetusAidinkieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A1" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A12" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A2" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A22" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B1" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B2" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B22" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B23" type="PerusOpetusKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="MA" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KS" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KU" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KO" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="BI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="MU" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="LI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="HI" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="FY" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="YH" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="TE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KT" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="GE" type="PerusOpetusAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="eivalmistu" type="EiValmistuLisaType" maxOccurs="1" minOccurs="0"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:complexType name="PerusOpetusAineType">
      <xs:sequence>
        <xs:element name="yhteinen" type="arvosana410" maxOccurs="1" minOccurs="1"/>
        <xs:element name="valinnainen" type="arvosana410" maxOccurs="3" minOccurs="0"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="LukioAineType">
      <xs:sequence>
        <xs:element name="yhteinen" type="arvosana410" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="LukioAineLaajuudellaType">
      <xs:complexContent>
        <xs:extension base="LukioAineType">
          <xs:sequence>
            <xs:element name="laajuus" type="aineenLaajuus"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:simpleType name="aineenLaajuus">
      <xs:restriction base="xs:string">
        <xs:enumeration value="lyhyt"/>
        <xs:enumeration value="pitka"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="arvosana410">
      <xs:restriction base="xs:string">
        <xs:enumeration value="4"/>
        <xs:enumeration value="5"/>
        <xs:enumeration value="6"/>
        <xs:enumeration value="7"/>
        <xs:enumeration value="8"/>
        <xs:enumeration value="9"/>
        <xs:enumeration value="10"/>
        <xs:enumeration value="S"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:complexType name="PerusOpetusAidinkieliType">
      <xs:complexContent>
        <xs:extension base="PerusOpetusAineType">
          <xs:sequence>
            <xs:element name="tyyppi" type="koodisto:aidinkielijakirjallisuus"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:complexType name="LukioAidinkieliType">
      <xs:complexContent>
        <xs:extension base="LukioAineType">
          <xs:sequence>
            <xs:element name="tyyppi" type="koodisto:aidinkielijakirjallisuus"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:simpleType name="EiValmistuType">
      <xs:restriction base="xs:string">
        <xs:enumeration value="PERUSOPETUS PAATTYNYT VALMISTUMATTA"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="EiValmistuLisaType">
      <xs:restriction base="xs:string">
        <xs:enumeration value="SUORITUS HYLATTY"/>
      </xs:restriction>
    </xs:simpleType>



    <xs:group name="SyntymaAjallinen">
      <xs:sequence>
        <xs:element name="henkiloTunniste" type="xs:string" maxOccurs="1" minOccurs="1"/>
        <xs:element name="syntymaAika" type="xs:date" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:group>

    <xs:complexType name="PerusOpetusKieliType">
      <xs:complexContent>
        <xs:extension base="PerusOpetusAineType">
          <xs:sequence>
            <xs:element name="kieli" type="koodisto:kieli"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:complexType name="LukioKieliType">
      <xs:complexContent>
        <xs:extension base="LukioAineType">
          <xs:sequence>
            <xs:element name="kieli" type="koodisto:kieli"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:simpleType name="hetuType">
      <xs:restriction base="xs:string">
        <xs:pattern value="[0-9]{6}[-A][0-9]{3}[0123456789ABCDEFHJKLMNPRSTUVWXY]"/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="valmistuvanPaivaysType">
      <xs:restriction base="xs:date">
        <xs:maxInclusive value={
    DateTimeFormat.forPattern("yyyy-MM-dd").print(saturdayOfWeek22(LocalDate.now().getYear))
  }/>
      </xs:restriction>
    </xs:simpleType>

    <xs:simpleType name="oppijaNumeroType">
      <xs:restriction base="xs:string">
        <xs:pattern value="1\.2\.246\.562\.24\.[0-9]{11}"/>
      </xs:restriction>
    </xs:simpleType>



    <xs:group name="PerusopetuksenKaynyt">
      <xs:sequence>
        <xs:element name="perusopetus" type="PerusopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="perusopetuksenlisaopetus" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="ammattistartti" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valmentava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="telma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>


    <xs:group name="LisaopetuksenKaynyt">
      <xs:sequence>
        <xs:element name="perusopetuksenlisaopetus" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="ammattistartti" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valmentava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="telma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="AmmattistartinKaynyt">
      <xs:sequence>
        <xs:element name="ammattistartti" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="valmentava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="telma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>


    <xs:group name="ValmentavanKaynyt">
      <xs:sequence>
        <xs:element name="valmentava" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="telma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="MaahanmuuttajienLukioonValmistavanKaynyt">
      <xs:sequence>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="telma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="MaahanmuuttajienValmistavanKaynyt">
      <xs:sequence>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="valma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="telma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="Valma">
      <xs:sequence>
        <xs:element name="valma" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="telma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="Telma">
      <xs:sequence>
        <xs:element name="telma" type="LisaopetusType" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="LukionKaynyt">
      <xs:sequence>
        <xs:element name="lukio" type="LukioType" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:group>

    <xs:complexType name="SuoritusWithoutDateType">
      <xs:sequence>
        <xs:element name="myontaja" type="koodisto:oppilaitosnumero" maxOccurs="1" minOccurs="1"/>
        <xs:element name="suorituskieli" type="koodisto:kieli" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:complexType>

    <xs:complexType name="SuoritusType">
      <xs:complexContent>
        <xs:extension base="SuoritusWithoutDateType">
          <xs:sequence>
            <xs:element name="valmistuminen" type="valmistuvanPaivaysType" maxOccurs="1" minOccurs="1"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>


    <xs:complexType name="LukioType">
      <xs:complexContent>
        <xs:extension base="SuoritusType">
          <xs:sequence>
            <xs:element name="AI" type="LukioAidinkieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A1" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A12" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A2" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="A22" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B1" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B2" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B22" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B23" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B3" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B32" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="B33" type="LukioKieliType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="MA" type="LukioAineLaajuudellaType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="BI" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="GE" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="FY" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KE" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="TE" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KT" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="HI" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="YH" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="MU" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="KU" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="LI" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="PS" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
            <xs:element name="FI" type="LukioAineType" maxOccurs="1" minOccurs="0"/>
          </xs:sequence>
        </xs:extension>
      </xs:complexContent>
    </xs:complexType>

    <xs:group name="UlkomainenKorvaava">
      <xs:sequence>
        <xs:element name="ulkomainen" type="SuoritusType" maxOccurs="1" minOccurs="1"/>
        <xs:element name="ammattistartti" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valmentava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienlukioonvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="maahanmuuttajienammvalmistava" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="valma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
        <xs:element name="telma" type="LisaopetusType" maxOccurs="1" minOccurs="0"/>
      </xs:sequence>
    </xs:group>

    <xs:group name="AmmattikoulunKaynyt">
      <xs:sequence>
        <xs:element name="ammatillinen" type="SuoritusType" maxOccurs="1" minOccurs="1"/>
      </xs:sequence>
    </xs:group>
  </xs:schema>

}

object ArvosanatKoodisto
    extends IncludeSchema(
      "http://service.koodisto.sade.vm.fi/types/koodisto",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/oppilaitosnumero.xsd",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/kieli.xsd",
      "https://virkailija.opintopolku.fi/koodisto-service/rest/aidinkielijakirjallisuus.xsd"
    ) {
  override val schemaLocation: String = "arvosanat-koodisto.xsd"
}
