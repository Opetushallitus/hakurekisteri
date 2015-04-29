package fi.vm.sade.hakurekisteri.mocha

import fi.vm.sade.hakurekisteri.{CleanSharedJetty, SharedJetty}
import org.scalatest.{FlatSpec, Matchers}

import scala.language.postfixOps
import scala.sys.process._

class HakureRekisteriMochaTest extends FlatSpec with CleanSharedJetty with Matchers {
  "Mocha tests" should "pass" in {
    val pb = Seq("node_modules/mocha-phantomjs/bin/mocha-phantomjs", "-R", "spec", "http://localhost:" + SharedJetty.port + "/test/runner.html")
    val res = pb.!
    res should be(0)
  }
}
