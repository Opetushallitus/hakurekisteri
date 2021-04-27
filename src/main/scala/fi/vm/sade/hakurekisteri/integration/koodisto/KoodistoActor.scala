package fi.vm.sade.hakurekisteri.integration.koodisto

import java.util.concurrent.ExecutionException

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{AskableActorRef, pipe}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.{
  ExecutorUtil,
  OphUrlProperties,
  PreconditionFailedException,
  VirkailijaRestClient
}
import support.{TypedActorRef, TypedAskableActorRef}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class GetRinnasteinenKoodiArvoQuery(
  koodisto: String,
  arvo: String,
  rinnasteinenKoodistoUri: String
)
case class Koodisto(koodistoUri: String)
case class KoodiMetadata(nimi: String, kieli: String)
@SerialVersionUID(1) case class Koodi(
  koodiArvo: String,
  koodiUri: String,
  koodisto: Koodisto,
  metadata: Seq[KoodiMetadata]
)
case class RinnasteinenKoodiNotFoundException(message: String) extends Exception(message)

case class GetKoodi(koodistoUri: String, koodiUri: String)

@SerialVersionUID(1) case class KoodistoKoodiArvot(koodistoUri: String, arvot: Seq[String])
case class GetKoodistoKoodiArvot(koodistoUri: String)

class KoodistoActor(restClient: VirkailijaRestClient, config: Config, cacheFactory: CacheFactory)
    extends Actor
    with ActorLogging {

  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

  private val koodiCache = cacheFactory.getInstance[String, Option[Koodi]](
    config.integrations.koodistoCacheHours.hours.toMillis,
    this.getClass,
    classOf[Koodi],
    "koodi"
  )
  private val relaatioCache = cacheFactory.getInstance[GetRinnasteinenKoodiArvoQuery, String](
    config.integrations.koodistoCacheHours.hours.toMillis,
    this.getClass,
    classOf[String],
    "relaatio"
  )
  private val koodiArvotCache = cacheFactory.getInstance[String, KoodistoKoodiArvot](
    config.integrations.koodistoCacheHours.hours.toMillis,
    this.getClass,
    classOf[KoodistoKoodiArvot],
    "koodi-arvo"
  )
  val maxRetries = config.integrations.koodistoConfig.httpClientMaxRetries

  override def receive: Receive = {
    case q: GetRinnasteinenKoodiArvoQuery =>
      getRinnasteinenKoodiArvo(q) pipeTo sender

    case q: GetKoodi =>
      getKoodi(q.koodistoUri, q.koodiUri) pipeTo sender

    case q: GetKoodistoKoodiArvot =>
      getKoodistoKoodiArvot(q.koodistoUri) pipeTo sender
  }

  def getKoodistoKoodiArvot(koodistoUri: String): Future[KoodistoKoodiArvot] = {
    val loader: String => Future[Option[KoodistoKoodiArvot]] = { uri =>
      restClient
        .readObject[Seq[Koodi]]("koodisto-service.koodisByKoodisto", koodistoUri)(200, maxRetries)
        .map(koodit => KoodistoKoodiArvot(koodistoUri, koodit.map(_.koodiArvo)))
        .map(Some(_))
    }
    koodiArvotCache.get(koodistoUri, loader).map(_.get)
  }

  def notFound(t: Throwable): Boolean = t match {
    case PreconditionFailedException(_, 500) => true
    case _                                   => false
  }

  def getKoodi(koodistoUri: String, koodiUri: String): Future[Option[Koodi]] = {
    val loader: String => Future[Option[Option[Koodi]]] = { uri =>
      val koodi: Future[Option[Koodi]] = restClient
        .readObject[Koodi]("koodisto-service.koodiByUri", koodistoUri, uri)(200, maxRetries)
        .map(Some(_))
        .recoverWith {
          case t: ExecutionException if t.getCause != null && notFound(t.getCause) =>
            log.warning(s"koodi not found from koodisto $koodistoUri with koodiUri $koodiUri: $t")
            Future.successful(None)
        }
      koodi.map(Option(_))
    }
    koodiCache.get(koodiUri, loader).map(_.get)
  }

  def getRinnasteinenKoodiArvo(q: GetRinnasteinenKoodiArvoQuery): Future[String] = {
    val loader: GetRinnasteinenKoodiArvoQuery => Future[Option[String]] = { query =>
      lazy val url =
        OphUrlProperties.url("koodisto-service.koodisByKoodistoAndArvo", q.koodisto, q.arvo)
      restClient
        .readObject[Seq[Koodi]]("koodisto-service.koodisByKoodistoAndArvo", q.koodisto, q.arvo)(
          200,
          maxRetries
        )
        .map(_.headOption.map(_.koodiUri))
        .flatMap {
          case Some(uri) =>
            val fs = restClient
              .readObject[Seq[Koodi]]("koodisto-service.relaatio", "rinnasteinen", uri)(
                200,
                maxRetries
              )
              .map(_.find(_.koodisto.koodistoUri == q.rinnasteinenKoodistoUri) match {
                case None =>
                  throw RinnasteinenKoodiNotFoundException(
                    s"rinnasteisia koodeja ei löytynyt koodiurilla $uri"
                  )
                case Some(k) => k.koodiArvo
              })
            fs.map(Option(_))
          case None =>
            throw RinnasteinenKoodiNotFoundException(
              s"rinnasteisia koodeja ei löytynyt koodistosta: $url"
            )
        }
    }

    relaatioCache.get(q, loader).flatMap {
      case Some(found) => Future.successful(found)
      case None =>
        Future.failed(
          new RuntimeException(
            s"Something went wrong when retrieving rinnasteinen koodiarvo with $q"
          )
        )
    }
  }
}

case class KoodistoActorRef(actor: AskableActorRef) extends TypedAskableActorRef

class MockKoodistoActor extends Actor {
  override def receive: Actor.Receive = { case q: GetKoodistoKoodiArvot =>
    q.koodistoUri match {
      case "oppiaineetyleissivistava" =>
        sender ! KoodistoKoodiArvot(
          koodistoUri = "oppiaineetyleissivistava",
          arvot = Seq(
            "AI",
            "A1",
            "A12",
            "A2",
            "A22",
            "B1",
            "B2",
            "B22",
            "B23",
            "B3",
            "B32",
            "B33",
            "BI",
            "FI",
            "FY",
            "GE",
            "HI",
            "KE",
            "KO",
            "KS",
            "KT",
            "KU",
            "LI",
            "MA",
            "MU",
            "PS",
            "TE",
            "YH"
          )
        )
      case "kieli" =>
        sender ! KoodistoKoodiArvot(
          koodistoUri = "kieli",
          arvot = Seq("FI", "SV", "EN")
        )
    }
  }
}
