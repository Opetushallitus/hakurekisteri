package fi.vm.sade.hakurekisteri.integration

import fi.vm.sade.hakurekisteri.ProjectRootFinder
import org.scalatest.BeforeAndAfterEach

import java.io.File

trait LocalhostProperties extends BeforeAndAfterEach {
  this: LocalhostProperties with org.scalatest.Suite =>
  override def beforeEach() {
    super.beforeEach()
    OphUrlProperties.overrides.setProperty("baseUrl", "http://localhost")
  }

  override def afterEach() {
    super.afterEach()
    OphUrlProperties.overrides.clear()
  }

  val root: File = ProjectRootFinder.findProjectRoot()
  val jsonDir =
    root + "/suoritusrekisteri/src/test/scala/fi/vm/sade/hakurekisteri/integration/hakemus/json/"
  def getJson(testCase: String): String =
    scala.io.Source.fromFile(jsonDir + testCase + ".json").mkString

  def getJsonWithReplaces(testCase: String, replaces: Map[String, String]): String = {
    var result: String = scala.io.Source.fromFile(jsonDir + testCase + ".json").mkString
    replaces.foreach(o => result = result.replace(o._1, o._2))
    result
  }
}
