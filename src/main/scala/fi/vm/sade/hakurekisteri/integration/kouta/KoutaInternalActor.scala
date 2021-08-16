package fi.vm.sade.hakurekisteri.integration.kouta

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId}

import akka.actor.{Actor, ActorLogging}
import akka.pattern.{AskableActorRef, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
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
  Hakukohteenkoulutus
}
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import org.joda.time.LocalDate
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

  override def receive: Receive = {
    case GetHautQuery                   => getHaut pipeTo sender
    case q: HakukohdeQuery              => getHakukohde(q.oid) pipeTo sender
    case q: HakukohteenKoulutuksetQuery => getHakukohteenKoulutukset(q.hakukohdeOid) pipeTo sender
  }

  private def substring(s: String, before: String) = s.substring(0, s.indexOf(before))

  private def getKoodistoUri(koulutus: KoutaInternalKoulutus): String =
    substring(koulutus.koulutusKoodiUri, "_")

  private def getKoodiUri(koulutus: KoutaInternalKoulutus): String =
    substring(koulutus.koulutusKoodiUri, "#")

  def getHakukohteenKoulutukset(hakukohdeOid: String): Future[HakukohteenKoulutukset] =
    for {
      hakukohde <- getHakukohdeFromKoutaInternal(hakukohdeOid)
      toteutus <- getToteutusFromKoutaInternal(hakukohde.toteutusOid)
      koulutus <- getKoulutusFromKoutaInternal(toteutus.koulutusOid)
      koodi <- getKoodi(koulutus)
    } yield HakukohteenKoulutukset(
      hakukohdeOid = hakukohdeOid,
      ulkoinenTunniste =
        Some("FIXME: NEEDS SPECIFICATION HOW TO DEFINE FOR HAKUKOHDE IN KOUTA-INTERNAL"),
      koulutukset = Seq(
        Hakukohteenkoulutus(
          komoOid = koulutus.oid,
          tkKoulutuskoodi = koodi.koodiArvo,
          kkKoulutusId = None,
          koulutuksenAlkamiskausi = None,
          koulutuksenAlkamisvuosi = None,
          koulutuksenAlkamisPvms = None,
          koulutusohjelma = None
        )
      )
    )

  private def getKoodi(koulutus: KoutaInternalKoulutus): Future[Koodi] = {
    (koodistoActorRef.actor ? GetKoodi(
      koodistoUri = getKoodistoUri(koulutus),
      koodiUri = getKoodiUri(koulutus)
    )).mapTo[Option[Koodi]].map(_.get)
  }

  def getHaut: Future[RestHakuResult] =
    restClient
      .readObject[List[KoutaInternalRestHaku]]("kouta-internal.haku.search.all")(200)
      .map(_.map(_.toRestHaku))
      .map(RestHakuResult)
      .recover { case t: Throwable =>
        log.error(t, "error retrieving all hakus from kouta-internal")
        throw GetHautQueryFailedException("error retrieving all hakus from kouta-internal", t)
      }

  def getHakukohde(hakukohdeOid: String): Future[Option[Hakukohde]] =
    getHakukohdeFromKoutaInternal(hakukohdeOid)
      .flatMap(hakukohde => {
        getToteutusFromKoutaInternal(hakukohde.toteutusOid)
          .map(toteutus => {
            Some(
              Hakukohde(
                oid = hakukohde.oid,
                hakukohdeKoulutusOids = Seq(toteutus.koulutusOid),
                ulkoinenTunniste = None,
                tarjoajaOids = toteutus.tarjoajat,
                hakukohteenNimet = hakukohde.nimi,
                alinValintaPistemaara = None
              )
            )
          })
      })

  private def getKoulutusFromKoutaInternal(koulutusOid: String) =
    restClient.readObject[KoutaInternalKoulutus]("kouta-internal.koulutus", koulutusOid)(200)

  private def getHakukohdeFromKoutaInternal(hakukohdeOid: String) = restClient
    .readObject[KoutaInternalHakukohde]("kouta-internal.hakukohde", hakukohdeOid)(200)

  private def getToteutusFromKoutaInternal(toteutusOid: String) = restClient
    .readObject[KoutaInternalToteutus]("kouta-internal.toteutus", toteutusOid)(200)
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
}

case class KoutaInternalRestHaku(
  oid: Option[String],
  tila: String,
  nimi: Map[String, String],
  hakutapaKoodiUri: String,
  kohdejoukkoKoodiUri: Option[String],
  hakuajat: List[KoutaInternalRestHakuAika],
  alkamiskausiKoodiUri: Option[String],
  alkamisvuosi: Option[String],
  kohdejoukonTarkenneKoodiUri: Option[String]
) {
  def toRestHaku: RestHaku = RestHaku(
    oid = oid,
    hakuaikas = hakuajat.map(_.toRestHakuAika),
    nimi = nimi.foldLeft(Map[String, String]())((acc, x) => {
      acc ++ Map(s"kieli_${x._1}" -> x._2)
    }),
    hakukausiUri = alkamiskausiKoodiUri.orNull,
    hakutapaUri = hakutapaKoodiUri,
    hakukausiVuosi = new LocalDate().getYear,
    koulutuksenAlkamiskausiUri = alkamiskausiKoodiUri,
    koulutuksenAlkamisVuosi = alkamisvuosi.map(_.toInt),
    kohdejoukkoUri = kohdejoukkoKoodiUri,
    kohdejoukonTarkenne = kohdejoukonTarkenneKoodiUri,
    tila = tila
  )
}

case class KoutaInternalKoulutus(oid: String, koulutusKoodiUri: String)

case class KoutaInternalHakukohde(oid: String, toteutusOid: String, nimi: Map[String, String]) {
  def toHakukohde(tarjoajaOids: Option[Set[String]]): Hakukohde =
    Hakukohde(
      oid = oid,
      hakukohteenNimet = nimi,
      hakukohdeKoulutusOids = Seq(toteutusOid),
      ulkoinenTunniste = None,
      tarjoajaOids = tarjoajaOids,
      alinValintaPistemaara = None
    )
}

case class KoutaInternalToteutus(tarjoajat: Option[Set[String]], koulutusOid: String)
