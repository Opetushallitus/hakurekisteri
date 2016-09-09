package fi.vm.sade.hakurekisteri.tools

import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvio410, Arvosana}
import org.scalatra.test.scalatest.ScalatraFunSuite

class DiffSpec extends ScalatraFunSuite {

  test("diff between equal arvosanat should be empty") {
    val a1 = Arvosana(null, Arvio410("4"), "A",None,true,None,"jokukomo", Map.empty, Some(1))
    val a2 = Arvosana(null, Arvio410("4"), "A",None,true,None,"jokukomo", Map.empty, Some(1))

    Diff[Arvosana].find(a1, a2) should equal (Map.empty)
  }
  test("diff between arvosanat with different arvio") {
    val a1 = Arvosana(null, Arvio410("4"), "A",None,true,None,"jokukomo", Map.empty, Some(1))
    val a2 = Arvosana(null, Arvio410("5"), "A",None,true,None,"jokukomo", Map.empty, Some(1))

    Diff[Arvosana].find(a1, a2) should equal (Map("arvio" -> (Arvio410("4"),(Arvio410("5")))))
  }

  test("diff between arvosanat with multiple differences") {
    val a1 = Arvosana(null, Arvio410("4"), "A",None,true,None,"jokukomo", Map.empty, Some(1))
    val a2 = Arvosana(null, Arvio410("4"), "B",None,true,None,"jokukomo", Map("jee" -> "jee"), Some(1))

    Diff[Arvosana].find(a1, a2) should equal (Map("aine" -> ("A","B"), "lahdeArvot" -> (Map(),Map("jee" -> "jee"))))
  }
}
