package siirto

import scalaz._
import org.xml.sax.SAXParseException
import scala.xml.{XML, Elem}
import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.matchers.{MatchResult, Matcher}
import generators.DataGen
import org.joda.time.LocalDate

class ArvosanaXmlSpec extends FlatSpec with Matchers {

  behavior of "Arvosana Xml Validation"

  val validator: XMLValidator[ValidationNel[(String, SAXParseException), Elem],NonEmptyList[(String, SAXParseException)], Elem] = new ValidXml(Arvosanat, ArvosanatKoodisto)

  it should "mark valid perusopetuksen todistus as valid" in {
    val todistus = siirto(henkilo(perusopetus)).generate
    validator.validate(todistus)  should succeed
  }

  it should "mark luokalle jaava as valid" in {
    val todistus =  siirto(henkilo(jaaLuokalle)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark perusopetuksen keskettanyt as valid" in {
    val todistus =  siirto(henkilo(keskeyttanytPerusopetuksen)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark lisaopetuksen kaynyt as valid" in {
    val todistus =  siirto(henkilo(lisaopetus)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark lisaopetuksen keskeyttanyt as valid" in {
    val todistus =  siirto(henkilo(lisaopetuksenKeskeyttanyt)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark ammattistartin kaynyt as valid" in {
    val todistus =  siirto(henkilo(ammattistartti)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark ammattistartin keskeyttanyt as valid" in {
    val todistus =  siirto(henkilo(ammattistartinKeskeyttanyt)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark valmentavan kaynyt as valid" in {
    val todistus =  siirto(henkilo(valmentava)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark valmentavan keskeyttanyt as valid" in {
    val todistus =  siirto(henkilo(valmentavanKeskeyttanyt)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark maahanmuuttajien lukioonvalmistavan kaynyt as valid" in {
    val todistus =  siirto(henkilo(maahanmuuttajienLukioonValmistava)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark maahanmuuttajien lukioonvalmistavan keskeyttanyt as valid" in {
    val todistus =  siirto(henkilo(maahanmuuttajienLukioonValmistavanKeskeyttanyt)).generate
    validator.validate(todistus) should succeed
  }


  it should "mark maahanmuuttajien ammattiin valmistavan kaynyt as valid" in {
    val todistus =  siirto(henkilo(maahanmuuttajienAmmValmistava)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark maahanmuuttajien ammattiin valmistavan keskeyttanyt as valid" in {
    val todistus =  siirto(henkilo(maahanmuuttajienLukioonValmistavanKeskeyttanyt)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark lukion kaynyt as valid" in {
    val todistus =  siirto(henkilo(lukio)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark ammattikoulun kaynyt as valid" in {
    val todistus =  siirto(henkilo(ammattikoulu)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark ulkomaalaisen korvaavn kaynyt as valid" in {
    val todistus =  siirto(hetutonHenkilo(ulkomainenKorvaava)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark syntyma-ajallinen henkilo as valid" in {
    val todistus =  siirto(hetutonHenkilo(perusopetus)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark oidillinen henkilo as valid" in {
    val todistus =  siirto(henkiloOidilla(perusopetus)).generate
    validator.validate(todistus) should succeed
  }

  it should "mark todistus with multiple entries as valid" in {
    val todistus = siirto(
      henkilo(perusopetus),
      henkiloOidilla(jaaLuokalle),
      henkilo(keskeyttanytPerusopetuksen),
      henkilo(lisaopetus),
      henkilo(lisaopetuksenKeskeyttanyt),
      henkilo(ammattistartti),henkilo(ammattistartinKeskeyttanyt),henkilo(valmentava), henkilo(valmentavanKeskeyttanyt),henkilo(maahanmuuttajienLukioonValmistava),henkilo(maahanmuuttajienLukioonValmistavanKeskeyttanyt),henkilo(maahanmuuttajienAmmValmistava),henkilo(maahanmuuttajienLukioonValmistavanKeskeyttanyt),henkilo(lukio),henkilo(ammattikoulu),hetutonHenkilo(ulkomainenKorvaava)
    ).generate
    XML.save("arvosanat-example.xml", todistus)
  }



  val succeed =
    Matcher { (left: Validation[Any,Any]) =>
      MatchResult(
        left.isSuccess,
        left + " failed",
        left + " succeeded"
      )
    }


  def suoritus(name:String): DataGen[Elem] = DataGen.always(
    <suoritus>
      <myontaja>05127</myontaja>
      <suorituskieli>FI</suorituskieli>
    </suoritus>.copy(label = name)
  )

  def valmistunutSuoritus(name:String): DataGen[Elem] = for (
    baseSuoritus <- suoritus(name)
  ) yield baseSuoritus.copy(child = baseSuoritus.child ++ <valmistuminen>2014-05-30</valmistuminen>)

  val perusopetuksenAineet = DataGen.always(
    <aineet>
      <AI>
        <yhteinen>5</yhteinen>
        <tyyppi>FI</tyyppi>
      </AI>
      <A1>
        <yhteinen>5</yhteinen>
        <valinnainen>6</valinnainen>
        <valinnainen>8</valinnainen>
        <kieli>EN</kieli>
      </A1>
      <B1>
        <yhteinen>5</yhteinen>
        <kieli>SV</kieli>
      </B1>
      <MA>
        <yhteinen>5</yhteinen>
        <valinnainen>6</valinnainen>
        <valinnainen>8</valinnainen>
      </MA>
      <KS>
        <yhteinen>5</yhteinen>
        <valinnainen>6</valinnainen>
      </KS>
      <KE>
        <yhteinen>5</yhteinen>
      </KE>
      <KU>
        <yhteinen>5</yhteinen>
      </KU>
      <KO>
        <yhteinen>5</yhteinen>
      </KO>
      <BI>
        <yhteinen>5</yhteinen>
      </BI>
      <MU>
        <yhteinen>5</yhteinen>
      </MU>
      <LI>
        <yhteinen>5</yhteinen>
      </LI>
      <HI>
        <yhteinen>5</yhteinen>
      </HI>
      <FY>
        <yhteinen>5</yhteinen>
      </FY>
      <YH>
        <yhteinen>5</yhteinen>
      </YH>
      <TE>
        <yhteinen>5</yhteinen>
      </TE>
      <KT>
        <yhteinen>5</yhteinen>
      </KT>
      <GE>
        <yhteinen>5</yhteinen>
      </GE>
    </aineet>.child)

  val perusopetus: DataGen[Elem] = for (
    perusopetusBase <- valmistunutSuoritus("perusopetus");
    aineet <- perusopetuksenAineet
  ) yield perusopetusBase.copy(child = perusopetusBase.child ++ aineet)

  val jaaLuokalleLisaTiedot =
    DataGen.always(<perusopetus>
      <oletettuvalmistuminen>2016-06-01</oletettuvalmistuminen>
      <valmistuminensiirtyy>JAA LUOKALLE</valmistuminensiirtyy>
    </perusopetus>.child)

  val jaaLuokalle = for(
    perusopetusBase <- suoritus("perusopetus");
    jaaLuokalleTiedot <- jaaLuokalleLisaTiedot

  ) yield perusopetusBase.copy(child = perusopetusBase.child ++ jaaLuokalleTiedot)

  val keskeyttanytLisatiedot =  DataGen.always(
    <perusopetus>
      <opetuspaattynyt>2015-06-01</opetuspaattynyt>
      <eivalmistu>PERUSOPETUS PAATTYNYT VALMISTUMATTA</eivalmistu>
    </perusopetus>.child
  )

  val keskeyttanytPerusopetuksen = for(
    perusopetusBase <- suoritus("perusopetus");
    keskeytysTiedot <- keskeyttanytLisatiedot

  ) yield perusopetusBase.copy(child = perusopetusBase.child ++ keskeytysTiedot)

  val lukionAineet = DataGen.always(
    <lukio>
      <AI>
        <yhteinen>7</yhteinen>
        <tyyppi>FI</tyyppi>
      </AI>
      <A1>
        <yhteinen>9</yhteinen>
        <kieli>EN</kieli>
      </A1>
      <B1>
        <yhteinen>5</yhteinen>
        <kieli>SV</kieli>
      </B1>
      <MA>
        <yhteinen>5</yhteinen>
        <laajuus>pitka</laajuus>
      </MA>
      <BI>
        <yhteinen>7</yhteinen>
      </BI>
      <GE>
        <yhteinen>7</yhteinen>
      </GE>
      <FY>
        <yhteinen>7</yhteinen>
      </FY>
      <KE>
        <yhteinen>7</yhteinen>
      </KE>
      <TE>
        <yhteinen>7</yhteinen>
      </TE>
      <KT>
        <yhteinen>7</yhteinen>
      </KT>
      <HI>
        <yhteinen>7</yhteinen>
      </HI>
      <YH>
        <yhteinen>7</yhteinen>
      </YH>
      <MU>
        <yhteinen>7</yhteinen>
      </MU>
      <KU>
        <yhteinen>7</yhteinen>
      </KU>
      <LI>
        <yhteinen>7</yhteinen>
      </LI>
      <PS>
        <yhteinen>7</yhteinen>
      </PS>
      <FI>
        <yhteinen>7</yhteinen>
      </FI>
    </lukio>.child
  )

  val lukio = for (
    baseLukio <- valmistunutSuoritus("lukio");
    aineet <- lukionAineet
  ) yield baseLukio.copy(child = baseLukio.child ++ aineet)



  val ammattikoulu = valmistunutSuoritus("ammatillinen")

  val lisaopetus = convertPerusopetus("perusopetuksenlisaopetus")


  def convertPerusopetus(label: String): DataGen[Elem] = {
    for (
      perusopetusTodistus <- perusopetus
    ) yield {

      perusopetusTodistus.copy(label = label)
    }
  }

  val lisaopetuksenKeskeyttanyt = eiValmistuLisaopetuksesta(lisaopetus)


  def eiValmistuLisaopetuksesta(lisaopetusTodistusGen:DataGen[Elem]): DataGen[Elem] = {
    for (
      lisaopetusTodistus <- lisaopetusTodistusGen
    ) yield lisaopetusTodistus.copy(child = lisaopetusTodistus.child ++ Seq(<eivalmistu>SUORITUS HYLATTY</eivalmistu>))
  }

  val ammattistartti = convertPerusopetus("ammattistartti")

  val ammattistartinKeskeyttanyt = eiValmistuLisaopetuksesta(ammattistartti)

  val valmentava = convertPerusopetus("valmentava")

  val valmentavanKeskeyttanyt = eiValmistuLisaopetuksesta(valmentava)

  val maahanmuuttajienLukioonValmistava = convertPerusopetus("maahanmuuttajienlukioonvalmistava")

  val maahanmuuttajienLukioonValmistavanKeskeyttanyt = eiValmistuLisaopetuksesta(maahanmuuttajienLukioonValmistava)

  val maahanmuuttajienAmmValmistava = convertPerusopetus("maahanmuuttajienammvalmistava")

  val maahanmuuttajienAmmValmistavanKeskeyttanyt = eiValmistuLisaopetuksesta(maahanmuuttajienAmmValmistava)

  val ulkomainenKorvaava = valmistunutSuoritus("ulkomainen")


  def henkilo(todistuksetGen:DataGen[Elem]*): DataGen[Elem] = for (
    todistukset <- DataGen.combine[Elem](todistuksetGen);
    hetu <- DataGen.hetu
  ) yield <henkilo>
      <hetu>{hetu}</hetu>
      <sukunimi>Hetullinen</sukunimi>
      <etunimet>Juha Jaakko</etunimet>
      <kutsumanimi>Juha</kutsumanimi>
      {<todistukset/>.copy(child = todistukset)}
    </henkilo>

  def henkiloOidilla(todistuksetGen:DataGen[Elem]*): DataGen[Elem] = for (
    todistukset <- DataGen.combine[Elem](todistuksetGen);
    oid <- DataGen.henkiloOid
  ) yield <henkilo>
      <oppijanumero>{oid}</oppijanumero>
      <sukunimi>Hetullinen</sukunimi>
      <etunimet>Juha Jaakko</etunimet>
      <kutsumanimi>Juha</kutsumanimi>
      {<todistukset/>.copy(child = todistukset)}
    </henkilo>

  def hetutonHenkilo(todistuksetGen:DataGen[Elem]*)  = for (
    todistukset <- DataGen.combine[Elem](todistuksetGen);
    tunniste <- DataGen.uuid;
    (pv, kk, vuosi) <- DataGen.paiva
  ) yield <henkilo>
      <henkiloTunniste>{tunniste}</henkiloTunniste>
      <syntymaAika>{"%02d".format(vuosi)}-{"%02d".format(kk)}-{"%02d".format(pv)}</syntymaAika>
      <sukunimi>Tunnisteellinen</sukunimi>
      <etunimet>Juha Jaakko</etunimet>
      <kutsumanimi>Juha</kutsumanimi>
      {<todistukset/>.copy(child = todistukset)}
    </henkilo>

  def siirto(henkiloGens:DataGen[Elem]*): DataGen[Elem] =  for (
    henkilot <- DataGen.combine[Elem](henkiloGens)
  ) yield <arvosanat xmlns:p="http://service.koodisto.sade.vm.fi/types/koodisto"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:noNamespaceSchemaLocation="arvosanat.xsd">
      <eranTunniste>PKERA3_2015S_05127</eranTunniste>
      {<henkilot/>.copy(child = henkilot)}
    </arvosanat>


}
