package fi.vm.sade.hakurekisteri.query

case class SuoritusQuery(henkilo: Option[String], kausi: Option[String], vuosi: Option[String])

object SuoritusQuery{
  def apply(params: Map[String,String]): SuoritusQuery = {
    SuoritusQuery(params.get("henkilo"), params.get("kausi"), params.get("vuosi"))
  }
}
