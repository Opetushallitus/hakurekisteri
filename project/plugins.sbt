addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.5.2")

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.4")


addSbtPlugin("com.mojolly.scalate" % "xsbt-scalate-generator" % "0.4.2")

addSbtPlugin("org.scalatra.sbt" % "scalatra-sbt" % "0.3.2")

addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "0.3.2")

resolvers += "sonatype-releases" at "https://oss.sonatype.org/content/repositories/releases/"

resolvers += "sonatype-snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

addSbtPlugin("info.schleichardt" % "sbt-sonar" % "0.2.0-SNAPSHOT")

addSbtPlugin("com.sqality.scct" % "sbt-scct" % "0.3")
