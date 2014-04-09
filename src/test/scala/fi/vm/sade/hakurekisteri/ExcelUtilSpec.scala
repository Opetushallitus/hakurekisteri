package fi.vm.sade.hakurekisteri

import org.scalatra.test.scalatest.ScalatraFunSuite
import fi.vm.sade.hakurekisteri.hakija._
import java.io.{BufferedOutputStream, OutputStream, ByteArrayOutputStream}

class ExcelUtilSpec extends ScalatraFunSuite {

  test("write as excel should return non zero length result") {
    val hakijat = XMLHakijat(Seq(XMLHakija(
      hetu = "",
      oppijanumero = "1.1",
      sukunimi = "A",
      etunimet = "B",
      kutsumanimi = None,
      lahiosoite = "K",
      postinumero = "00000",
      maa = "246",
      kansalaisuus = "246",
      matkapuhelin = None,
      muupuhelin = None,
      sahkoposti = None,
      kotikunta = None,
      sukupuoli = "1",
      aidinkieli = "FI",
      koulutusmarkkinointilupa = false,
      XMLHakemus(
        vuosi = "2014",
        kausi = "S",
        hakemusnumero = "1.2",
        lahtokoulu = None,
        lahtokoulunnimi = None,
        luokka = None,
        luokkataso = None,
        pohjakoulutus = "1",
        todistusvuosi = Some("2014"),
        julkaisulupa = None,
        yhteisetaineet = None,
        lukiontasapisteet = None,
        lisapistekoulutus = None,
        yleinenkoulumenestys = None,
        painotettavataineet = None,
        hakutoiveet = Seq(XMLHakutoive(
          hakujno = 1,
          oppilaitos = "00999",
          opetuspiste = None,
          opetuspisteennimi = None,
          koulutus = "900",
          harkinnanvaraisuusperuste = None,
          urheilijanammatillinenkoulutus = None,
          yhteispisteet = None,
          valinta = None,
          vastaanotto = None,
          lasnaolo = None,
          terveys = None,
          aiempiperuminen = None,
          kaksoistutkinto = None
        ))
      )
    )))

    val out: ByteArrayOutputStream = new ByteArrayOutputStream()
    ExcelUtil.write(out, hakijat)

    println("out size: " + out.size)

    out.size() should not equal(0)
  }

}
