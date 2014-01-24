import sbt._
import Keys._
import org.scalatra.sbt._
import org.scalatra.sbt.PluginKeys._
import com.mojolly.scalate.ScalatePlugin._
import ScalateKeys._

object HakuJaValintarekisteriBuild extends Build {
  val Organization = "fi.vm.sade"
  val Name = "Haku- ja valintarekisteri"
  val Version = "0.1.0-SNAPSHOT"
  val ScalaVersion = "2.10.3"
  val ScalatraVersion = "2.2.2"

  val ScalatraStack = Seq(
    "org.scalatra" %% "scalatra",
    "org.scalatra" %% "scalatra-scalate",
    "org.scalatra" %% "scalatra-json",
    "org.scalatra" %% "scalatra-swagger")

  val dependencies = Seq(
    "ch.qos.logback" % "logback-classic" % "1.0.6" % "runtime",
    "org.eclipse.jetty" % "jetty-webapp" % "8.1.8.v20121106" % "container",
    "org.json4s" %% "json4s-jackson" % "3.2.4"
  )

  val testDependencies = Seq("org.scalatra" %% "scalatra-scalatest" % ScalatraVersion)


  lazy val project = {

    Project(
      "hakurekisteri",
      file("."),
      settings = Defaults.defaultSettings ++ ScalatraPlugin.scalatraWithJRebel ++ scalateSettings ++ Seq(

        organization := Organization,
        name := Name,
        version := Version,
        scalaVersion := ScalaVersion,
        resolvers += Classpaths.typesafeReleases,
        libraryDependencies ++= Seq("org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "container;provided;test" artifacts (Artifact("javax.servlet", "jar", "jar")))
          ++ ScalatraStack.map((a) => a % ScalatraVersion)
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
    )
  }
}


