import com.mojolly.scalate.ScalatePlugin.ScalateKeys
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

object HakurekisteriBuild extends Build {
  import sys.process._

  val Organization = "fi.vm.sade"
  val Name = "hakurekisteri"
  val Version = "13.1-SNAPSHOT"
  val ScalaVersion = "2.11.2"
  val ArtifactName = (s: ScalaVersion, m: ModuleID, a: Artifact) => s"${a.name}-${m.revision}.${a.extension}"
  val ScalatraVersion = "2.3.0"
  val SpringVersion = "3.2.1.RELEASE"

  lazy val LoadSpecs = config("load") extend Test

  lazy val createTestDb = taskKey[Unit]("create h2 test db")



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

  val webDeps =  Seq(
    "org.eclipse.jetty" % "jetty-webapp" % "9.1.5.v20140505" % "container",
    "org.eclipse.jetty" % "jetty-plus" % "9.1.5.v20140505" % "container",
    "javax.servlet" % "javax.servlet-api" % "3.1.0"
  )

  val dependencies = Seq(
    "org.scalaz"              %% "scalaz-core"        % "7.0.6",
    "org.slf4j" % "slf4j-api" % "1.6.1",
    "org.json4s" %% "json4s-ast" % "3.2.10",
    "org.json4s" %% "json4s-core" % "3.2.10",
    "org.json4s" %% "json4s-ext" % "3.2.10",
    "org.json4s" %% "json4s-jackson" % "3.2.10",
    "com.github.nscala-time" %% "nscala-time" % "1.4.0",
    "com.typesafe.slick" %% "slick" % "2.1.0",
    "com.h2database" % "h2" % "1.3.176",
    "org.postgresql" % "postgresql" % "9.3-1100-jdbc4",
    "net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
    "org.apache.poi" % "poi" % "3.10.1",
    "org.apache.poi" % "poi-ooxml" % "3.10.1",
    "org.apache.activemq" % "activemq-all" % "5.9.1",
    "org.apache.camel" % "camel-jms" % "2.13.0",
    "fi.vm.sade.log" % "log-client" % "7.0",
    "fr.janalyse" %% "janalyse-ssh" % "0.9.14"
  )

  val testDependencies = Seq("org.scalatra" %% "scalatra-scalatest" % ScalatraVersion,
    "org.scalamock" %% "scalamock-scalatest-support" % "3.1.4",
    "com.storm-enroute" %% "scalameter" % "0.6")

  lazy val npmBuild = taskKey[Unit]("run npm build")
  val npmBuildTask = npmBuild := {
    if ((Seq("npm", "run", "build", "-s")!) !=  0)
      sys.error("npm run build failed")
  }

  lazy val karma = taskKey[Unit]("run karma tests")
  val karmaTask = karma <<= (sourceDirectory in Test) map {
    (sd) =>
      if ((Seq("npm", "test", "-s")!) !=  0)
        sys.error("npm test failed")
  }

  val cleanNodeModules = cleanFiles <+= baseDirectory { base => base / "node_modules" }

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

      val f: File = file("web/src/main/webapp/buildversion.txt")
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



  lazy val core = Project(
    id = "hakurekisteri-core",
    base = file("core"),
    configurations = Seq(LoadSpecs),
    settings = Seq(
      name                  := "hakurekisteri-core",
      organization          := Organization,
      version               := Version,
      scalaVersion          := ScalaVersion,
      artifactName          := ArtifactName,
      scalacOptions         := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8"),
      resolvers += Classpaths.typesafeReleases,
      resolvers += "oph-snapshots" at "https://artifactory.oph.ware.fi/artifactory/oph-sade-snapshot-local",
      resolvers += "oph-releases" at "https://artifactory.oph.ware.fi/artifactory/oph-sade-release-local",
      resolvers += "Sonatype" at "http://oss.sonatype.org/content/repositories/releases/",
      resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
      resolvers += "JAnalyse Repository" at "http://www.janalyse.fr/repository/",
      resolvers             += "JAnalyse Repository" at "http://www.janalyse.fr/repository/",
      artifactoryPublish,
      libraryDependencies   ++= AkkaStack ++ dependencies
        ++ testDependencies.map((m) => m % "test"),
      fullRunTask(createTestDb, Test, "util.CreateDb")
    ) ++ inConfig(LoadSpecs)(Defaults.testSettings)
      ++ inConfig(LoadSpecs)(Seq(
      testFrameworks += new TestFramework("org.scalameter.ScalaMeterFramework"),
      logBuffered := false,
      parallelExecution := false))
  )



  lazy val root = project.in(file(".")).aggregate(core, web)


  lazy val web = {
    Project(
      s"hakurekisteri-web",
      file("web"),
      configurations = Seq(LoadSpecs),
      settings = ScalatraPlugin.scalatraWithJRebel ++ scalateSettings
        ++ inConfig(LoadSpecs)(Defaults.testSettings)
        ++ inConfig(LoadSpecs)(Seq(
          testFrameworks += new TestFramework("org.scalameter.ScalaMeterFramework"),
          logBuffered := false,
          parallelExecution := false))
        ++ Seq(ideaExtraTestConfigurations := Seq(LoadSpecs))
        ++ org.scalastyle.sbt.ScalastylePlugin.Settings
        ++ Seq(scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8"))
        ++ Seq(compile in Compile <<= (compile in Compile) dependsOn npmBuild)
        ++ Seq(webappResources in Compile <+= (target in Runtime)(t => t / "javascript") )
        ++ Seq(webappResources in Compile <+= (sourceDirectory in Runtime) { sd => sd / "resources" / "tiedonsiirto"})
        ++ Seq(karmaTask, npmBuildTask, cleanNodeModules)
        ++ Seq((test in Test) <<= (test in Test) dependsOn karma)
        ++ Seq(watchSources <++= baseDirectory map { path => ((path / "src/main/webapp/coffee") ** "*.coffee").get })
        ++ Seq(
        organization := Organization,
        name := s"hakurekisteri-web",
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
          ++ SecurityStack ++ webDeps,
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

  }.dependsOn(core % "test->test;compile->compile")



}