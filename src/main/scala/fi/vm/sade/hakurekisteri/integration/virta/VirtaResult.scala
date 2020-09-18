package fi.vm.sade.hakurekisteri.integration.virta

case class VirtaResult(
  oppijanumero: String,
  opiskeluoikeudet: Seq[VirtaOpiskeluoikeus],
  tutkinnot: Seq[VirtaTutkinto],
  suoritukset: Seq[VirtaOpintosuoritus]
)

object VirtaResult {
  def apply(oppijanumero: String): VirtaResult = VirtaResult(oppijanumero, Seq(), Seq(), Seq())
}
