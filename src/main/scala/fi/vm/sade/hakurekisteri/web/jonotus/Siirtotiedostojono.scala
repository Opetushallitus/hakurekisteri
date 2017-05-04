package fi.vm.sade.hakurekisteri.web.jonotus

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent._

import _root_.akka.pattern._
import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import com.google.common.cache.{CacheBuilder, CacheLoader, RemovalListener, RemovalNotification}
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.kkhakija._
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat.ApiFormat
import org.apache.commons.io.IOUtils
import org.json4s.DefaultFormats
import org.json4s.jackson.Serialization.write
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class Siirtotiedostojono(hakijaActor: ActorRef, kkHakija: KkHakijaService)(implicit system: ActorSystem) {
  private implicit val defaultTimeout: Timeout = 45.minutes
  private implicit val formats = HakurekisteriJsonSupport.format
  private val poolSize = 4
  private val threadPool = Executors.newFixedThreadPool(poolSize)
  private val logger = LoggerFactory.getLogger(classOf[Siirtotiedostojono])
  private val jobs = new CopyOnWriteArrayList[QueryAndFormat]()
  private val inprogress = new CopyOnWriteArrayList[QueryAndFormat]()
  private val shortIds = new ConcurrentHashMap[String, QueryAndFormat]()

  private def eradicateAllShortUrlsToQuery(q: QueryAndFormat): Unit = {
    import scala.collection.JavaConversions._
    shortIds.entrySet().filter(_.getValue.equals(q)).map(_.getKey)
      .foreach(shortIds.remove)
  }

  private val asiakirjat = CacheBuilder.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(1, TimeUnit.HOURS)
    .removalListener(new RemovalListener[QueryAndFormat, Either[Exception, Array[Byte]]]() {
      override def onRemoval(notification: RemovalNotification[QueryAndFormat, Either[Exception, Array[Byte]]]): Unit =
        eradicateAllShortUrlsToQuery(notification.getKey)
    })
    .build(
      new CacheLoader[QueryAndFormat, Either[Exception, Array[Byte]]] {
        override def load(q: QueryAndFormat): Either[Exception, Array[Byte]] = {
          Try(q.query match {
            case query:KkHakijaQuery =>
              kkQueryToAsiakirja(q.format, query)
            case query:HakijaQuery =>
              queryToAsiakirja(q.format, query)
            case _ =>
              logger.error(s"Unknown 'asiakirja' requested with query ${q.query}")
              throw new RuntimeException("No content to store!")
          }) match {
            case Success(content) =>
              if(content.length == 0) {
                logger.error("Created asiakirja had no content")
                Left(new EmptyAsiakirjaException())
              } else {
                Right(content)
              }
            case Failure(fail: Exception) =>
              logger.error("Error creating asiakirja: ", fail)
              Left(fail)
            case Failure(t: Throwable) =>
              logger.error("Error creating asiakirja: ", t)
              Left(new RuntimeException(t))
          }
        }
      })

  def queryToAsiakirja(format: ApiFormat, query: HakijaQuery): Array[Byte] = {
    Await.result(hakijaActor ? query, defaultTimeout.duration) match {
      case hakijat: XMLHakijat =>
        if (hakijat.hakijat.isEmpty) {
          Array()
        } else {
          val bytes = new ByteArrayOutputStream()
          ExcelUtilV1.write(bytes, hakijat)
          bytes.toByteArray
        }
      case hakijat: JSONHakijat =>
        if (hakijat.hakijat.isEmpty) {
          Array()
        } else {
          val bytes = new ByteArrayOutputStream()
          format match {
            case ApiFormat.Json =>
              IOUtils.write(write(hakijat), bytes)
              bytes.toByteArray
            case ApiFormat.Excel =>
              if(query.version == 2) {
                ExcelUtilV2.write(bytes, hakijat)
              } else {
                ExcelUtilV3.write(bytes, hakijat)
              }
              bytes.toByteArray
          }
        }
      case _ =>
        logger.error(s"Couldn't handle return type from HakijaActor!")
        throw new scala.RuntimeException("No content to store!")
    }
  }


  def kkQueryToAsiakirja(format: ApiFormat, query: KkHakijaQuery): Array[Byte] = {
    val hakijat = Await.result(kkHakija.getKkHakijat(query, query.version), defaultTimeout.duration)
    if (hakijat.isEmpty) {
      Array()
    } else {
      val bytes = new ByteArrayOutputStream()
      format match {
        case ApiFormat.Json =>
          IOUtils.write(write(hakijat), bytes)
          bytes.toByteArray
        case ApiFormat.Excel =>
          if(query.version == 1) {
            KkExcelUtil.write(bytes, hakijat)
          } else {
            KkExcelUtilV3.write(bytes, hakijat)
          }
          bytes.toByteArray
      }
    }
  }

  def getAsiakirjaWithId(id: String): Option[(ApiFormat, Either[Exception, Array[Byte]], Option[User])] = {
    Option(shortIds.get(id)).map(q => (q.format, asiakirjat.getIfPresent(q), q.query.user))
  }
  private def submitNewAsiakirja(q: QueryAndFormat) {
    threadPool.execute(new Runnable() {
      override def run(): Unit = {
        // prevent multiple threads from starting same job <= case where user retries multiple times
        val isMissing = asiakirjat.getIfPresent(q) == null
        val firstInProcessor = isMissing && inprogress.addIfAbsent(q)
        if(firstInProcessor) {
          try {
            asiakirjat.get(q)
            logger.info(s"Asiakirja created for id ${queryToShortId(q)}")
          } finally {
            jobs.remove(q)
            inprogress.remove(q)
          }
        }
      }
    })
  }
  def queryToShortId(q: QueryAndFormat): String = {
    import scala.collection.JavaConversions._
    shortIds.entrySet().find(_.getValue.equals(q)) match {
      case Some(entry) =>
        entry.getKey
      case None =>
        val uuid = UUID.randomUUID().toString
        shortIds.put(uuid, q)
        uuid
    }
  }

  def forceAddToJono(q: QueryAndFormat, personOid: String): Option[Int] = {
    asiakirjat.invalidate(q)
    jobs.add(q)
    val pos = positionInQueue(q)
    submitNewAsiakirja(q)
    queryToShortId(q)
    pos
  }

  def isExistingAsiakirja(q: QueryAndFormat): Boolean = asiakirjat.getIfPresent(q) != null
  def positionInQueue(q: QueryAndFormat): Option[Int] = Some(jobs.indexOf(q)).filter(_ != -1).map(_ + 1)
  def isInProgress(q: QueryAndFormat): Boolean = inprogress.contains(q)
}
