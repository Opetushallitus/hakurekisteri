package fi.vm.sade.hakurekisteri.hakija

trait Hakupalvelu {

  def find(q: HakijaQuery): Seq[Hakemus]

}

object RestHakupalvelu extends Hakupalvelu {

  override def find(q: HakijaQuery): Seq[Hakemus] = {
    Seq()
  }

}