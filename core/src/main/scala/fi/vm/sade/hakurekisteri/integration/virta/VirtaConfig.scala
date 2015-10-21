package fi.vm.sade.hakurekisteri.integration.virta

import fi.vm.sade.hakurekisteri.integration.HttpConfig

case class VirtaConfig(serviceUrl: String,
                       jarjestelma: String,
                       tunnus: String,
                       avain: String, properties: Map[String, String],
                       threads: Int = 1) extends HttpConfig(properties)
