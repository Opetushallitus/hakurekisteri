package fi.vm.sade.hakurekisteri.web.jonotus

import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent._

import _root_.akka.pattern._
import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import com.google.common.cache.{CacheBuilder, CacheLoader, RemovalListener, RemovalNotification}
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.rest.support.User
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
  private implicit val defaultTimeout: Timeout = 120.seconds
  private implicit val formats = DefaultFormats
  private val poolSize = 1
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
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .removalListener(new RemovalListener[QueryAndFormat, Either[Exception, Array[Byte]]]() {
      override def onRemoval(notification: RemovalNotification[QueryAndFormat, Either[Exception, Array[Byte]]]): Unit =
        eradicateAllShortUrlsToQuery(notification.getKey)
    })
    .build(
      new CacheLoader[QueryAndFormat, Either[Exception, Array[Byte]]] {
        override def load(q: QueryAndFormat): Either[Exception, Array[Byte]] = {
          val tryCreateContent: Try[Array[Byte]] = Try(q.query match {
            case query:KkHakijaQuery =>
              val hakijat = Await.result(kkHakija.getKkHakijat(query), defaultTimeout.duration)
              val bytes = new ByteArrayOutputStream()
              q.format match {
                case ApiFormat.Json =>
                  IOUtils.write(write(hakijat), bytes)
                  bytes.toByteArray
                case ApiFormat.Excel =>
                  KkExcelUtil.write(bytes, hakijat)
                  bytes.toByteArray
              }
            case query:HakijaQuery =>
              Await.result((hakijaActor ? query), defaultTimeout.duration) match {
                case hakijat: XMLHakijat =>
                  val bytes = new ByteArrayOutputStream()
                  ExcelUtilV1.write(bytes, hakijat)
                  bytes.toByteArray
                case hakijat: JSONHakijat =>
                  val bytes = new ByteArrayOutputStream()
                  q.format match {
                    case ApiFormat.Json =>
                      IOUtils.write(write(hakijat), bytes)
                      bytes.toByteArray
                    case ApiFormat.Excel =>
                      ExcelUtilV2.write(bytes, hakijat)
                      bytes.toByteArray
                  }
                case _ =>
                  logger.error(s"Couldn't handle return type from HakijaActor!")
                  throw new RuntimeException("No content to store!")
              }
            case _ =>
              logger.error(s"Unknown 'asiakirja' requested with query ${q.query}")
              throw new RuntimeException("No content to store!")
          })
          tryCreateContent match {
            case Success(content) =>
              if(content.length == 0) {
                Left(new EmptyAsiakirjaException())
              } else {
                Right(content)
              }
            case Failure(fail: Exception) =>
              Left(fail)
            case Failure(t: Throwable) =>
              Left(new RuntimeException(t))
          }
        }
      })

  def getAsiakirjaWithId(id: String): Option[(ApiFormat, Either[Exception, Array[Byte]], Option[User])] = {
    Option(shortIds.get(id)).map(q => (q.format, asiakirjat.getIfPresent(q), q.query.user))
  }
  private def submitNewAsiakirja(q: QueryAndFormat) {
    threadPool.execute(new Runnable() {
      override def run(): Unit = {
        // prevent multiple threads from starting same job <= case where user retries multiple times
        val firstInProgress = inprogress.addIfAbsent(q)
        if(firstInProgress) {
          try {
          val jobWasAvailable = jobs.remove(q)
          if(jobWasAvailable) {
            if(asiakirjat.getIfPresent(q) == null) {
              asiakirjat.get(q)
              logger.info(s"Asiakirja created for id ${queryToShortId(q)}")
            }
          }
          } finally {
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

  def addToJono(q: QueryAndFormat, personOid: String): Option[Int] = {
    asiakirjat.invalidate(q)
    jobs.add(q)
    val pos = positionInQueue(q)
    submitNewAsiakirja(q)
    queryToShortId(q)
    pos
  }
  def isExistingAsiakirjaWithErrors(q: QueryAndFormat): Boolean = Option(asiakirjat.getIfPresent(q)) match {
    case Some(Left(_)) =>
      true
    case _ =>
      false
  }
  def isExistingAsiakirja(q: QueryAndFormat): Boolean = asiakirjat.getIfPresent(q) != null
  def positionInQueue(q: QueryAndFormat): Option[Int] = Some(jobs.indexOf(q)).filter(_ != -1).map(_ + 1)
}
