resolvers += "sonatype-releases" at "https://oss.sonatype.org/content/repositories/releases/"

resolvers += "sonatype-snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

resolvers += "sbt-plugin-snapshots" at "http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-snapshots/"

addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.5")

addSbtPlugin("com.mojolly.scalate" % "xsbt-scalate-generator" % "0.5.0")

addSbtPlugin("com.earldouglas" % "xsbt-web-plugin" % "0.9.0")

addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "0.3.2")

addSbtPlugin("info.schleichardt" % "sbt-sonar" % "0.2.0-SNAPSHOT")

addSbtPlugin("com.bowlingx" %% "xsbt-wro4j-plugin" % "0.3.5")
