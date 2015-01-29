package fi.vm.sade.hakurekisteri.integration.virta

case class VirtaResult(oppijanumero: String,
                       opiskeluoikeudet: Seq[VirtaOpiskeluoikeus],
                       tutkinnot: Seq[VirtaTutkinto])
