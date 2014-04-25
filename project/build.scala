import info.schleichardt.sbt.sonar.SbtSonarPlugin._
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import sbt._
import sbt.Keys._
import org.scalatra.sbt._
import com.mojolly.scalate.ScalatePlugin._
import sbt.testing.{Status, TestSelector, Event}
import scala.collection.concurrent.TrieMap
import scala.compat.Platform
import scala.Some
import scala.xml._
import ScalateKeys._

case class Attr(name:String, text:String) extends UnprefixedAttribute(name, Text(text),Null)

class SurefireListener(targetDir:File) extends TestReportListener {

  val startTimes: scala.collection.concurrent.Map[String,Long] = TrieMap()
  val details: scala.collection.concurrent.Map[String,Seq[Event]] = TrieMap()

  val baseDir = new File(targetDir,"surefire-reports")
  baseDir.mkdir()

  def createResults(name:String, result: TestResult.Value) = {
    val time = ((Platform.currentTime - startTimes(name))/1000f).toString
    val statuses: Seq[Status] = details(name).map(d => (d.selector(), d.status())).collect{case (s:TestSelector, status) => status}
    val tests = statuses.length.toString

    val skipped = statuses.filter(Set(Status.Skipped, Status.Pending, Status.Ignored).contains(_)).length.toString
    val errors = statuses.filter(_ eq Status.Error).length.toString
    val failures = statuses.filter(_ eq Status.Failure).length.toString
    import sbt.testing.TestSelector
    val testDetails: Seq[Elem] = details(name).map(detail => (detail.fullyQualifiedName, detail.selector, detail.duration)).collect{case (classname, sel:TestSelector, duration) => <testcase/> % Attr("name",sel.testName) % Attr("classname", classname) % Attr("time",(duration/1000f).toString)}
    (<testsuite/> %
      Attr("name",name) %
      Attr("tests",tests) %
      Attr("skipped", skipped) %
      Attr("errors", errors) %
      Attr("time", time) %
      Attr("failures", failures)).copy(child = testDetails)
  }


  override def endGroup(name: String, result: TestResult.Value):Unit = {
    IO.write(new File(baseDir, "TEST-" + name + ".xml"), createResults(name, result).toString, Charset.forName("UTF-8"))

  }

  override def endGroup(name: String, t: Throwable): Unit = {}



  override def testEvent(event: TestEvent): Unit = event.detail.headOption.foreach(e => details.put(e.fullyQualifiedName(), event.detail))

  override def startGroup(name: String): Unit = startTimes.put(name, Platform.currentTime)
}

object HakuJaValintarekisteriBuild extends Build {
  val Organization = "fi.vm.sade"
  val Name = "Haku- ja valintarekisteri"
  val Version = "LATEST-SNAPSHOT"
  val ScalaVersion = "2.10.3"
  val ScalatraVersion = "2.2.2"
  val SpringVersion = "3.2.1.RELEASE"

  val ScalatraStack = Seq(
    "org.scalatra" %% "scalatra",
    "org.scalatra" %% "scalatra-scalate",
    "org.scalatra" %% "scalatra-json",
    "org.scalatra" %% "scalatra-swagger",
    "org.scalatra" %% "scalatra-commands")

  val SpringStack = Seq(
    "org.springframework" % "spring-web" ,
    "org.springframework" % "spring-context" ,
    "org.springframework" % "spring-context-support",
    "org.springframework.security" % "spring-security-web" ,
    "org.springframework.security" % "spring-security-config",
    "org.springframework.security" % "spring-security-ldap" ,
    "org.springframework.security" % "spring-security-cas"

    )

  val SecurityStack = SpringStack.map(_ % SpringVersion) ++
    Seq("net.sf.ehcache" % "ehcache-core" % "2.5.0",
    "fi.vm.sade.generic" % "generic-common" % "9.0-SNAPSHOT",
    "org.apache.httpcomponents" % "httpclient" % "4.2.3",
    "org.apache.httpcomponents" % "httpclient-cache" % "4.2.3",
    "commons-httpclient" % "commons-httpclient" % "3.1",
    "commons-io" % "commons-io" % "2.4",
    "com.google.code.gson" % "gson" % "2.2.4",
    "org.jgroups"  % "jgroups" % "2.10.0.GA",
    "net.sf.ehcache" % "ehcache-jgroupsreplication" % "1.5",
    "org.jasig.cas" % "cas-client-support-distributed-ehcache" % "3.1.10" exclude("net.sf.ehcache", "ehcache"))

  val dependencies = Seq(
    "org.slf4j" % "slf4j-api" % "1.6.1",
    "org.eclipse.jetty" % "jetty-webapp" % "8.1.8.v20121106" % "container",
    "org.json4s" %% "json4s-jackson" % "3.2.4",
    "com.typesafe.akka" %% "akka-testkit" %  "2.2.3",
    "com.typesafe.akka" %% "akka-slf4j" % "2.2.3",
    "com.github.nscala-time" %% "nscala-time" % "0.8.0",
    "com.typesafe.slick" %% "slick" % "2.0.0",
    "com.h2database" % "h2" % "1.3.174",
    "org.postgresql" % "postgresql" % "9.3-1100-jdbc4",
    "net.databinder.dispatch" %% "dispatch-core" % "0.11.0",
    "com.stackmob" %% "newman" % "1.3.5",
    "info.folone" %% "poi-scala" % "0.9"
  )

  val testDependencies = Seq("org.scalatra" %% "scalatra-scalatest" % ScalatraVersion)

  lazy val mocha = taskKey[Unit]("run mocha tests")

  lazy val installMocha = taskKey[Unit]("install mocha")

  lazy val installCoffee = taskKey[Unit]("install mocha")

  val installMochaTask = installMocha := {
    import sys.process._
    val pb = Seq("npm", "install",  "mocha")
    if ((pb!) !=  0)
      sys.error("failed installing mocha")
  }

  val installCoffeeTask = installCoffee := {
    import sys.process._
    val pb = Seq("npm", "install",  "coffee-script")
    if ((pb!) !=  0)
      sys.error("failed installing coffee script")
  }

  val mochaTask = mocha <<= (installMocha, installCoffee) map {
    (Unit1, Unit2) =>
      import sys.process._
      val test_dir = "src/test/coffee/"
      if (file(test_dir).exists()) {
        val pb = Seq("./node_modules/mocha/bin/mocha", "--compilers", "coffee:coffee-script", test_dir)
        if ((pb!) !=  0)
          sys.error("mocha failed")
      } else {
        println("no mocha tests found")
      }
  }

  val cleanNodeModules = cleanFiles <+= baseDirectory { base => base / "node_modules" }

  val mochaTestSources =  unmanagedSourceDirectories in Test <+= (sourceDirectory in Test) {sd => sd / "coffee"}

  val artifactoryPublish = publishTo <<= version apply {
    (ver: String) =>
      val artifactory = "http://penaali.hard.ware.fi/artifactory"
      if (ver.trim.endsWith("SNAPSHOT"))
        Some("snapshots" at artifactory + "/oph-sade-snapshot-local")
      else
        Some("releases" at artifactory + "/oph-sade-release-local")
  }

  lazy val buildversion = taskKey[Unit]("start buildversion.txt generator")

  val surefire = testListeners += new SurefireListener(target.value)

  val buildversionTask = buildversion <<= version map {
    (ver: String) =>
      val now: String = new SimpleDateFormat("yyyyMMdd-HHmm").format(new Date())
      val buildversionTxt: String = "artifactId=suoritusrekisteri\nversion=" + ver +
        "\nbuildNumber=" + sys.props.getOrElse("buildNumber", "N/A") +
        "\nbranchName=" + sys.props.getOrElse("branchName", "N/A") +
        "\nvcsRevision=" + sys.props.getOrElse("revisionNumber", "N/A") +
        "\nbuildTtime=" + now
      println("writing buildversion.txt:\n" + buildversionTxt)

      val f: File = file("src/main/webapp/buildversion.txt")
      IO.write(f, buildversionTxt)
  }



  val sonar =  sonarSettings ++ Seq(sonarProperties := sonarProperties.value ++
    Map("sonar.host.url" -> "http://pulpetti.hard.ware.fi:9000/sonar",
      "sonar.jdbc.url" -> "jdbc:mysql://pulpetti.hard.ware.fi:3306/sonar?useUnicode=true&amp;characterEncoding=utf8",
      "sonar.jdbc.username" -> "sonar",
      "sonar.jdbc.password" -> sys.env.getOrElse("SONAR_PASSWORD", "sonar"),
      "sonar.projectKey" -> "fi.vm.sade.hakurekisteri:hakurekisteri",
      "sonar.language" -> "scala",
      "sonar.surefire.reportsPath" -> (target.value.getAbsolutePath + "/surefire-reports"),
      "sonar.sources" -> "src/main/scala",
      "sonar.tests" -> "src/test/scala",
      "sonar.binaries" -> "target/scala-2.10/classes"))


  lazy val project = {
    Project(
      "hakurekisteri",
      file("."),
      settings = Defaults.defaultSettings ++ ScalatraPlugin.scalatraWithJRebel ++ scalateSettings
        ++ org.scalastyle.sbt.ScalastylePlugin.Settings
        ++ Seq(unmanagedSourceDirectories in Compile <+= (sourceDirectory in Runtime) { sd => sd / "js"})
        ++ Seq(com.earldouglas.xsbtwebplugin.PluginKeys.webappResources in Compile <+= (sourceDirectory in Runtime)(sd => sd / "js"))
        ++ Seq(mochaTask, installMochaTask, installCoffeeTask, cleanNodeModules, mochaTestSources)
        ++ Seq(
          organization := Organization,
          name := Name,
          version := Version,
          scalaVersion := ScalaVersion,
          resolvers += Classpaths.typesafeReleases,
          resolvers += "oph-snapshots" at "http://penaali.hard.ware.fi/artifactory/oph-sade-snapshot-local",
          resolvers += "oph-releases" at "http://penaali.hard.ware.fi/artifactory/oph-sade-release-local",
          resolvers += "Sonatype" at "http://oss.sonatype.org/content/repositories/releases/",
          credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
          artifactoryPublish,
          buildversionTask,
          libraryDependencies ++= Seq("org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "container;provided;test" artifacts Artifact("javax.servlet", "jar", "jar"))
            ++ ScalatraStack.map(_ % ScalatraVersion)
            ++ SecurityStack
            ++ dependencies
            ++ testDependencies.map((m) => m % "test"),
          scalateTemplateConfig in Compile <<= (sourceDirectory in Compile) {
            base =>
              Seq(
                TemplateConfig(
                  base / "webapp" / "WEB-INF" / "templates",
                  Seq.empty, /* default imports should be added here */
                  Seq(
                    Binding("context", "_root_.org.scalatra.scalate.ScalatraRenderContext", importMembers = true, isImplicit = true)
                  ), /* add extra bindings here */
                  Some("templates")
                )
              )
          }
        )
        ++ sonar
        ++ Seq(surefire))
  }
}


