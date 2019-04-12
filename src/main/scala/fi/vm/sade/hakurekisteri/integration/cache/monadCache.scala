package fi.vm.sade.hakurekisteri.integration.cache

import java.time.{Duration => JavaDuration}
import java.util.concurrent.{CompletableFuture, Executor}
import java.util.function.BiFunction

import com.github.benmanes.caffeine.cache.{AsyncCache, Caffeine}
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.reflect.runtime.universe.typeOf
import scala.reflect.runtime.universe.TypeTag

trait MonadCache[F[_], K, T] {

  def +(key: K, value: T): F[_]

  def -(key: K)

  def contains(key: K): F[Boolean]

  def get(key: K, loader: K => F[Option[T]]): F[Option[T]]

  def getVersion: Future[Option[Long]]

  protected def k(key: K, prefix:String) = s"${prefix}:${key}"
}

class InMemoryFutureCache[K, T: TypeTag](val expirationDurationMillis: Long = 60.minutes.toMillis) extends MonadCache[Future, K, T] {
  routeCaffeineLoggingToSlf4j()

  private val typeName = typeOf[T].typeSymbol.name.toString
  private implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(4, s"${getClass.getSimpleName}-for-$typeName")

  private val caffeineCache: AsyncCache[K, T] = Caffeine.newBuilder().
    expireAfterWrite(JavaDuration.ofMillis(expirationDurationMillis)).
    buildAsync().
    asInstanceOf[AsyncCache[K, T]]

  def +(key: K, v: T): Future[_] = {
    caffeineCache.put(key, CompletableFuture.completedFuture(v))
    Future.successful(v)
  }

  def -(key: K): Unit = caffeineCache.synchronous().invalidate(key)

  def contains(key: K): Future[Boolean] = {
    val valueFromCache: CompletableFuture[T] = caffeineCache.getIfPresent(key)
    if (valueFromCache == null) {
      Future.successful(false)
    } else {
      FutureConverters.toScala(valueFromCache).map(_ != null)
    }
  }

  def size: Int = caffeineCache.synchronous().estimatedSize().toInt

  def getCache: Map[K, T] = caffeineCache.synchronous().asMap().asScala.toMap

  override def get(key: K, loader: K => Future[Option[T]]): Future[Option[T]] = {
    val loadingFunction: BiFunction[K, Executor, CompletableFuture[T]] = (t: K, u: Executor) => {
      FutureConverters.toJava(loader.apply(t).flatMap {
        case Some(x) => Future.successful(x)
        case None => null
      })
    }.toCompletableFuture

    FutureConverters.toScala(caffeineCache.get(key, loadingFunction)).flatMap { value =>
      Future.successful(Option(value))
    }
  }

  override def getVersion: Future[Option[Long]] = Future.successful(Some(0l))

  private def routeCaffeineLoggingToSlf4j(): Unit = {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
  }
}
