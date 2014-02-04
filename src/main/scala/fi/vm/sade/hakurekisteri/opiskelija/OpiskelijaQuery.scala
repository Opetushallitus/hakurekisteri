package fi.vm.sade.hakurekisteri.opiskelija

case class OpiskelijaQuery(henkilo: Option[String], kausi: Option[String], vuosi: Option[String])

object OpiskelijaQuery{
  def apply(params: Map[String,String]): OpiskelijaQuery = {
    OpiskelijaQuery(params.get("henkilo"), params.get("kausi"), params.get("vuosi"))
  }
}
