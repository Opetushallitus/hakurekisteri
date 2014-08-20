package fi.vm.sade.hakurekisteri.integration.virta

case class VirtaResult(opiskeluoikeudet: Seq[VirtaOpiskeluoikeus],
                       tutkinnot: Seq[VirtaTutkinto])
