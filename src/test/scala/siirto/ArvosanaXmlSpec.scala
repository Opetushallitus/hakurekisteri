package siirto

import fi.vm.sade.hakurekisteri.suoritus.DayFinder
import generators.{DataGen, DataGeneratorSupport}
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.{FlatSpec, Matchers}
import scalaz._

import scala.xml.Elem

class ArvosanaXmlSpec extends FlatSpec with Matchers with DataGeneratorSupport {

  behavior of "Arvosana Xml Validation"

  implicit val schemas = NonEmptyList(ArvosanatV2, ArvosanatKoodisto)

  it should "mark valid perusopetuksen todistus as valid" in {
    siirto(
      henkilo(
        perusopetus
      )
    ) should abideSchemas
  }

  it should "mark luokalle jaava as valid" in {
    siirto(
      henkilo(
        jaaLuokalle
      )
    ) should not (abideSchemas)
  }

  it should "mark perusopetuksen keskettanyt as valid" in {
    siirto(
      henkilo(
        keskeyttanytPerusopetuksen
      )
    ) should abideSchemas

  }

  it should "mark lisaopetuksen kaynyt as valid" in {
    siirto(
      henkilo(
        lisaopetus
      )
    ) should abideSchemas
  }

  it should "mark lisaopetuksen keskeyttanyt as valid" in {
    siirto(
      henkilo(
        lisaopetuksenKeskeyttanyt
      )
    ) should abideSchemas
  }

  it should "mark ammattistartin kaynyt as valid" in {
    siirto(
      henkilo(
        ammattistartti
      )
    ) should abideSchemas
  }

  it should "mark ammattistartin keskeyttanyt as valid" in {
    siirto(
      henkilo(
        ammattistartinKeskeyttanyt
      )
    ) should abideSchemas
  }

  it should "mark valmentavan kaynyt as valid" in {
    siirto(
      henkilo(
        valmentava
      )
    ) should abideSchemas
  }

  it should "mark valmentavan keskeyttanyt as valid" in {
    siirto(
      henkilo(
        valmentavanKeskeyttanyt
      )
    ) should abideSchemas
  }

  it should "mark maahanmuuttajien lukioonvalmistavan kaynyt as valid" in {
    siirto(
      henkilo(
        maahanmuuttajienLukioonValmistava
      )
    ) should abideSchemas
  }

  it should "mark maahanmuuttajien lukioonvalmistavan keskeyttanyt as valid" in {
    siirto(
      henkilo(
        maahanmuuttajienLukioonValmistavanKeskeyttanyt
      )
    ) should abideSchemas
  }


  it should "mark maahanmuuttajien ammattiin valmistavan kaynyt as valid" in {
    siirto(
      henkilo(
        maahanmuuttajienAmmValmistava
      )
    ) should abideSchemas
  }

  it should "mark maahanmuuttajien ammattiin valmistavan keskeyttanyt as valid" in {
    siirto(
      henkilo(
        maahanmuuttajienLukioonValmistavanKeskeyttanyt
      )
    ) should abideSchemas
  }

  it should "mark valman kaynyt as valid" in {
    siirto(
      henkilo(
        valma
      )
    ) should abideSchemas
  }

  it should "mark telman kaynyt as valid" in {
    siirto(
      henkilo(
        telma
      )
    ) should abideSchemas
  }

  it should "mark perusopetuksen, valman & telman kaynyt as valid" in {
    siirto(
      henkilo(
        perusopetus,
        valma,
        telma
      )
    ) should abideSchemas
  }

  it should "mark lukion & valman kaynyt as invalid" in {
    siirto(
      henkilo(
        lukio,
        valma
      )
    ) should not (abideSchemas)
  }

  it should "mark lukion kaynyt as valid" in {
    siirto(
      henkilo(
        lukio
      )
    ) should abideSchemas
  }

  it should "mark ammattikoulun kaynyt as valid" in {
    siirto(
      henkilo(
        ammattikoulu
      )
    ) should abideSchemas
  }

  it should "mark ulkomaalaisen korvaavn kaynyt as valid" in {
    siirto(
      hetutonHenkilo(
        ulkomainenKorvaava
      )
    ) should abideSchemas
  }

  it should "mark syntyma-ajallinen henkilo as valid" in {
    siirto(
      hetutonHenkilo(
        perusopetus
      )
    ) should abideSchemas
  }

  it should "mark oidillinen henkilo as valid" in {
    siirto(
      henkiloOidilla(
        perusopetus
      )
    ) should abideSchemas
  }

  it should "mark perusopetus with date after saturday of week 22 as invalid" in {
    val dayAfter = DayFinder.saturdayOfWeek22(LocalDate.now().getYear).plusDays(1)
    siirto(
      henkiloOidilla(
        perusopetus(DateTimeFormat.forPattern("dd.MM.yyyy").print(dayAfter))
      )
    ) should not (abideSchemas)
  }

  it should "mark luokalle jaanyt with date before 1.8. as invalid" in {
    val day = LocalDate.now().withMonthOfYear(7).withDayOfMonth(15)
    siirto(
      henkiloOidilla(
        jaaLuokalle(DateTimeFormat.forPattern("dd.MM.yyyy").print(day))
      )
    ) should not (abideSchemas)
  }


  def abide(schemaDoc: SchemaDefinition, imports: SchemaDefinition*) = Matcher { (left: Elem) =>
    val validator = new ValidXml(schemaDoc, imports:_*)
    val validation: scalaz.ValidationNel[(String, _root_.scala.xml.SAXParseException), Elem] = validator.validate(left)
    val messages = validation.swap.toOption.map(_.list.toList).getOrElse(List()).mkString(", ")
    MatchResult(
      {
        validation.isSuccess
      },
      left + s" does not abide to schemas exceptions: $messages",
      left + " abides schemas"
    )
  }

  def abideSchemas(implicit schemas: NonEmptyList[SchemaDefinition]) = {
    abide(schemas.head, schemas.tail.toList:_*)
  }


  def suoritus(name:String): DataGen[Elem] = DataGen.always(
    <suoritus>
      <myontaja>05127</myontaja>
      <suorituskieli>FI</suorituskieli>
    </suoritus>.copy(label = name)
  )

  def valmistunutSuoritus(name:String, date: LocalDate = new LocalDate(2015, 5,30)): DataGen[Elem] = for (
    baseSuoritus <- suoritus(name)
  ) yield baseSuoritus.copy(child = baseSuoritus.child ++ <valmistuminen>{date.toString("yyyy-MM-dd")}</valmistuminen>)

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

  def perusopetus: DataGen[Elem] = perusopetus("30.5.2015")

  val suomalainenPaivays = DateTimeFormat.forPattern("dd.MM.yyyy")

  def perusopetus(paiva:String) =  for (
    perusopetusBase <- valmistunutSuoritus("perusopetus", LocalDate.parse(paiva, suomalainenPaivays));
    aineet <- perusopetuksenAineet
  ) yield perusopetusBase.copy(child = perusopetusBase.child ++ aineet)


  def jaaLuokalleLisaTiedot(paiva: LocalDate = LocalDate.now().withMonthOfYear(5).withDayOfMonth(15).plusYears(1)) =
    DataGen.always(<perusopetus>
      <oletettuvalmistuminen>{paiva.toString("yyyy-MM-dd")}</oletettuvalmistuminen>
      <valmistuminensiirtyy>JAA LUOKALLE</valmistuminensiirtyy>
    </perusopetus>.child)

  def jaaLuokalle: DataGen[Elem] = jaaLuokalle(DateTimeFormat.forPattern("dd.MM.yyyy").print(DayFinder.saturdayOfWeek22(LocalDate.now().getYear + 1)))

  def jaaLuokalle(paiva:String): DataGen[Elem] = for(
    perusopetusBase <- suoritus("perusopetus");
    jaaLuokalleTiedot <- jaaLuokalleLisaTiedot(LocalDate.parse(paiva, suomalainenPaivays))

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

  val valma = convertPerusopetus("valma")

  val telma = convertPerusopetus("telma")

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
    syntymaPaiva <- DataGen.paiva
  ) yield <henkilo>
      <henkiloTunniste>{tunniste}</henkiloTunniste>
      <syntymaAika>{syntymaPaiva.toString("yyyy-MM-dd")}</syntymaAika>
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
