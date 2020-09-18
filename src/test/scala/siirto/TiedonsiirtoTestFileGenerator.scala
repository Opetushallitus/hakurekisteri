package siirto

import generators.{DataGen, DataGeneratorSupport}
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat

import scala.xml.Elem

class TiedonsiirtoTestFileGenerator extends DataGeneratorSupport {

  def arvosanaSuoritus(name: String, oppilaitosnumero: String): DataGen[Elem] = DataGen.always(
    <suoritus>
      <myontaja>{oppilaitosnumero}</myontaja>
      <suorituskieli>FI</suorituskieli>
    </suoritus>.copy(label = name)
  )

  def valmistunutArvosanaSuoritus(
    name: String,
    date: LocalDate = new LocalDate(2015, 5, 30),
    oppilaitosnumero: String
  ): DataGen[Elem] = for (baseSuoritus <- arvosanaSuoritus(name, oppilaitosnumero))
    yield baseSuoritus.copy(child = baseSuoritus.child ++ <valmistuminen>{
      date.toString("yyyy-MM-dd")
    }</valmistuminen>)

  val perusopetuksenAineet = DataGen.always(<aineet>
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

  def arvosanaPerusopetus(oppilaitosnumero: String): DataGen[Elem] =
    arvosanaPerusopetus("30.5.2015", oppilaitosnumero)

  val suomalainenPaivays = DateTimeFormat.forPattern("dd.MM.yyyy")

  def arvosanaPerusopetus(paiva: String, oppilaitosnumero: String) = for (
    perusopetusBase <- valmistunutArvosanaSuoritus(
      "perusopetus",
      LocalDate.parse(paiva, suomalainenPaivays),
      oppilaitosnumero
    );
    aineet <- perusopetuksenAineet
  ) yield perusopetusBase.copy(child = perusopetusBase.child ++ aineet)

  def convertArvosanaPerusopetus(label: String, oppilaitosnumero: String): DataGen[Elem] = {
    for (perusopetusTodistus <- arvosanaPerusopetus(oppilaitosnumero)) yield {

      perusopetusTodistus.copy(label = label)
    }
  }

  def lisaopetus(oppilaitosnumero: String) =
    convertArvosanaPerusopetus("perusopetuksenlisaopetus", oppilaitosnumero)

  def randomStringFromCharList(length: Int, chars: Seq[Char]): String = {
    val sb = new StringBuilder
    for (i <- 1 to length) {
      val randomNum = scala.util.Random.nextInt(chars.length)
      sb.append(chars(randomNum))
    }
    sb.toString()
  }

  def arvosanaHenkilo(
    henkilo: (String, String, String, String),
    todistuksetGen: DataGen[Elem]*
  ): DataGen[Elem] = for (todistukset <- DataGen.combine[Elem](todistuksetGen))
    yield <henkilo>
      <hetu>{henkilo._1}</hetu>
      <sukunimi>{henkilo._2}</sukunimi>
      <etunimet>{henkilo._3}</etunimet>
      <kutsumanimi>{henkilo._4}</kutsumanimi>
      {<todistukset/>.copy(child = todistukset)}
    </henkilo>

  def arvosanaSiirto(oppilaitosnumero: String, henkiloGens: DataGen[Elem]*): DataGen[Elem] = for (
    henkilot <- DataGen.combine[Elem](henkiloGens)
  )
    yield <arvosanat xmlns:p="http://service.koodisto.sade.vm.fi/types/koodisto"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:noNamespaceSchemaLocation="arvosanat.xsd">
      <eranTunniste>{s"PKERA3_2015S_$oppilaitosnumero"}</eranTunniste>
      {<henkilot/>.copy(child = henkilot)}
    </arvosanat>

  def perustiedotSiirto(oppilaitosnumero: String, henkiloGens: DataGen[Elem]*): DataGen[Elem] =
    for (henkilot <- DataGen.combine[Elem](henkiloGens))
      yield <perustiedot xmlns:p="http://service.koodisto.sade.vm.fi/types/koodisto"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:noNamespaceSchemaLocation="perusaste-henkilot.xsd">

      <eranTunniste>{s"PKERA1_2015S_$oppilaitosnumero"}</eranTunniste>
      {<henkilot/>.copy(child = henkilot)}
    </perustiedot>

  def perustiedotHenkilo(
    henkilo: (String, String, String, String),
    lahtokoulu: String,
    suorituksetGen: DataGen[Elem]*
  ): DataGen[Elem] = for (suoritukset <- DataGen.combine[Elem](suorituksetGen))
    yield <henkilo>
      <hetu>{henkilo._1}</hetu>
      <lahtokoulu>{lahtokoulu}</lahtokoulu>
      <luokka>{s"9${randomStringFromCharList(1, 'A' to 'G')}"}</luokka>
      <sukunimi>{henkilo._2}</sukunimi>
      <etunimet>{henkilo._3}</etunimet>
      <kutsumanimi>{henkilo._4}</kutsumanimi>
      <kotikunta>020</kotikunta>
      <aidinkieli>FI</aidinkieli>
      <kansalaisuus>246</kansalaisuus>
      <lahiosoite>Katu 1 A 1</lahiosoite>
      <postinumero>00100</postinumero>
      <matkapuhelin>040 1234 567</matkapuhelin>
      <muuPuhelin>09 1234 567</muuPuhelin>
      {suoritukset}
    </henkilo>

  def perustiedotPerusopetus(oppilaitosnumero: String): DataGen[Elem] = DataGen.always(
    <perusopetus>
      <valmistuminen>2015-06-04</valmistuminen>
      <myontaja>{oppilaitosnumero}</myontaja>
      <suorituskieli>FI</suorituskieli>
      <tila>KESKEN</tila>
      <yksilollistaminen>EI</yksilollistaminen>
    </perusopetus>
  )

  /**
    * Example:
    *
    * $ ./sbt "hakurekisteri-core/test:console"
    * scala> import siirto.TiedonsiirtoTestFileGenerator
    * scala> val x = new TiedonsiirtoTestFileGenerator()
    * scala> x.generateFiles("03096", 5000)
    *
    * Generates two files (PKERA1_2015S_{oppilaitosnumero}.xml and PKERA3_2015S_{oppilaitosnumero}.xml) with matching data.
    *
    * @param oppilaitosnumero
    * @param maara
    */
  def generateFiles(oppilaitosnumero: String, maara: Int): Unit = {
    var hetut: Set[String] = Set()
    while (hetut.size < maara) {
      hetut = hetut + DataGen.hetu.generate
    }
    val henkilot = hetut
      .map(hetu => {
        val etunimi = randomStringFromCharList(5, 'a' to 'z').capitalize
        (hetu, randomStringFromCharList(12, 'a' to 'z').capitalize, etunimi, etunimi)
      })
      .toSeq
    val perustiedot: DataGen[Elem] = perustiedotSiirto(
      oppilaitosnumero,
      henkilot.map(h =>
        perustiedotHenkilo(h, oppilaitosnumero, perustiedotPerusopetus(oppilaitosnumero))
      ): _*
    )
    val arvosanat: DataGen[Elem] = arvosanaSiirto(
      oppilaitosnumero,
      henkilot.map(h => arvosanaHenkilo(h, arvosanaPerusopetus(oppilaitosnumero))): _*
    )

    scala.xml.XML.save(s"PKERA1_2015S_$oppilaitosnumero.xml", perustiedot.generate)
    scala.xml.XML.save(s"PKERA3_2015S_$oppilaitosnumero.xml", arvosanat.generate)
  }

}
