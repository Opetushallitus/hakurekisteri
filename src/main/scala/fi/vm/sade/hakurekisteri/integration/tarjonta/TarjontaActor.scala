package fi.vm.sade.hakurekisteri.integration.tarjonta

import akka.actor.{Actor, ActorLogging, ActorSystem}
import akka.pattern.{AskableActorRef, pipe}
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.haku.{
  GetHautQuery,
  RestHaku,
  RestHakuAika,
  RestHakuResult
}
import fi.vm.sade.hakurekisteri.integration.hakukohde.{Hakukohde, HakukohdeQuery}
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.tools.RicherString._
import fi.vm.sade.hakurekisteri.{Config, Oids}
import fi.vm.sade.properties.OphProperties
import org.joda.time.LocalDate
import support.TypedAskableActorRef

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class GetKomoQuery(oid: String)

case class TarjontaRestHakuResult(result: List[TarjontaRestHaku]) {
  def toRestHakuResult: RestHakuResult = RestHakuResult(result.map(_.toRestHaku))
}

case class GetHautQueryFailedException(m: String, cause: Throwable) extends Exception(m, cause)

case class TarjontaRestHaku(
  oid: Option[String],
  hakuaikas: List[TarjontaRestHakuAika],
  nimi: Map[String, String],
  hakukausiUri: String,
  hakutapaUri: String,
  hakukausiVuosi: Int,
  koulutuksenAlkamiskausiUri: Option[String],
  koulutuksenAlkamisVuosi: Option[Int],
  kohdejoukkoUri: Option[String],
  kohdejoukonTarkenne: Option[String],
  tila: String,
  hakutyyppiUri: String
) {
  def isJatkotutkintohaku = kohdejoukonTarkenne.exists(_.startsWith("haunkohdejoukontarkenne_3#"))

  def toRestHaku: RestHaku = RestHaku(
    oid = oid,
    hakuaikas = hakuaikas.map(_.toRestHakuAika),
    nimi = nimi,
    hakukausiUri = hakukausiUri,
    hakutapaUri = hakutapaUri,
    hakukausiVuosi = hakukausiVuosi,
    koulutuksenAlkamiskausiUri = koulutuksenAlkamiskausiUri,
    koulutuksenAlkamisVuosi = koulutuksenAlkamisVuosi,
    kohdejoukkoUri = kohdejoukkoUri,
    kohdejoukonTarkenne = kohdejoukonTarkenne,
    tila = tila
  )
}

case class TarjontaRestHakuAika(alkuPvm: Long, loppuPvm: Option[Long]) {
  def toRestHakuAika: RestHakuAika = RestHakuAika(alkuPvm = alkuPvm, loppuPvm = loppuPvm)
}

case class TarjontaResultResponse[T](result: T)

@SerialVersionUID(1)
case class KomoResponse(oid: String, komo: Option[Komo])

case class TarjontaKoodi(arvo: Option[String])

case class Koulutus(
  oid: String,
  komoOid: String,
  tunniste: Option[String],
  kandidaatinKoulutuskoodi: Option[TarjontaKoodi],
  koulutuksenAlkamiskausi: Option[TarjontaKoodi],
  koulutuksenAlkamisvuosi: Option[Int],
  koulutuksenAlkamisPvms: Option[Set[Long]],
  koulutusohjelma: Koulutusohjelma
)

case class HakukohdeOid(oid: String)

@SerialVersionUID(1)
case class TarjontaHakukohde(
  oid: String,
  hakukohteenNimet: Map[String, String],
  hakukohdeKoulutusOids: Seq[String],
  ulkoinenTunniste: Option[String],
  tarjoajaOids: Option[Set[String]],
  alinValintaPistemaara: Option[Int]
) {
  def toHakukohde: Hakukohde = Hakukohde(
    oid = oid,
    hakukohteenNimet = hakukohteenNimet,
    hakukohdeKoulutusOids = hakukohdeKoulutusOids,
    ulkoinenTunniste = ulkoinenTunniste,
    tarjoajaOids = tarjoajaOids,
    alinValintaPistemaara = alinValintaPistemaara
  )
}

case class Koulutusohjelma(tekstis: Map[String, String])

case class Hakukohteenkoulutus(
  komoOid: String,
  tkKoulutuskoodit: Set[String],
  kkKoulutusId: Option[String],
  koulutuksenAlkamiskausi: Option[TarjontaKoodi],
  koulutuksenAlkamisvuosi: Option[Int],
  koulutuksenAlkamisPvms: Option[Set[Long]],
  koulutusohjelma: Option[Koulutusohjelma]
)

case class HakukohteenKoulutukset(
  hakukohdeOid: String,
  ulkoinenTunniste: Option[String],
  koulutukset: Seq[Hakukohteenkoulutus]
)

class TarjontaException(val m: String) extends Exception(m)
case class HakukohdeNotFoundException(message: String) extends TarjontaException(message)
case class KoulutusNotFoundException(message: String) extends TarjontaException(message)
case class KomoNotFoundException(message: String) extends TarjontaException(message)

class TarjontaActor(restClient: VirkailijaRestClient, config: Config, cacheFactory: CacheFactory)
    extends Actor
    with ActorLogging {
  private val koulutusCache = cacheFactory.getInstance[String, HakukohteenKoulutukset](
    config.integrations.tarjontaCacheHours.hours.toMillis,
    this.getClass,
    classOf[HakukohteenKoulutukset],
    "koulutus"
  )
  private val komoCache = cacheFactory.getInstance[String, KomoResponse](
    config.integrations.tarjontaCacheHours.hours.toMillis,
    this.getClass,
    classOf[KomoResponse],
    "komo"
  )
  private val hakukohdeCache = cacheFactory.getInstance[String, Option[Hakukohde]](
    config.integrations.tarjontaCacheHours.hours.toMillis,
    this.getClass,
    classOf[Hakukohde],
    "hakukohde"
  )

  val maxRetries = config.integrations.tarjontaConfig.httpClientMaxRetries
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

  override def receive: Receive = {
    case q: GetKomoQuery   => getKomo(q.oid) pipeTo sender
    case q: HakukohdeQuery => getHakukohde(q.oid) pipeTo sender
    case GetHautQuery      => getHaut pipeTo sender
    case oid: HakukohdeOid => getHakukohteenKoulutuksetViaCache(oid) pipeTo sender
  }

  def getKomo(oid: String): Future[KomoResponse] = {
    val loader: String => Future[Option[KomoResponse]] = o =>
      restClient
        .readObject[TarjontaResultResponse[Option[Komo]]]("tarjonta-service.komo", oid)(
          200,
          maxRetries
        )
        .map(res => KomoResponse(oid, res.result))
        .map(Option(_))
    komoCache.get(oid, loader).flatMap {
      case Some(foundKomo) => Future.successful(foundKomo)
      case None            => Future.failed(new RuntimeException(s"Could not retrieve komo $oid"))
    }
  }

  def includeHaku(haku: RestHaku): Boolean = {
    haku.tila == "JULKAISTU" || (haku.tila == "VALMIS" && haku.isJatkotutkintohaku)
  }

  def getHaut: Future[RestHakuResult] = {
    restClient
      .readObject[TarjontaRestHakuResult]("tarjonta-service.haku.findAll")(200)
      .map(_.toRestHakuResult)
      .map(res => res.copy(result = res.result.filter(includeHaku)))
      .recover { case t: Throwable =>
        log.error(t, "error retrieving all hakus")
        throw GetHautQueryFailedException("error retrieving all hakus", t)
      }
  }

  def getKoulutus(oid: String): Future[Seq[Hakukohteenkoulutus]] = {
    val koulutus: Future[Option[Koulutus]] = restClient
      .readObject[TarjontaResultResponse[Option[Koulutus]]]("tarjonta-service.koulutus", oid)(
        200,
        maxRetries
      )
      .map(r => r.result)
    koulutus.flatMap {
      case None => Future.failed(KoulutusNotFoundException(s"koulutus not found with oid $oid"))
      case Some(k) =>
        val fk: Future[Option[Komo]] = getKomo(k.komoOid).map(r => r.komo)
        fk.map {
          case None => throw KomoNotFoundException(s"komo not found with oid ${k.komoOid}")
          case Some(komo) =>
            val kkKoulutusId = k.tunniste.flatMap(_.blankOption)
            val koulutukset = Seq(
              Hakukohteenkoulutus(
                komo.oid,
                Set(komo.koulutuskoodi.arvo),
                kkKoulutusId,
                k.koulutuksenAlkamiskausi,
                k.koulutuksenAlkamisvuosi,
                k.koulutuksenAlkamisPvms,
                Some(k.koulutusohjelma)
              )
            )
            k.kandidaatinKoulutuskoodi
              .flatMap(
                _.arvo.map(a =>
                  koulutukset :+ Hakukohteenkoulutus(
                    komo.oid,
                    Set(a),
                    kkKoulutusId,
                    k.koulutuksenAlkamiskausi,
                    k.koulutuksenAlkamisvuosi,
                    k.koulutuksenAlkamisPvms,
                    Some(k.koulutusohjelma)
                  )
                )
              )
              .getOrElse(koulutukset)
        }
    }
  }

  def getHakukohde(oid: String): Future[Option[Hakukohde]] = {
    val loader: String => Future[Option[Option[Hakukohde]]] = { hakukohdeOid =>
      val result = restClient
        .readObject[TarjontaResultResponse[Option[TarjontaHakukohde]]](
          "tarjonta-service.hakukohde",
          hakukohdeOid
        )(200, maxRetries)
        .map(r => r.result)
        .map(Option(_))
      result.flatMap {
        case None =>
          Future.failed(
            HakukohdeNotFoundException(s"hakukohde not found from Tarjonta with $hakukohdeOid")
          )
        case Some(None) =>
          Future.failed(
            HakukohdeNotFoundException(s"empty hakukohde returned from Tarjonta with $hakukohdeOid")
          )
        case Some(result) => Future.successful(Option(result.map(_.toHakukohde)))
      }
    }
    hakukohdeCache.get(oid, loader).map(_.get)
  }

  def getHakukohteenkoulutukset(oids: Seq[String]): Future[Seq[Hakukohteenkoulutus]] =
    Future.sequence(oids.map(getKoulutus)).map(_.foldLeft(Seq[Hakukohteenkoulutus]())(_ ++ _))

  def getHakukohteenKoulutuksetViaCache(hk: HakukohdeOid): Future[HakukohteenKoulutukset] = {
    val loader: String => Future[Option[HakukohteenKoulutukset]] = hakukohdeOid => {
      restClient
        .readObject[TarjontaResultResponse[Option[TarjontaHakukohde]]](
          "tarjonta-service.hakukohde",
          hk.oid
        )(200, maxRetries)
        .map(r => r.result)
        .flatMap {
          case None =>
            Future.failed(HakukohdeNotFoundException(s"hakukohde not found with oid ${hk.oid}"))
          case Some(h) =>
            for (
              hakukohteenkoulutukset: Seq[Hakukohteenkoulutus] <- getHakukohteenkoulutukset(
                h.hakukohdeKoulutusOids
              )
            ) yield Some(HakukohteenKoulutukset(h.oid, h.ulkoinenTunniste, hakukohteenkoulutukset))
        }
    }

    koulutusCache.get(hk.oid, loader).flatMap {
      case Some(foundHakukohteenKoulutukset) => Future.successful(foundHakukohteenKoulutukset)
      case None =>
        Future.failed(
          new RuntimeException(s"Could not retrieve koulutukset for Hakukohde ${hk.oid}")
        )
    }
  }
}

class MockTarjontaActor(config: Config)(implicit val system: ActorSystem)
    extends TarjontaActor(
      null,
      config,
      CacheFactory.apply(
        new OphProperties().addDefault("suoritusrekisteri.cache.redis.enabled", "false")
      )
    ) {

  override def receive: Receive = {
    case GetKomoQuery(oid) =>
      val response: KomoResponse =
        if (oid == Oids.yotutkintoKomoOid)
          KomoResponse(oid, Some(Komo(oid, Koulutuskoodi("301101"), "TUTKINTO", "LUKIOKOULUTUS")))
        else KomoResponse(oid, None)
      sender ! response

    case GetHautQuery =>
      sender ! TarjontaRestHakuResult(
        List(
          TarjontaRestHaku(
            oid = Some("1.2.3.4"),
            hakuaikas =
              List(TarjontaRestHakuAika(1, Some(new LocalDate().plusMonths(1).toDate.getTime))),
            nimi = Map("kieli_fi" -> "haku 1", "kieli_sv" -> "haku 1", "kieli_en" -> "haku 1"),
            hakukausiUri = "kausi_k#1",
            hakutapaUri = "hakutapa_01#1",
            hakukausiVuosi = new LocalDate().getYear,
            koulutuksenAlkamiskausiUri = Some("kausi_s#1"),
            koulutuksenAlkamisVuosi = Some(new LocalDate().getYear),
            kohdejoukkoUri = Some("haunkohdejoukko_12#1"),
            kohdejoukonTarkenne = None,
            tila = "JULKAISTU",
            hakutyyppiUri = "hakutyyppi_01#1"
          )
        )
      )

    case msg =>
      log.warning(s"not implemented receive(${msg})")
  }
}

case class TarjontaActorRef(actor: AskableActorRef) extends TypedAskableActorRef
