package fi.vm.sade.hakurekisteri.integration.ytl

import java.text.SimpleDateFormat
import java.util.Date

import org.apache.commons.lang3.time.DateUtils
import org.quartz.CronExpression
import org.slf4j.LoggerFactory

object YtlRerunPolicy {
  private val logger = LoggerFactory.getLogger(YtlRerunPolicy.getClass)

  def rerunPolicy(expression: String, ytlIntegrationActor: YtlFetchActorRef): () => Unit = {
    def nextTimestamp(expression: String, d: Date) = new SimpleDateFormat("dd.MM.yyyy HH:mm")
      .format(new CronExpression(expression).getNextValidTimeAfter(d))
    logger.info(s"First YTL fetch at '${nextTimestamp(expression, new Date())}'")

    () => {
      logger.info("Calling YtlFetchActor to start nightly YLT sync")
      ytlIntegrationActor.actor ! YtlSyncAllHautNightly("Nightly YTL sync")
    }
  }

}
