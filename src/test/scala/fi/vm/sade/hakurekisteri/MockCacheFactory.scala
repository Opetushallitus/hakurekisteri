package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory.InMemoryCacheFactory
import fi.vm.sade.hakurekisteri.integration.cache.{CacheFactory, MonadCache}
import fi.vm.sade.scalaproperties.OphProperties

import scala.concurrent.Future

object MockCacheFactory {
  def get() = CacheFactory.apply(new OphProperties().addDefault("suoritusrekisteri.cache.redis.enabled", "false"))(null)
}
