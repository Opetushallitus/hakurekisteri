package siirto

import scalaz._
import org.xml.sax.SAXParseException
import scala.xml.Elem
import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.matchers.{MatchResult, Matcher}
import generators.DataGen

/**
 * Created by verneri on 27.3.15.
 */
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




  val succeed =
    Matcher { (left: Validation[Any,Any]) =>
      MatchResult(
        left.isSuccess,
        left + " failed",
        left + " succeeded"
      )
    }


  val perusopetus: DataGen[Elem] = DataGen.always(<perusopetus>
    <myontaja>05127</myontaja>
    <suorituskieli>FI</suorituskieli>
    <valmistuminen>2001-01-01</valmistuminen>
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
  </perusopetus>)

  val jaaLuokalle = DataGen.always(<perusopetus>
    <myontaja>05127</myontaja>
    <suorituskieli>FI</suorituskieli>
    <oletettuvalmistuminen>2016-06-01</oletettuvalmistuminen>
    <valmistuminensiirtyy>JAA LUOKALLE</valmistuminensiirtyy>
  </perusopetus>)

  val keskeyttanytPerusopetuksen = DataGen.always(
    <perusopetus>
      <myontaja>05127</myontaja>
      <suorituskieli>FI</suorituskieli>
      <valmistuminen>2015-06-01</valmistuminen>
      <eivalmistu>PERUSOPETUS PAATTYNYT VALMISTUMATTA</eivalmistu>
    </perusopetus>

  )

  val lisaopetus = for (
    perusopetusTodistus <- perusopetus
  ) yield perusopetusTodistus.copy(label = "perusopetuksenlisaopetus")

  def henkilo(todistuksetGen:DataGen[Elem]*): DataGen[Elem] = for (
    todistukset <- DataGen.combine[Elem](todistuksetGen)
  ) yield <henkilo>
      <oppijanumero>1.2.246.562.24.12345678901</oppijanumero>
      <sukunimi>Oppijanumerollinen</sukunimi>
      <etunimet>Juha Jaakko</etunimet>
      <kutsumanimi>Jaakko</kutsumanimi>
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
