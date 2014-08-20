package fi.vm.sade.hakurekisteri.integration.organisaatio

import scala.concurrent.Future

trait Organisaatiopalvelu {

  def getAll:Future[Seq[String]]
  def get(str: String): Future[Option[Organisaatio]]

}
