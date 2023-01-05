package fi.vm.sade.hakurekisteri.integration.kouta

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}
import akka.actor.{Actor, ActorLogging}
import akka.pattern.{AskableActorRef, pipe}
import akka.util.Timeout
import com.github.blemale.scaffeine.Scaffeine
import com.google.common.base.{Supplier, Suppliers}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.batch.support.BatchOneApiCallAsMany
import fi.vm.sade.hakurekisteri.integration.haku.{
  GetHautQuery,
  RestHaku,
  RestHakuAika,
  RestHakuResult
}
import fi.vm.sade.hakurekisteri.integration.hakukohde.{
  Hakukohde,
  HakukohdeQuery,
  HakukohteenKoulutuksetQuery
}
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodi, Koodi, KoodistoActorRef}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{
  GetHautQueryFailedException,
  HakukohteenKoulutukset,
  Hakukohteenkoulutus,
  TarjontaKoodi
}
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, OphUrlProperties, VirkailijaRestClient}
import org.joda.time.{LocalDate, LocalDateTime}
import support.TypedAskableActorRef

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class KoutaInternalActor(
  koodistoActorRef: KoodistoActorRef,
  restClient: VirkailijaRestClient,
  config: Config
) extends Actor
    with ActorLogging {
  implicit val defaultTimeout: Timeout = 120.seconds
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

  private val koulutusCache = Scaffeine()
    .expireAfterWrite(30.minutes)
    .buildAsyncFuture[String, HakukohteenKoulutukset](getHakukohteenKoulutuksetForReal)

  private val hakukohdeCache = Scaffeine()
    .expireAfterWrite(30.minutes)
    .buildAsyncFuture[String, KoutaInternalHakukohde](getHakukohdeFromKoutaInternalForReal)

  private val koutaInternalHakukohdeBatcher = new BatchOneApiCallAsMany[KoutaInternalHakukohde](
    poolSize = 2,
    poolName = "KoutaInternalHakukohdeBatcher",
    getHakukohdeFromKoutaInternalBatched,
    oidsAtMostPerSingleApiCall = 500
  )

  val hakuCache = Suppliers.memoizeWithExpiration(
    () =>
      getHautForReal.recoverWith { case ex =>
        log.error(s"Failed to fetch all haut! Retrying..", ex)
        getHautForReal
      },
    30,
    MINUTES
  )

  override def receive: Receive = {
    case GetHautQuery                   => getHaut pipeTo sender
    case q: HakukohdeQuery              => getHakukohde(q.oid) pipeTo sender
    case q: HakukohteenKoulutuksetQuery => getHakukohteenKoulutukset(q.hakukohdeOid) pipeTo sender
    case q: HakukohteetHaussaQuery      => getOrganisationHakukohteetHaussa(q) pipeTo sender
  }

  private def substring(s: String, before: String) = s.substring(0, s.indexOf(before))

  private def getKoodistoUri(koulutusKoodiUri: String): String =
    substring(koulutusKoodiUri, "_")

  private def getKoodiUri(koulutusKoodiUri: String): String =
    substring(koulutusKoodiUri, "#")

  def getOrganisationHakukohteetHaussa(
    q: HakukohteetHaussaQuery
  ): Future[List[KoutaInternalHakukohdeLite]] = {
    findTarjoajanHakukohteet(q)
  }

  def getHakukohteenKoulutukset(hakukohdeOid: String): Future[HakukohteenKoulutukset] =
    koulutusCache.get(hakukohdeOid)

  private def toHakukohteenKoulutus(
    koulutus: KoutaInternalKoulutus,
    hakukohde: KoutaInternalHakukohde
  )(koodi: String) =
    Hakukohteenkoulutus(
      komoOid = koulutus.oid,
      tkKoulutuskoodi = koodi,
      kkKoulutusId = None,
      koulutuksenAlkamiskausi = hakukohde.paateltyAlkamiskausi.map(ak =>
        ak match {
          case kausi if kausi.kausiUri.startsWith("kausi_k") => TarjontaKoodi(Some("K"))
          case kausi if kausi.kausiUri.startsWith("kausi_s") => TarjontaKoodi(Some("S"))
          case unknown =>
            log.warning("Unknown alkamiskausiKoodiUri: " + unknown)
            TarjontaKoodi(None)
        }
      ),
      koulutuksenAlkamisvuosi =
        hakukohde.paateltyAlkamiskausi.map(ak => Integer.parseInt(ak.vuosi)),
      koulutuksenAlkamisPvms = None,
      koulutusohjelma = None
    )

  def getHakukohteenKoulutuksetForReal(hakukohdeOid: String): Future[HakukohteenKoulutukset] =
    for {
      hakukohde <- getHakukohdeFromKoutaInternal(hakukohdeOid)
      toteutus <- getToteutusFromKoutaInternal(hakukohde.toteutusOid)
      koulutus <- getKoulutusFromKoutaInternal(toteutus.koulutusOid)
      koodit <- getKoodit(koulutus)
    } yield HakukohteenKoulutukset(
      hakukohdeOid = hakukohdeOid,
      ulkoinenTunniste = hakukohde.externalId,
      koulutukset = (koodit, koulutus.johtaaTutkintoon) match {
        case (k, _) if k.nonEmpty =>
          k.map(toHakukohteenKoulutus(koulutus, hakukohde)).toSeq
        case (_, Some(false)) =>
          // tutkintoon johtamattomalla ei välttämättä ole koulutuskoodeja koutassa
          Seq(toHakukohteenKoulutus(koulutus, hakukohde)("999999"))
        case _ =>
          Seq()
      }
    )

  private def getKoodit(koulutus: KoutaInternalKoulutus): Future[Set[String]] = {
    Future.sequence(
      koulutus.koulutusKoodiUrit.map(koulutusKoodiUri => getKoodi(koulutusKoodiUri))
    )
  }

  private def getKoodi(koulutusKoodiUri: String): Future[String] = {
    (koodistoActorRef.actor ? GetKoodi(
      koodistoUri = getKoodistoUri(koulutusKoodiUri),
      koodiUri = getKoodiUri(koulutusKoodiUri)
    )).mapTo[Option[Koodi]].map(_.get.koodiArvo)
  }

  def getHaut: Future[RestHakuResult] = hakuCache.get()

  def getHautForReal: Future[RestHakuResult] =
    restClient
      .readObject[List[KoutaInternalRestHaku]]("kouta-internal.haku.search.all")(200, 3)
      .map(_.map(_.toRestHaku))
      .map(RestHakuResult)
      .recover { case t: Throwable =>
        log.error(t, "error retrieving all hakus from kouta-internal")
        throw GetHautQueryFailedException("error retrieving all hakus from kouta-internal", t)
      }

  /*
  def getHakukohde(hakukohdeOid: String): Future[Option[Hakukohde]] =
    getHakukohdeFromKoutaInternal(hakukohdeOid)
      .flatMap(hakukohde => {
        getToteutusFromKoutaInternal(hakukohde.toteutusOid)
          .map(toteutus => {
            Some(
              Hakukohde(
                oid = hakukohde.oid,
                hakukohdeKoulutusOids = Seq(toteutus.koulutusOid),
                ulkoinenTunniste = hakukohde.externalId,
                tarjoajaOids = toteutus.tarjoajat,
                hakukohteenNimet = hakukohde.nimi,
                alinValintaPistemaara = None
              )
            )
          })
      })*/

  def getHakukohde(hakukohdeOid: String): Future[Option[Hakukohde]] =
    getHakukohdeFromKoutaInternal(hakukohdeOid)
      .map(hk => Some(hk.toHakukohde))

  private def getKoulutusFromKoutaInternal(koulutusOid: String) =
    restClient.readObject[KoutaInternalKoulutus]("kouta-internal.koulutus", koulutusOid)(200, 3)

  private def getHakukohdeFromKoutaInternal(hakukohdeOid: String): Future[KoutaInternalHakukohde] =
    hakukohdeCache.get(hakukohdeOid)

  private def getHakukohdeFromKoutaInternalBatched(
    hakukohdeOids: Set[String]
  ): Future[List[KoutaInternalHakukohde]] = {
    restClient
      .postObjectWithCodes[Set[String], List[KoutaInternalHakukohde]](
        "kouta-internal.hakukohde.batch",
        Seq(200),
        2,
        hakukohdeOids,
        basicAuth = false
      )
  }

  private def getHakukohdeFromKoutaInternalForReal(
    hakukohdeOid: String
  ): Future[KoutaInternalHakukohde] =
    koutaInternalHakukohdeBatcher.batch(hakukohdeOid, hk => hakukohdeOid.equals(hk.oid))

  private def getToteutusFromKoutaInternal(toteutusOid: String): Future[KoutaInternalToteutus] =
    restClient
      .readObject[KoutaInternalToteutus]("kouta-internal.toteutus", toteutusOid)(200, 3)

  private def getHakuFromKoutaInternal(hakuOid: String): Future[KoutaInternalRestHaku] = restClient
    .readObject[KoutaInternalRestHaku]("kouta-internal.haku", hakuOid)(200, 3)

  private def findTarjoajanHakukohteet(q: HakukohteetHaussaQuery) = {
    val orgParam = q.organisaatioOid.map(oo => "&tarjoaja=" + oo).getOrElse("")
    val koodiParam = q.hakukohdeKoodiUri.map(k => "&hakukohdeKoodiUri=" + k).getOrElse("")
    val url =
      OphUrlProperties.url("kouta-internal.hakukohde.search", q.hakuOid) + orgParam + koodiParam

    restClient
      .readObjectFromUrl[List[KoutaInternalHakukohdeLite]](
        url,
        List(200),
        3
      )
  }

}

case class KoutaInternalActorRef(actor: AskableActorRef) extends TypedAskableActorRef

class MockKoutaInternalActor(koodistoActorRef: KoodistoActorRef, config: Config)
    extends KoutaInternalActor(koodistoActorRef, null, config)

case class KoutaInternalRestHakuAika(alkaa: String, paattyy: Option[String]) {
  def toRestHakuAika: RestHakuAika = {
    val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("Europe/Helsinki"))
    RestHakuAika(
      alkuPvm = Instant.from(formatter.parse(alkaa)).toEpochMilli,
      loppuPvm = paattyy.map(p => Instant.from(formatter.parse(p)).toEpochMilli)
    )
  }

  def getHakukausiVuosi: Int = {
    paattyy
      .map(p => new LocalDateTime(p).getYear)
      .getOrElse(new LocalDateTime(alkaa).getYear)
  }

  def getHakukausiUri: String = {
    paattyy
      .map(p => new LocalDateTime(p).getMonthOfYear)
      .getOrElse(new LocalDateTime(alkaa).getMonthOfYear)
  } match {
    case m if m >= 1 && m <= 7  => "kausi_k#1"
    case m if m >= 8 && m <= 12 => "kausi_s#1"
    case _                      => ""
  }
}

case class KoodiUri(koodiUri: String)

case class KoulutuksenAlkamiskausi(
  koulutuksenAlkamiskausi: Option[KoodiUri],
  koulutuksenAlkamisvuosi: Option[String]
)

case class KoutaHakuMetadata(koulutuksenAlkamiskausi: Option[KoulutuksenAlkamiskausi])

case class KoutaInternalRestHaku(
  oid: Option[String],
  tila: String,
  nimi: Map[String, String],
  hakutapaKoodiUri: String,
  kohdejoukkoKoodiUri: Option[String],
  hakuajat: List[KoutaInternalRestHakuAika],
  kohdejoukonTarkenneKoodiUri: Option[String],
  metadata: KoutaHakuMetadata,
  hakulomakeAtaruId: Option[String]
) {
  def toRestHaku: RestHaku = RestHaku(
    oid = oid,
    hakuaikas = hakuajat.map(_.toRestHakuAika),
    nimi = nimi.foldLeft(Map[String, String]())((acc, x) => {
      acc ++ Map(s"kieli_${x._1}" -> x._2)
    }),
    hakukausiUri = hakuajat
      .sortBy(ha => ha.alkaa)
      .headOption
      .map(ha => ha.getHakukausiUri)
      .getOrElse(""),
    hakutapaUri = hakutapaKoodiUri,
    hakukausiVuosi = hakuajat
      .sortBy(ha => ha.alkaa)
      .headOption
      .map(ha => ha.getHakukausiVuosi)
      .getOrElse(new LocalDate().getYear),
    koulutuksenAlkamiskausiUri =
      metadata.koulutuksenAlkamiskausi.flatMap(_.koulutuksenAlkamiskausi.map(_.koodiUri)),
    koulutuksenAlkamisVuosi =
      metadata.koulutuksenAlkamiskausi.flatMap(_.koulutuksenAlkamisvuosi.map(_.toInt)),
    kohdejoukkoUri = kohdejoukkoKoodiUri,
    kohdejoukonTarkenne = kohdejoukonTarkenneKoodiUri,
    tila = tila,
    hakulomakeAtaruId = hakulomakeAtaruId
  )
}

case class KoutaInternalKoulutus(
  oid: String,
  koulutusKoodiUrit: Set[String],
  johtaaTutkintoon: Option[Boolean]
)

case class HakukohteenTiedot(koodiUri: String)

case class PaateltyAlkamiskausi(
  source: String, //lähde-entiteetin oid (hakukohde, haku tai toteutus)
  kausiUri: String,
  vuosi: String
)

//Kouta-internal saattaa palauttaa joitakin hakukohteita, jotka eivät parsiudu KoutaInternalHakukohteiksi esim. puuttuvan tarjoajan tai toteutusOidin takia. Jos vain oid kiinnostaa, tämä toimii silti.
case class KoutaInternalHakukohdeLite(oid: String, hakukohde: Option[HakukohteenTiedot])

case class KoutaInternalHakukohde(
  oid: String,
  toteutusOid: String,
  nimi: Map[String, String],
  kaytetaanHaunAlkamiskautta: Option[Boolean],
  hakuOid: String,
  externalId: Option[String],
  tarjoaja: String,
  paateltyAlkamiskausi: Option[PaateltyAlkamiskausi]
) {
  def toHakukohde(): Hakukohde =
    Hakukohde(
      oid = oid,
      hakukohteenNimet = nimi,
      hakukohdeKoulutusOids = Seq(toteutusOid),
      ulkoinenTunniste = externalId,
      tarjoajaOids = Some(Set(tarjoaja)),
      alinValintaPistemaara = None
    )
}

case class KoutaToteutusOpetustiedot(
  alkamiskausiKoodiUri: Option[String],
  alkamisvuosi: Option[String]
)

case class KoutaToteutusMetadata(opetus: Option[KoutaToteutusOpetustiedot])

case class KoutaInternalToteutus(
  tarjoajat: Option[Set[String]],
  koulutusOid: String,
  metadata: Option[KoutaToteutusMetadata]
)

case class HakukohteetHaussaQuery(
  hakuOid: String,
  organisaatioOid: Option[String],
  hakukohdeKoodiUri: Option[String]
)
