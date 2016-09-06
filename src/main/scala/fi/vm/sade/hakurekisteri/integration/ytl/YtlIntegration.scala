package fi.vm.sade.hakurekisteri.integration.ytl

import fi.vm.sade.hakurekisteri.integration.hakemus.{Trigger, HakemusService}
import fi.vm.sade.properties.OphProperties

class YtlIntegration(config: OphProperties, fileSystem: YtlFileSystem, hakemusService: HakemusService) {
  val fetch = new YtlHttpFetch(config, fileSystem)

  hakemusService.addTrigger(Trigger(hakemus =>  {
    if (hakemus.hetu.isDefined) {
      val student = fetch.fetchOne(hakemus.hetu.get)
      // TODO
    }
  }))
}
