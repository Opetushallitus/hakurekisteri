package fi.vm.sade.hakurekisteri.web.jonotus

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent._

import _root_.akka.pattern._
import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import com.google.common.cache.{CacheBuilder, CacheLoader, RemovalListener, RemovalNotification}
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.hakija.representation._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.kkhakija._
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat.ApiFormat
import org.apache.commons.io.IOUtils
import org.json4s.jackson.Serialization.write
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class Siirtotiedostojono(hakijaActor: ActorRef, kkHakija: KkHakijaService)(implicit
  system: ActorSystem
) {
  private implicit val defaultTimeout: Timeout = 45.minutes
  private implicit val formats = HakurekisteriJsonSupport.format
  private val poolSize = 4
  private val threadPool = Executors.newFixedThreadPool(poolSize)
  private val logger = LoggerFactory.getLogger(classOf[Siirtotiedostojono])
  private val jobs = new CopyOnWriteArrayList[QueryAndFormat]()
  private val inprogress = new CopyOnWriteArrayList[QueryAndFormat]()
  private val shortIds = new ConcurrentHashMap[String, QueryAndFormat]()

  private def eradicateAllShortUrlsToQuery(q: QueryAndFormat): Unit = {
    import scala.collection.JavaConverters._
    shortIds
      .entrySet()
      .asScala
      .filter(_.getValue.equals(q))
      .map(_.getKey)
      .foreach(shortIds.remove)
  }

  private type cacheKeyType = QueryAndFormat
  private type cacheValueType = Either[Exception, Array[Byte]]
  private val asiakirjat = CacheBuilder
    .newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(1, TimeUnit.HOURS)
    .removalListener(new RemovalListener[cacheKeyType, cacheValueType]() {
      override def onRemoval(
        notification: RemovalNotification[cacheKeyType, cacheValueType]
      ): Unit =
        eradicateAllShortUrlsToQuery(notification.getKey)
    })
    .build[cacheKeyType, cacheValueType](new CacheLoader[cacheKeyType, cacheValueType] {
      override def load(q: cacheKeyType): cacheValueType = {
        Try(q.query match {
          case query: KkHakijaQuery =>
            kkQueryToAsiakirja(q.format, query)
          case query: HakijaQuery =>
            queryToAsiakirja(q.format, query)
          case _ =>
            logger.error(s"Unknown 'asiakirja' requested with query ${q.query}")
            throw new RuntimeException("No content to store!")
        }) match {
          case Success(content) =>
            if (content.length == 0) {
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

  private val charset: Charset = Charset.forName("UTF-8")

  def queryToAsiakirja(format: ApiFormat, query: HakijaQuery): Array[Byte] = {
    Await.result(hakijaActor ? query, defaultTimeout.duration) match {
      case hakijat: XMLHakijat =>
        if (hakijat.hakijat.isEmpty) {
          Array()
        } else {
          format match {
            case ApiFormat.Excel =>
              val bytes = new ByteArrayOutputStream()
              ExcelUtilV1.write(bytes, hakijat)
              bytes.toByteArray

            case ApiFormat.Xml =>
              val printer = new scala.xml.PrettyPrinter(120, 2)
              val formattedXml = printer.format(hakijat.toXml)
              val bytes = new ByteArrayOutputStream()
              IOUtils.write(formattedXml, bytes, charset)
              bytes.toByteArray
          }

        }
      case hakijat: JSONHakijat =>
        if (hakijat.hakijat.isEmpty) {
          Array()
        } else {
          val bytes = new ByteArrayOutputStream()
          format match {
            case ApiFormat.Json =>
              IOUtils.write(write(hakijat), bytes, charset)
              bytes.toByteArray
            case ApiFormat.Excel =>
              if (query.version == 2) {
                ExcelUtilV2.write(bytes, hakijat)
              } else {
                ExcelUtilV3.write(bytes, hakijat)
              }
              bytes.toByteArray
          }
        }
      case hakijat: JSONHakijatV4 =>
        if (hakijat.hakijat.isEmpty) {
          Array()
        } else {
          val bytes = new ByteArrayOutputStream()
          format match {
            case ApiFormat.Json =>
              IOUtils.write(write(hakijat), bytes, charset)
              bytes.toByteArray
            case ApiFormat.Excel =>
              ExcelUtilV4.write(bytes, hakijat)
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
          IOUtils.write(write(hakijat), bytes, charset)
          bytes.toByteArray
        case ApiFormat.Excel =>
          query.version match {
            case 1 =>
              KkExcelUtil.write(bytes, hakijat)
            case 2 =>
              KkExcelUtilV2.write(bytes, hakijat)
            case 3 =>
              KkExcelUtilV3.write(bytes, hakijat)
            case 4 =>
              KkExcelUtilV4.write(bytes, hakijat)
            case _ =>
              throw new RuntimeException("Unknown version number requested")
          }
          bytes.toByteArray
      }
    }
  }

  def getAsiakirjaWithId(id: String): Option[(ApiFormat, cacheValueType, Option[User])] = {
    Option(shortIds.get(id)).map(q => (q.format, asiakirjat.getIfPresent(q), q.query.user))
  }
  private def submitNewAsiakirja(q: QueryAndFormat) {
    threadPool.execute(new Runnable() {
      override def run(): Unit = {
        // prevent multiple threads from starting same job <= case where user retries multiple times
        val isMissing = asiakirjat.getIfPresent(q) == null
        val firstInProcessor = isMissing && inprogress.addIfAbsent(q)
        if (firstInProcessor) {
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
    import scala.collection.JavaConverters._
    shortIds.entrySet().asScala.find(_.getValue.equals(q)) match {
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
  def positionInQueue(q: QueryAndFormat): Option[Int] =
    Some(jobs.indexOf(q)).filter(_ != -1).map(_ + 1)
  def isInProgress(q: QueryAndFormat): Boolean = inprogress.contains(q)
}
