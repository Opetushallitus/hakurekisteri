package fi.vm.sade.hakurekisteri.mocha

import java.util.concurrent.TimeUnit.MINUTES
import fi.vm.sade.hakurekisteri.CleanSharedTestJettyBeforeEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.Duration
import scala.language.postfixOps
import scala.sys.process._

class HakureRekisteriMochaTest
    extends AnyFlatSpec
    with CleanSharedTestJettyBeforeEach
    with Matchers {
  private val totalMochaTestsMaxDuration: Duration = Duration(10, MINUTES)

  "Mocha tests" should "pass" in {
    val pb = Seq(
      "node_modules/mocha-headless-chrome/bin/start",
      "-t",
      totalMochaTestsMaxDuration.toMillis.toString,
      //"-v",
      "-f",
      "http://localhost:" + port + "/test/runner.html"
    )
    val res = pb.!
    res should be(0)
  }
}
