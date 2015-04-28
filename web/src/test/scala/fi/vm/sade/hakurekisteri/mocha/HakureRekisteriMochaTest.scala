package fi.vm.sade.hakurekisteri.mocha

import fi.vm.sade.hakurekisteri.SharedJetty
import org.scalatest.{FlatSpec, Matchers}

import scala.language.postfixOps
import scala.sys.process._

class HakureRekisteriMochaTest extends FlatSpec with Matchers {
  "Mocha tests" should "pass" in {
    SharedJetty.start
    val pb = Seq("node_modules/mocha-phantomjs/bin/mocha-phantomjs", "-R", "spec", "http://localhost:" + SharedJetty.port + "/test/runner.html?grep=Opiskelijatiedot%20Tietojen%20muokkaus%20Peruskoulun%20suoritus%20Peruskoulun%20suoritustiedot%20(ja%20arvosanat)%20talletetaan%20vain%20jos%20muuttuneita%20arvoja")
    val res = pb.!
    res should be(0)
  }
}
