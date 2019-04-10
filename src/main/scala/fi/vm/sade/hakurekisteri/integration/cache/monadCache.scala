package fi.vm.sade.hakurekisteri.integration.cache

import java.util.concurrent.{CompletableFuture, Executor}
import java.util.function.BiFunction

import com.github.benmanes.caffeine.cache.{AsyncCache, Caffeine}
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.collection.JavaConverters._
import scala.compat.Platform
import scala.compat.java8.FutureConverters
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.higherKinds

trait MonadCache[F[_], K, T] {

  def +(key: K, value: T): F[_]

  def -(key: K)

  def contains(key: K): F[Boolean]

  def get(key: K, loader: K => F[Option[T]]): F[Option[T]]

  def getVersion: Future[Option[Long]]

  protected def k(key: K, prefix:String) = s"${prefix}:${key}"
}

class InMemoryFutureCache[K, T](val expirationDurationMillis: Long = 60.minutes.toMillis) extends MonadCache[Future, K, T] {
  routeCaffeineLoggingToSlf4j()

  private val caffeineCache: AsyncCache[K, Cacheable[Future, T]] = Caffeine.newBuilder().buildAsync().asInstanceOf[AsyncCache[K, Cacheable[Future, T]]]

  import scala.concurrent.ExecutionContext.Implicits.global

  def +(key: K, v: T): Future[_] = {
    val value: Cacheable[Future, T] = Cacheable[Future, T](f = Future.successful(v))
    caffeineCache.put(key, CompletableFuture.completedFuture(value))
    value.f
  }

  def -(key: K): Unit = caffeineCache.synchronous().invalidate(key)

  def contains(key: K): Future[Boolean] = {
    val existsAndIsFreshEnough: Cacheable[Future, T] => Boolean =
      v => v != null &&
        v.inserted + expirationDurationMillis > Platform.currentTime

    val valueFromCache: CompletableFuture[Cacheable[Future, T]] = caffeineCache.getIfPresent(key)
    if (valueFromCache == null) {
      Future.successful(false)
    } else {
      FutureConverters.toScala(valueFromCache).map(existsAndIsFreshEnough)
    }
  }

  def size: Int = caffeineCache.synchronous().estimatedSize().toInt

  def getCache: Map[K, Cacheable[Future, T]] = caffeineCache.synchronous().asMap().asScala.toMap

  override def get(key: K, loader: K => Future[Option[T]]): Future[Option[T]] = {
    val loadingFunction: BiFunction[K, Executor, CompletableFuture[Cacheable[Future, T]]] = new BiFunction[K, Executor, CompletableFuture[Cacheable[Future, T]]] {
      override def apply(t: K, u: Executor): CompletableFuture[Cacheable[Future, T]] = {
        FutureConverters.toJava(loader.apply(t).map {
          case Some(x) => Cacheable(f = Future.successful(x))
          case None => null
        })
      }.toCompletableFuture
    }

    FutureConverters.toScala(caffeineCache.get(key, loadingFunction)).flatMap { value =>
      if (value == null) {
        Future.successful(None)
      } else {
        value.f.map(Some(_))
      }
    }
  }

  override def getVersion: Future[Option[Long]] = Future.successful(Some(0l))

  private def routeCaffeineLoggingToSlf4j(): Unit = {
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
  }
}

case class Cacheable[F[_], T](inserted: Long = Platform.currentTime, f: F[T])
