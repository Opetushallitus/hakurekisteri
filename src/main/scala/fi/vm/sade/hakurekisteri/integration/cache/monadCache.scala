package fi.vm.sade.hakurekisteri.integration.cache

import java.io._

import akka.actor.ActorSystem
import akka.util.ByteString
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory.RedisCacheFactory
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import redis.{ByteStringFormatter, RedisClient}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.higherKinds
import scala.util.{Failure, Success, Try}

trait MonadCache[F[_], K, T] {

  def +(key: K, f: F[T])

  def -(key: K)

  def contains(key: K): Boolean

  def get(key: K): F[T]

  protected def k(key: K, prefix:String) = s"${prefix}:${key}"
}

trait CacheFactory {

  val defaultExpirationDuration: Long = 60.minutes.toMillis
  def prefix(clazz:Class[_], prefix:String) = s"${clazz.getName}:${prefix}"

  def getInstance[K, T](expirationDurationMillis:Long,
                        clazz:Class[_],
                        cacheKeyPrefix:String):MonadCache[Future, K, T] = getInstance[K, T](expirationDurationMillis, prefix(clazz, cacheKeyPrefix))

  def getInstance[K, T](expirationDurationMillis:Long,
                        cacheKeyPrefix:String):MonadCache[Future, K, T]
}

object CacheFactory {
  def apply(config: OphProperties)(implicit system:ActorSystem): CacheFactory = config.getOrElse("suoritusrekisteri.cache.redis.enabled", "false") match {
    case p if "TRUE".equalsIgnoreCase(p) => new RedisCacheFactory(config)
    case _ => new InMemoryCacheFactory
  }

  class InMemoryCacheFactory extends CacheFactory {
    override def getInstance[K, T](expirationDurationMillis: Long, cacheKeyPrefix: String) = new InMemoryFutureCache[K, T](expirationDurationMillis)

    class InMemoryFutureCache[K, T](val exp: Long = 60.minutes.toMillis) extends InMemoryMonadCache[Future, K, T](exp)

    case class Cacheable[F[_], T](inserted: Long = Platform.currentTime, accessed: Long = Platform.currentTime, f: F[T])

    class InMemoryMonadCache[F[_], K, T](val expirationDurationMillis: Long = 60.minutes.toMillis) extends MonadCache[F, K, T] {

      private var cache: Map[K, Cacheable[F, T]] = Map()

      def +(key: K, f: F[T]) = cache = cache + (key -> cacheable(key, f))

      def cacheable(key: K, f: F[T]): Cacheable[F, T] = {
        Cacheable[F, T](f = f, accessed = getAccessed(key))
      }

      def -(key: K) = if (cache.contains(key)) cache = cache - key

      def contains(key: K): Boolean = cache.contains(key) && (cache(key).inserted + expirationDurationMillis) > Platform.currentTime

      def get(key: K): F[T] = {
        val cached = cache(key)
        cache = cache + (key -> Cacheable[F, T](inserted = cached.inserted, f = cached.f))
        cached.f
      }

      def inUse(key: K): Boolean = cache.contains(key) && (cache(key).accessed + expirationDurationMillis) > Platform.currentTime

      private def getAccessed(key: K): Long = cache.get(key).map(_.accessed).getOrElse(Platform.currentTime)

      def size: Int = cache.size

      def getCache: Map[K, Cacheable[F, T]] = cache
    }
  }

  class RedisCacheFactory(config: OphProperties)(implicit system:ActorSystem) extends CacheFactory {

    val r = {
      val host = config.getOrElse("suoritusrekisteri.cache.redis.host", "")
      if("".equals(host)) throw new RuntimeException(s"No configuration for Redis host found")
      val port = config.getOrElse("suoritusrekisteri.cache.redis.port", "6379").toInt
      org.slf4j.LoggerFactory.getLogger(getClass).info(s"Using redis cache ${host}:${port}")
      RedisClient(host = host, port = port)
    }

    override def getInstance[K, T](expirationDurationMillis: Long, cacheKeyPrefix: String) = new RedisCache[K, T](r, expirationDurationMillis, cacheKeyPrefix)

    class RedisCache[K, T](val r:RedisClient,
                           val expirationDurationMillis:Long,
                           val cacheKeyPrefix:String) extends MonadCache[Future, K, T] {
      import scala.concurrent.ExecutionContext.Implicits.global
      implicit val byteStringFormatter = new ByteStringFormatterImpl[T]

      def +(key: K, f: Future[T]): Unit = f onSuccess {
        case t => r.set[T](k(key), t)
      }

      def -(key: K): Unit = r.del(k(key))

      def contains(key: K): Boolean = Await.result(r.exists(k(key)), 60.seconds)

      def get(key: K): Future[T] = r.get[T](k(key)).collect{ case Some(x) => x }

      private def k(key: K):String = {
        val moi = k(key, cacheKeyPrefix)
        println(moi)
        moi
      }
    }

    class ByteStringFormatterImpl[T] extends ByteStringFormatter[T] {

      def close(c:Try[Closeable]) = c.foreach(IOUtils.closeQuietly(_))

      def resultOrFailure[T](t:Try[T]): T = t match {
        case Success(s) => s
        case Failure(t) => throw t
      }

      def tryFinally[T](t:Try[T], resources:Try[Closeable]*) = try { resultOrFailure(t) } finally { resources.foreach(close) }

      def serialize(data: T): ByteString = {
        val baos = new ByteArrayOutputStream
        val oos = Try(new ObjectOutputStream(baos))

        def ser: Try[ByteString] = oos.map { o =>
          o.writeObject(data)
          ByteString(baos.toByteArray)
        }

        tryFinally[ByteString](ser, oos)
      }

      def deserialize(bs: ByteString): T = {
        val ois = Try(new ObjectInputStream(new ByteArrayInputStream(bs.toArray)))
        def des: Try[T] = ois.map(_.readObject.asInstanceOf[T])
        tryFinally[T](des, ois)
      }
    }
  }
}