import java.text.SimpleDateFormat
import java.util.Date

import com.earldouglas.xsbtwebplugin._
import com.mojolly.scalate.ScalatePlugin._
import sbt.Keys._
import sbt._

import scala.language.postfixOps

object HakurekisteriBuild extends Build {
  import com.earldouglas.xsbtwebplugin.PluginKeys._

  import sys.process._

  val Organization = "fi.vm.sade"
  val Name = "hakurekisteri"
  val Version = "14.0-SNAPSHOT"
  val ScalaVersion = "2.11.2"
  val ArtifactName = (s: ScalaVersion, m: ModuleID, a: Artifact) => s"${a.name}-${m.revision}.${a.extension}"
  val ScalatraVersion = "2.4.1"
  val SpringVersion = "3.2.8.RELEASE"

  lazy val LoadSpecs = config("load") extend Test
  lazy val IntegrationTest = config("it") extend Test

  lazy val createDevDb = taskKey[Unit]("create h2 db for development")

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
    "org.eclipse.jetty" % "jetty-webapp" % "9.2.1.v20140609" % "container;test",
    "org.eclipse.jetty" % "jetty-plus" % "9.2.1.v20140609" % "container;test",
    "javax.servlet" % "javax.servlet-api" % "3.1.0",
    "validator" % "hakurekisteri-validation" % "0.1.0-SNAPSHOT" changing()
  )

  val dependencies = Seq(
    "org.scala-lang.modules" % "scala-xml_2.11" % "1.0.5",
    "org.scalaz" %% "scalaz-core" % "7.2.3",
    "org.scalaz.stream" %% "scalaz-stream" % "0.7.2",
    "org.slf4j" % "slf4j-api" % "1.6.1",
    "org.json4s" %% "json4s-ast" % "3.3.0",
    "org.json4s" %% "json4s-core" % "3.3.0",
    "org.json4s" %% "json4s-ext" % "3.3.0",
    "org.json4s" %% "json4s-jackson" % "3.3.0",
    "com.github.nscala-time" %% "nscala-time" % "1.4.0",
    "com.typesafe.slick" %% "slick" % "2.1.0",
    "com.h2database" % "h2" % "1.3.176",
    "org.postgresql" % "postgresql" % "9.3-1100-jdbc4",
    "net.databinder.dispatch" %% "dispatch-core" % "0.11.2",
    "org.apache.poi" % "poi" % "3.10.1",
    "org.apache.poi" % "poi-ooxml" % "3.10.1" exclude("xml-apis", "xml-apis"),
    "org.apache.activemq" % "activemq-all" % "5.9.1",
    "org.apache.camel" % "camel-jms" % "2.13.0",
    "fi.vm.sade.log" % "log-client" % "7.0",
    "fi.vm.sade" % "auditlogger" % "5.0.0-SNAPSHOT",
    "fr.janalyse" %% "janalyse-ssh" % "0.9.14",
    "fi.vm.sade" %% "scala-utils" % "0.1.0-SNAPSHOT",
    "fi.vm.sade" %% "scala-properties" % "0.0.1-SNAPSHOT"
  )

  val testDependencies = Seq("org.scalatra" %% "scalatra-scalatest" % ScalatraVersion,
    "org.scalamock" %% "scalamock-scalatest-support" % "3.1.4",
    "com.storm-enroute" %% "scalameter" % "0.6",
    "org.apache.tomcat.embed" % "tomcat-embed-core" % "7.0.39",
    "org.apache.tomcat.embed" % "tomcat-embed-logging-juli" % "7.0.39",
    "org.apache.tomcat.embed" % "tomcat-embed-jasper" % "7.0.39"
  )

  lazy val npmBuild = taskKey[Unit]("run npm build")
  val npmBuildTask = npmBuild := {
    if ((Seq("npm", "run", "build", "-s")!) !=  0)
      sys.error("npm run build failed")
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

  lazy val generateSchema = taskKey[Unit]("start database schema generator")

  lazy val generateDbDiagram = taskKey[Unit]("start schema diagram generator")

  val generateDbDiagramTask = generateDbDiagram <<= (version, baseDirectory) map {
    (ver: String, dir: File) =>
      s"sbin/generateDbDiagram.sh $dir/target/sql2diagram suoritusrekisteri-$ver" !
  }

  val scalac = Seq(scalacOptions ++= Seq( "-deprecation", "-unchecked", "-feature" ))

  lazy val core = Project(
    id = "hakurekisteri-core",
    base = file("core"),
    configurations = Seq(LoadSpecs, IntegrationTest),
    settings = Seq(
      name                  := "hakurekisteri-core",
      organization          := Organization,
      version               := Version,
      scalaVersion          := ScalaVersion,
      artifactName          := ArtifactName,
      scalacOptions         := Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8", "-Xfatal-warnings", "-Xmax-classfile-name", "78"),
      updateOptions         := updateOptions.value.withCachedResolution(true),
      resolvers += Classpaths.typesafeReleases,
      resolvers += "oph-snapshots" at "https://artifactory.oph.ware.fi/artifactory/oph-sade-snapshot-local",
      resolvers += "oph-releases" at "https://artifactory.oph.ware.fi/artifactory/oph-sade-release-local",
      resolvers += "Sonatype" at "http://oss.sonatype.org/content/repositories/releases/",
      resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
      resolvers += "JAnalyse Repository" at "http://www.janalyse.fr/repository/",
      resolvers += "JAnalyse Repository" at "http://www.janalyse.fr/repository/",
      artifactoryPublish,
      parallelExecution := false,
      generateDbDiagramTask,
      libraryDependencies   ++= AkkaStack ++ dependencies
        ++ testDependencies.map((m) => m % "test,it"),
      fullRunTask(createDevDb, Test, "util.CreateDevDb"),
      fullRunTask(generateSchema, Compile, "fi.vm.sade.hakurekisteri.storage.SchemaGenerator")
    ) ++ inConfig(LoadSpecs)(Defaults.testSettings)
      ++ inConfig(LoadSpecs)(Seq(
      testFrameworks += new TestFramework("org.scalameter.ScalaMeterFramework"),
      logBuffered := false,
      parallelExecution := false)
    ) ++ inConfig(IntegrationTest)(Defaults.testSettings)
      ++ inConfig(IntegrationTest)(Seq(parallelExecution := false))
  )

  lazy val hakurekisteri = project.in(file(".")).aggregate(core, web)

  lazy val web = {
    Project(
      s"hakurekisteri-web",
      file("web"),
      configurations = Seq(LoadSpecs, IntegrationTest),
      settings = WebPlugin.webSettings ++ scalateSettings
        ++ inConfig(LoadSpecs)(Defaults.testSettings)
        ++ inConfig(LoadSpecs)(Seq(
          testFrameworks += new TestFramework("org.scalameter.ScalaMeterFramework"),
          parallelExecution := false,
          logBuffered := false))
        ++ inConfig(IntegrationTest)(Defaults.testSettings)
        ++ inConfig(IntegrationTest)(Seq(parallelExecution := false))
        ++ org.scalastyle.sbt.ScalastylePlugin.Settings
        ++ Seq(scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature", "-encoding", "utf8", "-Xfatal-warnings", "-Xmax-classfile-name", "100"))
        ++ Seq(compile in Compile <<= (compile in Compile) dependsOn npmBuild)
        ++ Seq(webappResources in Compile <+= (target in Runtime)(t => t / "javascript") )
        ++ Seq(webappResources in Compile <+= (sourceDirectory in Runtime) { sd => sd / "resources" / "tiedonsiirto"})
        ++ Seq(npmBuildTask, cleanNodeModules)
        ++ Seq(watchSources <++= baseDirectory map { path => ((path / "src/main/webapp/coffee") ** "*.coffee").get })
        ++ Seq(
        organization := Organization,
        name := s"hakurekisteri-web",
        version := Version,
        scalaVersion := ScalaVersion,
        artifactName := ArtifactName,
        updateOptions := updateOptions.value.withCachedResolution(true),
        resolvers += Classpaths.typesafeReleases,
        resolvers += "oph-snapshots" at "https://artifactory.oph.ware.fi/artifactory/oph-sade-snapshot-local",
        resolvers += "oph-releases" at "https://artifactory.oph.ware.fi/artifactory/oph-sade-release-local",
        resolvers += "Sonatype" at "http://oss.sonatype.org/content/repositories/releases/",
        resolvers += "Sonatype snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
        resolvers += "JAnalyse Repository" at "http://www.janalyse.fr/repository/",
        resolvers += "clojars.org" at "http://clojars.org/repo",
        credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
        artifactoryPublish,
        parallelExecution := false,
        buildversionTask,
        libraryDependencies ++=  ScalatraStack.map(_ % ScalatraVersion)
          ++ SecurityStack ++ webDeps
      )).settings(net.virtualvoid.sbt.graph.Plugin.graphSettings: _*)

  }.dependsOn(core % "it->it;test->test;compile->compile")
}
