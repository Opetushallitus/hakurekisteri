import com.bowlingx.sbt.plugins.Wro4jPlugin._
import Wro4jKeys._
import info.schleichardt.sbt.sonar.SbtSonarPlugin._
import java.text.SimpleDateFormat
import java.util.Date
import sbt._
import sbt.Keys._
import org.scalatra.sbt._
import com.mojolly.scalate.ScalatePlugin._
import ScalateKeys._
import org.sbtidea.SbtIdeaPlugin._
import scala.language.postfixOps


object HakuJaValintarekisteriBuild extends Build {
  val Organization = "fi.vm.sade"
  val Name = "hakurekisteri"
  val Version = "13.1-SNAPSHOT"
  val ScalaVersion = "2.11.2"
  val ArtifactName = (s: ScalaVersion, m: ModuleID, a: Artifact) => s"${a.name}-${m.revision}.${a.extension}"
  val ScalatraVersion = "2.3.0"
  val SpringVersion = "3.2.1.RELEASE"

  lazy val LoadSpecs = config("load") extend Test

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
    "fi.vm.sade.generic" % "generic-common" % "9.1-SNAPSHOT",
    "org.apache.httpcomponents" % "httpclient" % "4.3.4",
    "org.apache.httpcomponents" % "httpclient-cache" % "4.3.4",
    "commons-httpclient" % "commons-httpclient" % "3.1",
    "commons-io" % "commons-io" % "2.4",
    "com.google.code.gson" % "gson" % "2.2.4",
    "org.jgroups"  % "jgroups" % "2.10.0.GA",
    "net.sf.ehcache" % "ehcache-jgroupsreplication" % "1.5",
    "org.jasig.cas" % "cas-client-support-distributed-ehcache" % "3.1.10" exclude("net.sf.ehcache", "ehcache"))

  val akkaVersion = "2.3.6"
  val AkkaStack = Seq("akka-testkit", "akka-slf4j","akka-camel").map("com.typesafe.akka" %% _ % akkaVersion)


  val dependencies = Seq(
    "org.slf4j" % "slf4j-api" % "1.6.1",
    "org.eclipse.jetty" % "jetty-webapp" % "9.1.5.v20140505" % "container",
    "org.eclipse.jetty" % "jetty-plus" % "9.1.5.v20140505" % "container",
    "javax.servlet" % "javax.servlet-api" % "3.1.0",
    "org.json4s" %% "json4s-jackson" % "3.2.10",
    "com.github.nscala-time" %% "nscala-time" % "1.4.0",
    "com.typesafe.slick" %% "slick" % "2.1.0",
    "com.h2database" % "h2" % "1.3.176",
    "org.postgresql" % "postgresql" % "9.3-1100-jdbc4",
    "net.databinder.dispatch" %% "dispatch-core" % "0.11.0",
    "org.apache.poi" % "poi" % "3.10.1",
    "org.apache.poi" % "poi-ooxml" % "3.10.1",
    "org.apache.activemq" % "activemq-all" % "5.9.1",
    "org.apache.camel" % "camel-jms" % "2.13.0",
    "fi.vm.sade.log" % "log-client" % "7.0",
    "fr.janalyse" %% "janalyse-ssh" % "0.9.14"
  )

  val testDependencies = Seq("org.scalatra" %% "scalatra-scalatest" % ScalatraVersion,
                             "org.scalamock" %% "scalamock-scalatest-support" % "3.1.4")

  lazy val karma = taskKey[Unit]("run karma tests")
  lazy val installKarma = taskKey[Unit]("install karma")
  lazy val installCoffee = taskKey[Unit]("install coffee")
  val installKarmaTask = installKarma := {
    import sys.process._
    val pb = Seq("npm", "install", "phantomjs", "karma", "karma-coffee-preprocessor", "karma-phantomjs-launcher", "karma-requirejs", "karma-jasmine", "karma-junit-reporter")
    if ((pb!) !=  0)
      sys.error("failed installing karma")
  }
  val installCoffeeTask = installCoffee := {
    import sys.process._
    val pb = Seq("npm", "install", "coffee-script")
    if ((pb!) !=  0)
      sys.error("failed installing coffee script")
  }
  val karmaTask = karma <<= (installCoffee, installKarma) map {
    (Unit1, Unit2) =>
      import sys.process._
      val karma_conf = "src/test/js/karma.conf.js"
      if (file(karma_conf).exists()) {
        val pb = Seq("./node_modules/karma/bin/karma", "start", karma_conf)
        if ((pb!) !=  0)
          sys.error("karma failed")
      } else {
        println("no karma tests found")
      }
  }

  val cleanNodeModules = cleanFiles <+= baseDirectory { base => base / "node_modules" }
  val karmaTestSources = unmanagedSourceDirectories in Test <+= (sourceDirectory in Test) {sd => sd / "coffee"}

  val artifactoryPublish = publishTo <<= version apply {
    (ver: String) =>
      val artifactory = "https://artifactory.oph.ware.fi/artifactory"
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

  val scalac = Seq(scalacOptions ++= Seq( "-deprecation", "-unchecked", "-feature" ))

  val sonar =  sonarSettings ++ Seq(sonarProperties := sonarProperties.value ++
    Map("sonar.host.url" -> "http://pulpetti.hard.ware.fi:9000/sonar",
      "sonar.jdbc.url" -> "jdbc:mysql://pulpetti.hard.ware.fi:3306/sonar?useUnicode=true&amp;characterEncoding=utf8",
      "sonar.jdbc.username" -> "sonar",
      "sonar.jdbc.password" -> sys.env.getOrElse("SONAR_PASSWORD", "sonar"),
      "sonar.projectKey" -> "fi.vm.sade.hakurekisteri:hakurekisteri",
      "sonar.language" -> "scala",
      "sonar.surefire.reportsPath" -> (target.value.getAbsolutePath + "/surefire-reports"),
      "sonar.dynamicAnalysis" -> "reuseReports",
      "sonar.core.codeCoveragePlugin" -> "cobertura",
      "sonar.java.coveragePlugin"  -> "cobertura",
      "sonar.cobertura.reportPath" -> (target.value.getAbsolutePath +"/scala-" +scalaBinaryVersion.value + "/coverage-report/cobertura.xml")))

  import com.earldouglas.xsbtwebplugin.PluginKeys._

  lazy val project = {
    Project(
      "hakurekisteri",
      file("."),
      configurations = Seq(LoadSpecs),
      settings = ScalatraPlugin.scalatraWithJRebel ++ scalateSettings
        ++ inConfig(LoadSpecs)(Defaults.testSettings)
        ++ Seq(ideaExtraTestConfigurations := Seq(LoadSpecs))
        ++ org.scalastyle.sbt.ScalastylePlugin.Settings
        ++ Seq(scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature"))
        ++ Seq(wro4jSettings:_*)
        ++ Seq(compile in Compile <<= (compile in Compile) dependsOn (generateResources in Compile))
        ++ Seq(webappResources in Compile <+= (targetFolder in generateResources in Compile))
        ++ Seq(webappResources in Compile <+= (sourceDirectory in Runtime) { sd => sd / "resources" / "tiedonsiirto"})
        ++ Seq(karmaTask, installKarmaTask, installCoffeeTask, cleanNodeModules, karmaTestSources)
        //++ Seq((test in Test) <<= (test in Test) dependsOn karma) // uncomment to enable running karma tests together with "test" phase
        ++ Seq(
          organization := Organization,
          name := Name,
          version := Version,
          scalaVersion := ScalaVersion,
          artifactName := ArtifactName,
          resolvers += Classpaths.typesafeReleases,
          resolvers += "oph-snapshots" at "https://artifactory.oph.ware.fi/artifactory/oph-sade-snapshot-local",
          resolvers += "oph-releases" at "https://artifactory.oph.ware.fi/artifactory/oph-sade-release-local",
          resolvers += "Sonatype" at "http://oss.sonatype.org/content/repositories/releases/",
          resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
          resolvers += "JAnalyse Repository" at "http://www.janalyse.fr/repository/",
          credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
          artifactoryPublish,
          buildversionTask,
          libraryDependencies ++=  ScalatraStack.map(_ % ScalatraVersion)
            ++ SecurityStack
            ++ AkkaStack
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
        ++ Seq(surefire)).settings(net.virtualvoid.sbt.graph.Plugin.graphSettings: _*)

  }
}


