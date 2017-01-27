package fi.vm.sade.hakurekisteri.web.jonotus

import java.util.{UUID, Date}
import java.util.concurrent._
import java.util.function.BiFunction

import com.google.common.cache.{CacheLoader, RemovalNotification, RemovalListener, CacheBuilder}
import fi.vm.sade.hakurekisteri.web.kkhakija.Query
import org.slf4j.LoggerFactory


class Siirtotiedostojono {

  private val poolSize = 1
  private val threadPool = Executors.newFixedThreadPool(poolSize)
  private val logger = LoggerFactory.getLogger(classOf[Siirtotiedostojono])
  private val jobs = new CopyOnWriteArrayList[Query]()
  private val shortUrls = new ConcurrentHashMap[String, Query]()
  private def eradicateAllShortUrlsToQuery(q: Query): Unit = {
    import scala.collection.JavaConversions._
    shortUrls.entrySet().filter(_.getValue.equals(q)).map(_.getKey)
      .foreach(shortUrls.remove)
  }
  private val asiakirjat = CacheBuilder.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .removalListener(new RemovalListener[Query, Array[Byte]]() {
      override def onRemoval(notification: RemovalNotification[Query, Array[Byte]]): Unit =
        eradicateAllShortUrlsToQuery(notification.getKey)
    })
    .build(
      new CacheLoader[Query, Array[Byte]]() {
        override def load(q: Query): Array[Byte] = {
          Thread.sleep(15L * 1000L)
          "Jee jee jee".getBytes
        }
      })
  private def submitNewAsiakirja(q: Query) {
    threadPool.execute(new Runnable() {
      override def run(): Unit = {
        if(jobs.remove(q)) {
          if(asiakirjat.getIfPresent(q) == null) {
            asiakirjat.get(q)
            logger.info(s"Asiakirja created for id ${queryToShortUrl(q)}")
          }
        }
      }
    })
  }
  def queryToShortUrl(q: Query): String = {
    import scala.collection.JavaConversions._
    shortUrls.entrySet().find(_.getValue.equals(q)) match {
      case Some(entry) =>
        entry.getKey
      case None =>
        val uuid = UUID.randomUUID().toString
        shortUrls.put(uuid, q)
        uuid
    }
  }

  def addToJono(q: Query, personOid: String): String = {
    jobs.add(q)
    submitNewAsiakirja(q)
    queryToShortUrl(q)
  }
  def isExistingAsiakirja(q: Query): Boolean = asiakirjat.getIfPresent(q) != null
  def positionInQueue(q: Query): Int = jobs.indexOf(q)
  private def addPersonOid(p: SiirtotiedostoPyynto, oid: String) = p.copy(personOids = p.personOids + oid)
}
