package fi.vm.sade.hakurekisteri.integration.cache

import java.io._

import akka.actor.ActorSystem
import akka.util.ByteString
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import redis.{ByteStringFormatter, RedisClient}

import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

import scala.concurrent.duration._

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
  def apply(config: OphProperties)(implicit system: ActorSystem): CacheFactory = config.getOrElse("suoritusrekisteri.cache.redis.enabled", "false") match {
    case p if "TRUE".equalsIgnoreCase(p) => new RedisCacheFactory(config)
    case _ => new InMemoryCacheFactory
  }

  class InMemoryCacheFactory extends CacheFactory {
    override def getInstance[K, T](expirationDurationMillis: Long, cacheKeyPrefix: String) = new InMemoryFutureCache[K, T](expirationDurationMillis)
  }

  class RedisCacheFactory(config: OphProperties)(implicit system: ActorSystem) extends CacheFactory {

    val r = {
      val host = config.getOrElse("suoritusrekisteri.cache.redis.host", "")
      if ("".equals(host)) throw new RuntimeException(s"No configuration for Redis host found")
      val port = config.getOrElse("suoritusrekisteri.cache.redis.port", "6379").toInt
      org.slf4j.LoggerFactory.getLogger(getClass).info(s"Using redis cache ${host}:${port}")
      RedisClient(host = host, port = port)
    }

    override def getInstance[K, T](expirationDurationMillis: Long, cacheKeyPrefix: String) = new RedisCache[K, T](r, expirationDurationMillis, cacheKeyPrefix)

    class RedisCache[K, T](val r: RedisClient,
                           val expirationDurationMillis: Long,
                           val cacheKeyPrefix: String) extends MonadCache[Future, K, T] {

      val logger = org.slf4j.LoggerFactory.getLogger(getClass)

      import scala.concurrent.ExecutionContext.Implicits.global

      implicit val byteStringFormatter = new ByteStringFormatterImpl[T]

      def +(key: K, f: Future[T]): Unit = f onSuccess {
        case t => {
          val pefixKey = k(key)
          logger.info(s"Adding value with key ${pefixKey} to Redis cache")
          r.set[T](pefixKey, t, pxMilliseconds = Some(expirationDurationMillis))
        }
      }

      def -(key: K): Unit = r.del(k(key))

      def contains(key: K): Boolean = Await.result(r.exists(k(key)), 60.seconds)

      def get(key: K): Future[T] = {
        val pefixKey = k(key)
        logger.info(s"Getting value with key ${pefixKey} from Redis cache")
        r.get[T](pefixKey).collect { case Some(x) => x }
      }

      private def k(key: K): String = k(key, cacheKeyPrefix)

    }

    class ByteStringFormatterImpl[T] extends ByteStringFormatter[T] {

      def close(c: Try[Closeable]) = c.foreach(IOUtils.closeQuietly(_))

      def resultOrFailure[T](t: Try[T]): T = t match {
        case Success(s) => s
        case Failure(t) => throw t
      }

      def tryFinally[T](t: Try[T], resources: Try[Closeable]*) = try {
        resultOrFailure(t)
      } finally {
        resources.foreach(close)
      }

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