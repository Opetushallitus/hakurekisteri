package fi.vm.sade.hakurekisteri.mocha

import fi.vm.sade.hakurekisteri.CleanSharedTestJettyBeforeEach
import org.scalatest.{FlatSpec, Matchers}

import scala.language.postfixOps
import scala.sys.process._

class HakureRekisteriMochaTest extends FlatSpec with CleanSharedTestJettyBeforeEach with Matchers {
  "Mocha tests" should "pass" in {
    val pb = Seq("node_modules/mocha-headless-chrome/bin/start", "-r", "spec", "-f", "http://localhost:" + port + "/test/runner.html")
    val res = pb.!
    res should be(0)
  }
}
