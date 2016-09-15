package fi.vm.sade.hakurekisteri.integration.ytl

import java.io.File

import fi.vm.sade.hakurekisteri.suoritus.{VirallinenSuoritus, yksilollistaminen}
import org.joda.time.LocalDate
import org.scalatra.test.scalatest.ScalatraFunSuite

class YtlDiffSpec extends ScalatraFunSuite {

  private def testKokelas(oid: String) = Kokelas(oid, VirallinenSuoritus("yo1", "myontaja", "VALMIS", LocalDate.now(), "henkilo", yksilollistaminen.Ei, "fi", None, vahv = true, "YTL"), None, Seq(), Seq())

  test("YtlActor should write JSON for diff purposes") {
    val filename = "ytl-diff-kokelaat.json"
    val kokelaat = Iterator(testKokelas("oid1"), testKokelas("oid2"))
    YtlDiff.writeKokelaatAsJson(kokelaat, filename)

    val expected =
      """[{
        |  "oid" : "oid1",
        |  "yo" : {
        |    "komo" : "yo1",
        |    "myontaja" : "myontaja",
        |    "tila" : "VALMIS",
        |    "valmistuminen" : { },
        |    "yksilollistaminen" : {
        |      "name" : "Ei"
        |    },
        |    "suoritusKieli" : "fi"
        |  },
        |  "yoTodistus" : [ ],
        |  "osakokeet" : [ ]
        |},{
        |  "oid" : "oid2",
        |  "yo" : {
        |    "komo" : "yo1",
        |    "myontaja" : "myontaja",
        |    "tila" : "VALMIS",
        |    "valmistuminen" : { },
        |    "yksilollistaminen" : {
        |      "name" : "Ei"
        |    },
        |    "suoritusKieli" : "fi"
        |  },
        |  "yoTodistus" : [ ],
        |  "osakokeet" : [ ]
        |}]""".stripMargin

    val actual = scala.io.Source.fromFile(filename).mkString
    actual should be(expected)
  }

}
