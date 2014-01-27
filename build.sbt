org.scalastyle.sbt.ScalastylePlugin.Settings

lazy val mocha = taskKey[Unit]("run mocha tests")

lazy val installMocha = taskKey[Unit]("install mocha")

lazy val installCoffee = taskKey[Unit]("install mocha")

cleanFiles <+= baseDirectory { base => base / "node_modules" }

installMocha := {
  import sys.process._
  val pb = Seq("npm", "install",  "mocha")
  if ((pb!) !=  0)
    sys.error("failed installing mocha")
}

installCoffee := {
  import sys.process._
  val pb = Seq("npm", "install",  "coffee-script")
  if ((pb!) !=  0)
    sys.error("failed installing coffee script")
}

mocha <<= (installMocha, installCoffee) map {
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
