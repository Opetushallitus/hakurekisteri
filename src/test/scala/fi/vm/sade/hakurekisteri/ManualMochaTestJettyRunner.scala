package fi.vm.sade.hakurekisteri

object ManualMochaTestJettyRunner extends App {
  val port: Int = SharedTestJetty.port
  val baseUrl = s"http://localhost:$port"

  SharedTestJetty.restart()
  println( "*************************************************************************")
  println( "*                                                                       *")
  println(s"    ${getClass.getSimpleName} running at $baseUrl")
  println( "*                                                                       *")
  println( "*************************************************************************")
}
