package fi.vm.sade.hakurekisteri.web.kkhakija

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.ensikertalainen.{Ensikertalainen, EnsikertalainenQuery}
import fi.vm.sade.hakurekisteri.hakija.{Hakuehto, Lasnaolo}
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.hakukohde.{
  HakukohdeAggregatorActorRef,
  HakukohteenKoulutuksetQuery
}
import fi.vm.sade.hakurekisteri.integration.hakukohderyhma.IHakukohderyhmaService
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  Henkilo,
  IOppijaNumeroRekisteri,
  Kansalaisuus,
  Kieli
}
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodi, Koodi, KoodistoActorRef}
import fi.vm.sade.hakurekisteri.integration.koski.IKoskiService
import fi.vm.sade.hakurekisteri.integration.parametrit.{ParametritActorRef, UsesPriority}
import fi.vm.sade.hakurekisteri.integration.tarjonta.HakukohteenKoulutukset
import fi.vm.sade.hakurekisteri.integration.valintaperusteet.{
  IValintaperusteetService,
  ValintatapajononTiedot
}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{
  Lukuvuosimaksu,
  LukuvuosimaksuQuery,
  ValintarekisteriActorRef
}
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos._
import fi.vm.sade.hakurekisteri.integration.ytl.YoTutkinto
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.suoritus.{SuoritysTyyppiQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.web.kkhakija.KkHakijaHakemusUtil._
import fi.vm.sade.hakurekisteri.web.kkhakija.KkHakijaUtil._
import org.scalatra.util.RicherString._
import org.slf4j.LoggerFactory

import java.text.SimpleDateFormat
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class KkHakijaQuery(
  oppijanumero: Option[String],
  haku: Option[String],
  organisaatio: Option[String],
  hakukohde: Option[String],
  hakukohderyhma: Option[String],
  palautaKoulusivistyskielet: Boolean,
  hakuehto: Hakuehto.Hakuehto,
  version: Int,
  user: Option[User]
) extends Query {}

object KkHakijaQuery {
  def apply(params: Map[String, String], currentUser: Option[User]): KkHakijaQuery =
    new KkHakijaQuery(
      oppijanumero = params.get("oppijanumero").flatMap(_.blankOption),
      haku = params.get("haku").flatMap(_.blankOption),
      organisaatio = params.get("organisaatio").flatMap(_.blankOption),
      hakukohde = params.get("hakukohde").flatMap(_.blankOption),
      hakukohderyhma = params.get("hakukohderyhma").flatMap(_.blankOption),
      palautaKoulusivistyskielet = Try(params("palautaKoulusivistyskielet").toBoolean).recover {
        case _ => false
      }.get,
      hakuehto = Try(Hakuehto.withName(params("hakuehto"))).recover { case _ =>
        Hakuehto.Kaikki
      }.get,
      version = Try(params("version").toInt).recover { case _ => 2 }.get,
      user = currentUser
    )
}

class KkHakijaService(
  hakemusService: IHakemusService,
  hakupalvelu: Hakupalvelu,
  hakukohderyhmaService: IHakukohderyhmaService,
  hakukohdeAggregator: HakukohdeAggregatorActorRef,
  haut: ActorRef,
  koodisto: KoodistoActorRef,
  oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
  suoritukset: ActorRef,
  valintaTulos: ValintaTulosActorRef,
  valintaRekisteri: ValintarekisteriActorRef,
  valintaperusteetService: IValintaperusteetService,
  koskiService: IKoskiService,
  valintaTulosTimeout: Timeout,
  ensikertalainenActor: ActorRef,
  parameterActor: ParametritActorRef
)(implicit system: ActorSystem) {
  implicit val defaultTimeout: Timeout = 120.seconds
  implicit def executor: ExecutionContext = system.dispatcher

  def getKkHakijat(q: KkHakijaQuery, version: Int): Future[Seq[Hakija]] = {
    val queryFixed =
      q.copy(version = version) //Yhdenmukaistetaan siirtotiedostojono ja suorat rajapintakutsut
    def resolveMultipleHakukohdeOidsAsHakemukset(
      hakukohdeOids: Seq[String]
    ): Future[Seq[HakijaHakemus]] = {
      hakemusService.hakemuksetForHakukohdes(hakukohdeOids.toSet, q.organisaatio)
    }

    def matchHakemusToQuery(hakemus: HakijaHakemus): Boolean = {
      hakemus.personOid.isDefined && hakemus.stateValid && q.haku.forall(
        _ == hakemus.applicationSystemId
      )
    }

    for (
      hakemukset <- queryFixed match {
        case KkHakijaQuery(Some(oppijanumero), _, _, _, _, _, _, _, _) =>
          hakemusService.hakemuksetForPerson(oppijanumero)
        case KkHakijaQuery(None, _, _, Some(hakukohde), _, _, _, _, _) =>
          hakemusService.hakemuksetForHakukohde(hakukohde, q.organisaatio)
        case KkHakijaQuery(None, Some(haku), _, None, Some(hakukohderyhma), _, _, _, _) =>
          getHakukohdeOidsForHakukohdeRyhma(hakukohderyhma, haku)
            .flatMap(resolveMultipleHakukohdeOidsAsHakemukset)
        case _ => Future.failed(KkHakijaParamMissingException)
      };
      hakijat <- fullHakemukset2hakijat(hakemukset.filter(matchHakemusToQuery), version)(queryFixed)
    ) yield hakijat
  }

  private val logger = LoggerFactory.getLogger(this.getClass)

  private def getHakukohdeOidsForHakukohdeRyhma(
    hakukohderyhma: String,
    haku: String
  ): Future[Seq[String]] = {
    hakupalvelu
      .getHakukohdeOids(hakukohderyhma, haku)
      .flatMap(hakukohdeoids => {
        if (hakukohdeoids.isEmpty) {
          hakukohderyhmaService.getHakukohteetOfHakukohderyhma(hakukohderyhma)
        } else {
          Future.successful(hakukohdeoids)
        }
      })
  }

  private def getVastaanottaneetOids(eventualHakijat: Future[Seq[Hakija]]): Future[Seq[String]] = {
    for {
      hakijat <- eventualHakijat
    } yield {
      logger.info(s"Received ${hakijat.size} hakijas for vastaanottaneet filtering.")
      val vastaanottaneetOids = hakijat
        .filter(h =>
          h.hakemukset
            .exists(_.vastaanottotieto.contains(Vastaanottotila.VASTAANOTTANUT))
        )
        .map(_.oppijanumero)
      logger.info(s"Found ${vastaanottaneetOids.size} vastaanottaneet oids.")

      vastaanottaneetOids
    }
  }

  private def withKoulusivistyskieliForVastaanottaneet(
    eventualHakijat: Future[Seq[Hakija]]
  ): Future[Seq[Hakija]] = {
    for {
      hakijat <- eventualHakijat
      vastaanottaneetOids <- getVastaanottaneetOids(eventualHakijat)
      koulusivistyskielet <- koskiService.fetchKoulusivistyskielet(vastaanottaneetOids)
    } yield {
      hakijat.map(h =>
        h.copy(koulusivistyskielet = Some(koulusivistyskielet.getOrElse(h.oppijanumero, Seq.empty)))
      )
    }
  }

  private def fullHakemukset2hakijat(hakemukset: Seq[HakijaHakemus], version: Int)(
    q: KkHakijaQuery
  ): Future[Seq[Hakija]] = {
    val fullHakemusesByHakuOid: Map[String, Seq[HakijaHakemus]] =
      hakemukset.groupBy(_.applicationSystemId)
    Future
      .sequence(fullHakemusesByHakuOid.map { case (hakuOid, fullHakemuses) =>
        (haut ? GetHaku(hakuOid))
          .mapTo[Haku]
          .flatMap(haku =>
            if (haku.kkHaku) {
              q.hakukohderyhma
                .map(getHakukohdeOidsForHakukohdeRyhma(_, haku.oid))
                .getOrElse(Future.successful(Seq()))
                .flatMap(hakukohdeOids => {
                  version match {
                    case 1 =>
                      logger.info("Kkhakijat v{} requested, hakuOid: {}", q.version, hakuOid)
                      kokoHaunTulosIfNoOppijanumero(q, hakuOid).flatMap { kokoHaunTulos =>
                        Future
                          .sequence(
                            fullHakemuses
                              .flatMap(getKkHakijaV1(haku, q, kokoHaunTulos, hakukohdeOids))
                          )
                          .map(_.filter(_.hakemukset.nonEmpty))
                      }
                    case 2 =>
                      logger.info("Kkhakijat v{} requested, hakuOid: {}", q.version, hakuOid)
                      createV2Hakijas(q, fullHakemuses, haku, hakukohdeOids)
                    case 3 =>
                      logger.info("Kkhakijat v{} requested, hakuOid: {}", q.version, hakuOid)
                      createV3Hakijas(q, fullHakemuses, haku, hakukohdeOids)
                    case 4 =>
                      logger.info("Kkhakijat v{} requested, hakuOid: {}", q.version, hakuOid)
                      createV4Hakijas(q, fullHakemuses, haku, hakukohdeOids)
                    case 5 =>
                      logger.info("Kkhakijat v{} requested, hakuOid: {}", q.version, hakuOid)
                      if (q.palautaKoulusivistyskielet) {
                        withKoulusivistyskieliForVastaanottaneet(
                          createV5Hakijas(q, fullHakemuses, haku, hakukohdeOids)
                        )
                      } else {
                        createV5Hakijas(q, fullHakemuses, haku, hakukohdeOids)
                      }
                  }
                })
            } else {
              Future.successful(Seq())
            }
          )
          .recoverWith { case e: HakuNotFoundException =>
            Future.successful(Seq())
          }
      }.toSeq)
      .map(_.foldLeft(Seq[Hakija]())(_ ++ _))
      .map(_.filter(_.hakemukset.nonEmpty))
  }

  private def createV2Hakijas(
    q: KkHakijaQuery,
    hakemukset: Seq[HakijaHakemus],
    haku: Haku,
    hakukohdeOids: Seq[String]
  ) = {
    val maksuvelvollisuudet: Set[String] = getMaksuvelvollisuudet(hakemukset)

    logger.debug(s"Got maksuvelvollisuudet: '$maksuvelvollisuudet'")

    getLukuvuosimaksut(maksuvelvollisuudet, q.user.get.auditSession()).flatMap(lukuvuosimaksut => {
      kokoHaunTulosIfNoOppijanumero(q, haku.oid).flatMap { kokoHaunTulos =>
        val maksusByHakijaAndHakukohde =
          lukuvuosimaksut.groupBy(_.personOid).mapValues(_.toList.groupBy(_.hakukohdeOid))
        Future
          .sequence(
            hakemukset.flatMap(
              getKkHakijaV2(haku, q, kokoHaunTulos, hakukohdeOids, maksusByHakijaAndHakukohde)
            )
          )
          .map(_.filter(_.hakemukset.nonEmpty))
      }
    })
  }

  private def createV3Hakijas(
    q: KkHakijaQuery,
    hakemukset: Seq[HakijaHakemus],
    haku: Haku,
    hakukohdeOids: Seq[String]
  ) = {
    val maksuvelvollisuudet: Set[String] = getMaksuvelvollisuudet(hakemukset)

    getLukuvuosimaksut(maksuvelvollisuudet, q.user.get.auditSession()).flatMap(lukuvuosimaksut => {
      kokoHaunTulosIfNoOppijanumero(q, haku.oid).flatMap { kokoHaunTulos =>
        val maksusByHakijaAndHakukohde =
          lukuvuosimaksut.groupBy(_.personOid).mapValues(_.toList.groupBy(_.hakukohdeOid))
        Future
          .sequence(
            hakemukset.flatMap(
              getKkHakijaV3(haku, q, kokoHaunTulos, hakukohdeOids, maksusByHakijaAndHakukohde)
            )
          )
          .map(_.filter(_.hakemukset.nonEmpty))
      }
    })
  }

  private def createV4Hakijas(
    q: KkHakijaQuery,
    hakemukset: Seq[HakijaHakemus],
    haku: Haku,
    hakukohdeOids: Seq[String]
  ) = {
    val hakemusOid: Option[String] = if (hakemukset.size == 1) Some(hakemukset.last.oid) else None

    val maksuvelvollisuudet: Set[String] = getMaksuvelvollisuudet(hakemukset)

    getLukuvuosimaksut(maksuvelvollisuudet, q.user.get.auditSession()).flatMap(
      (lukuvuosimaksut: Seq[Lukuvuosimaksu]) => {
        getEnoughTuloksesToSatisfyQuery(haku.oid, hakemusOid).flatMap { sijoittelunTulokset =>
          {
            val maksusByHakijaAndHakukohde =
              lukuvuosimaksut.groupBy(_.personOid).mapValues(_.toList.groupBy(_.hakukohdeOid))
            valintaperusteetService
              .getValintatapajonot(sijoittelunTulokset.valintatapajono.values.toSet)
              .flatMap(jonotiedot =>
                Future
                  .sequence(
                    hakemukset.flatMap(
                      getKkHakijaV4(
                        haku,
                        q,
                        sijoittelunTulokset,
                        hakukohdeOids,
                        maksusByHakijaAndHakukohde,
                        jonotiedot,
                        getMasterHenkilosForFullHakemukses(hakemukset)
                      )
                    )
                  )
                  .map(_.filter(_.hakemukset.nonEmpty))
              )
          }
        }
      }
    )
  }

  private def getMasterHenkilosForFullHakemukses(
    hakemukset: Seq[HakijaHakemus]
  ): Future[Map[String, Henkilo]] = {
    oppijaNumeroRekisteri.getByOids(
      hakemukset
        .filter {
          case _: FullHakemus => true
          case _              => false
        }
        .flatMap(hakemus => hakemus.personOid)
        .toSet
    )
  }
  private def createV5Hakijas(
    q: KkHakijaQuery,
    hakemukset: Seq[HakijaHakemus],
    haku: Haku,
    hakukohdeOids: Seq[String]
  ) = {
    val hakemusOid: Option[String] = if (hakemukset.size == 1) Some(hakemukset.last.oid) else None
    val henkiloOids = hakemukset.flatMap(h => h.personOid).toSet

    val usePriority =
      (parameterActor.actor ? UsesPriority(haku.oid))(60.seconds).mapTo[Boolean]

    val ensikertalaisuudet: Future[Map[String, Boolean]] = Future
      .sequence(
        henkiloOids
          .grouped(10000)
          .map(oidBatch =>
            (ensikertalainenActor ? EnsikertalainenQuery(
              henkiloOids = oidBatch,
              hakuOid = haku.oid
            ))(
              5.minutes
            ).mapTo[Seq[Ensikertalainen]]
              .map(_.groupBy(_.henkiloOid).mapValues(_.head.ensikertalainen))
          )
      )
      .map(_.reduce((a: Map[String, Boolean], b: Map[String, Boolean]) => a ++ b))

    val maksuvelvollisuudet: Set[String] = getMaksuvelvollisuudet(hakemukset)

    getLukuvuosimaksut(maksuvelvollisuudet, q.user.get.auditSession()).flatMap(
      (lukuvuosimaksut: Seq[Lukuvuosimaksu]) => {
        getEnoughTuloksesToSatisfyQuery(haku.oid, hakemusOid).flatMap { sijoittelunTulokset =>
          {
            val maksusByHakijaAndHakukohde =
              lukuvuosimaksut.groupBy(_.personOid).mapValues(_.toList.groupBy(_.hakukohdeOid))
            valintaperusteetService
              .getValintatapajonot(sijoittelunTulokset.valintatapajono.values.toSet)
              .flatMap(jonotiedot =>
                Future
                  .sequence(
                    hakemukset.flatMap(
                      getKkHakijaV5(
                        haku,
                        q,
                        sijoittelunTulokset,
                        hakukohdeOids,
                        maksusByHakijaAndHakukohde,
                        jonotiedot,
                        ensikertalaisuudet,
                        usePriority,
                        getMasterHenkilosForFullHakemukses(hakemukset)
                      )
                    )
                  )
                  .map(_.filter(_.hakemukset.nonEmpty))
              )
          }
        }
      }
    )
  }

  private def kokoHaunTulosIfNoOppijanumero(
    q: KkHakijaQuery,
    hakuOid: String
  ): Future[Option[SijoitteluTulos]] = q.oppijanumero match {
    case Some(_) => Future.successful(None)
    case None =>
      (valintaTulos.actor ? HaunValintatulos(hakuOid))(valintaTulosTimeout)
        .mapTo[SijoitteluTulos]
        .map(Some(_))
  }

  private def getEnoughTuloksesToSatisfyQuery(
    hakuOid: String,
    hakemusOid: Option[String]
  ): Future[SijoitteluTulos] = hakemusOid match {
    case Some(h) =>
      logger.debug("Getting tulokset for one hakemus: {}", h)
      (valintaTulos.actor ? HakemuksenValintatulos(hakuOid, h)).mapTo[SijoitteluTulos]
    case None =>
      logger.debug("Getting tulokset for whole haku: {}", hakuOid)
      (valintaTulos.actor ? HaunValintatulos(hakuOid))(valintaTulosTimeout).mapTo[SijoitteluTulos]
  }

  private val Pattern = "preference(\\d+)-Koulutus-id".r

  private def getLukuvuosimaksut(
    hakukohdeOids: Set[String],
    auditSession: AuditSessionRequest
  ): Future[Seq[Lukuvuosimaksu]] = {
    (valintaRekisteri.actor ? LukuvuosimaksuQuery(hakukohdeOids, auditSession))
      .mapTo[Seq[Lukuvuosimaksu]]
  }

  private def getHakemukset(
    haku: Haku,
    hakemus: HakijaHakemus,
    lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]],
    q: KkHakijaQuery,
    kokoHaunTulos: Option[SijoitteluTulos],
    hakukohdeOids: Seq[String]
  ): Future[Seq[Hakemus]] = {
    ((kokoHaunTulos, q.oppijanumero) match {
      case (Some(tulos), _) => Future.successful(tulos)
      case (None, Some(_)) =>
        (valintaTulos.actor ? HakemuksenValintatulos(hakemus.applicationSystemId, hakemus.oid))
          .mapTo[SijoitteluTulos]
      case (None, None) =>
        (valintaTulos.actor ? HaunValintatulos(hakemus.applicationSystemId)).mapTo[SijoitteluTulos]
    }).flatMap(tulos =>
      Future
        .sequence(
          extractHakemukset(
            hakemus,
            lukuvuosimaksutByHakukohdeOid,
            q,
            haku,
            tulos,
            hakukohdeOids,
            Seq.empty
          )
        )
        .map(_.flatten)
    )
  }

  private def getHakemuksetV4(
    haku: Haku,
    hakemus: HakijaHakemus,
    lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]],
    q: KkHakijaQuery,
    tulokset: SijoitteluTulos,
    hakukohdeOids: Seq[String],
    jonotiedot: Seq[ValintatapajononTiedot]
  ): Future[Seq[Hakemus]] = {
    Future
      .sequence(
        extractHakemukset(
          hakemus,
          lukuvuosimaksutByHakukohdeOid,
          q,
          haku,
          tulokset,
          hakukohdeOids,
          jonotiedot
        )
      )
      .map(_.flatten)
  }

  private def getHakemuksetV5(
    haku: Haku,
    hakemus: HakijaHakemus,
    lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]],
    q: KkHakijaQuery,
    tulokset: SijoitteluTulos,
    hakukohdeOids: Seq[String],
    jonotiedot: Seq[ValintatapajononTiedot],
    usePriority: Future[Boolean]
  ): Future[Seq[Hakemus]] = {
    Future
      .sequence(
        extractHakemukset(
          hakemus,
          lukuvuosimaksutByHakukohdeOid,
          q,
          haku,
          tulokset,
          hakukohdeOids,
          jonotiedot,
          usePriority,
          useV5Format = true
        )
      )
      .map(_.flatten)
  }

  private def extractHakemukset(
    hakemus: HakijaHakemus,
    lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]],
    q: KkHakijaQuery,
    haku: Haku,
    sijoitteluTulos: SijoitteluTulos,
    hakukohdeOids: Seq[String],
    jonotiedot: Seq[ValintatapajononTiedot] = Seq.empty,
    usePriority: Future[Boolean] = Future.successful(false),
    useV5Format: Boolean = false
  ): Seq[Future[Option[Hakemus]]] = {
    (for {
      hakutoiveet: Seq[HakutoiveDTO] <- hakemus.hakutoiveet
    } yield hakutoiveet.map(toive =>
      if (
        toive.koulutusId.isDefined && queryMatches(q, toive, hakukohdeOids ++ q.hakukohde.toSeq)
      ) {
        if (useV5Format) {
          extractSingleHakemusV5(
            hakemus,
            lukuvuosimaksutByHakukohdeOid,
            q,
            toive,
            haku,
            sijoitteluTulos,
            jonotiedot,
            usePriority
          )
        } else {
          extractSingleHakemus(
            hakemus,
            lukuvuosimaksutByHakukohdeOid,
            q,
            toive,
            haku,
            sijoitteluTulos,
            jonotiedot
          )
        }
      } else Future.successful(None)
    )).getOrElse(Seq.empty)
  }

  private def queryMatches(
    q: KkHakijaQuery,
    toive: HakutoiveDTO,
    hakukohdeOids: Seq[String]
  ): Boolean = {
    (hakukohdeOids.isEmpty || toive.koulutusId.exists(hakukohdeOids.contains)) &&
    q.organisaatio.forall(toive.organizationParentOids.contains) &&
    toive.organizationParentOids.intersect(getKnownOrganizations(q.user)).nonEmpty
  }

  private def extractSingleHakemusV5(
    h: HakijaHakemus,
    lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]],
    q: KkHakijaQuery,
    toive: HakutoiveDTO,
    haku: Haku,
    sijoitteluTulos: SijoitteluTulos,
    jonotiedot: Seq[ValintatapajononTiedot],
    usePriorityFuture: Future[Boolean]
  ): Future[Option[Hakemus]] = h match {
    case hakemus: FullHakemus =>
      val hakemusOid = hakemus.oid
      val hakukohdeOid = toive.koulutusId.getOrElse("")
      val preferenceEligibilities = hakemus.preferenceEligibilities
      val hakukelpoisuus = getHakukelpoisuus(hakukohdeOid, preferenceEligibilities)
      for {
        hakukohteenkoulutukset: HakukohteenKoulutukset <-
          (hakukohdeAggregator.actor ? HakukohteenKoulutuksetQuery(hakukohdeOid))
            .mapTo[HakukohteenKoulutukset]
        kausi: String <- getKausi(haku.kausi, hakemusOid, koodisto)
        jononTyyppi: Option[String] <- jononTyyppiForHakemusF(
          sijoitteluTulos.valintatila.get(hakemusOid, hakukohdeOid),
          sijoitteluTulos.valintatapajono.get(hakemusOid, hakukohdeOid),
          jonotiedot
        )
        lasnaolot: Seq[Lasnaolo] <- getLasnaolot(
          sijoitteluTulos,
          hakukohdeOid,
          hakemusOid,
          hakukohteenkoulutukset.koulutukset
        )
      } yield {
        if (matchHakuehto(sijoitteluTulos, hakemusOid, hakukohdeOid)(q.hakuehto)) {
          for {
            answers <- hakemus.answers
          } yield Hakemus(
            haku = hakemus.applicationSystemId,
            hakuVuosi = haku.vuosi,
            hakuKausi = kausi,
            hakemusnumero = hakemusOid,
            hakemusViimeinenMuokkausAikaleima = None,
            hakemusJattoAikaleima = None,
            valinnanAikaleima = None,
            organisaatio = toive.organizationOid.getOrElse(""),
            hakukohde = toive.koulutusId.getOrElse(""),
            hakutoivePrioriteetti = None,
            hakukohdeKkId = hakukohteenkoulutukset.ulkoinenTunniste,
            avoinVayla = None, // TODO valinnoista?
            valinnanTila = sijoitteluTulos.valintatila.get(hakemusOid, hakukohdeOid),
            vastaanottotieto = sijoitteluTulos.vastaanottotila.get(hakemusOid, hakukohdeOid),
            valintatapajononTyyppi = jononTyyppi,
            valintatapajononNimi = None,
            hyvaksymisenEhto = None,
            pisteet = None,
            ilmoittautumiset = lasnaolot,
            pohjakoulutus = getPohjakoulutukset(answers.koulutustausta.getOrElse(Koulutustausta())),
            julkaisulupa = Some(hakemus.julkaisulupa),
            hKelpoisuus = hakukelpoisuus.status,
            hKelpoisuusLahde = hakukelpoisuus.source,
            hKelpoisuusMaksuvelvollisuus = hakukelpoisuus.maksuvelvollisuus,
            lukuvuosimaksu = resolveLukuvuosiMaksu(
              hakemus,
              hakukelpoisuus,
              lukuvuosimaksutByHakukohdeOid,
              hakukohdeOid
            ),
            hakukohteenKoulutukset = hakukohteenkoulutukset.koulutukset
              .map(koulutus =>
                KkHakukohteenkoulutus(
                  komoOid = koulutus.komoOid,
                  tkKoulutuskoodi = koulutus.tkKoulutuskoodi,
                  kkKoulutusId = koulutus.kkKoulutusId,
                  koulutuksenAlkamiskausi = None,
                  koulutuksenAlkamisvuosi = None,
                  johtaaTutkintoon = koulutus.johtaaTutkintoon
                )
              ),
            liitteet = attachmentToLiite(hakemus.attachmentRequests)
          )
        } else {
          None
        }
      }
    case hakemus: AtaruHakemus =>
      for {
        hakukohdeOid <- toive.koulutusId.fold[Future[String]](
          Future.failed(new RuntimeException("No hakukohde OID"))
        )(Future.successful)
        hakukohteenkoulutukset: HakukohteenKoulutukset <-
          (hakukohdeAggregator.actor ? HakukohteenKoulutuksetQuery(hakukohdeOid))
            .mapTo[HakukohteenKoulutukset]
        kausi: String <- getKausi(haku.kausi, hakemus.oid, koodisto)
        jononTyyppi: Option[String] <- jononTyyppiForHakemusF(
          sijoitteluTulos.valintatila.get(hakemus.oid, hakukohdeOid),
          sijoitteluTulos.valintatapajono.get(hakemus.oid, hakukohdeOid),
          jonotiedot
        )
        lasnaolot: Seq[Lasnaolo] <- getLasnaolot(
          sijoitteluTulos,
          hakukohdeOid,
          hakemus.oid,
          hakukohteenkoulutukset.koulutukset
        )
        usePriority <- usePriorityFuture
      } yield {
        if (matchHakuehto(sijoitteluTulos, hakemus.oid, hakukohdeOid)(q.hakuehto)) {
          val hakukelpoisuus = PreferenceEligibility(
            aoId = hakukohdeOid,
            status = hakemus.eligibilities.getOrElse(hakukohdeOid, "NOT_CHECKED"),
            source = None,
            maksuvelvollisuus =
              Some(hakemus.paymentObligations.getOrElse(hakukohdeOid, "NOT_CHECKED"))
          )
          val jononOid = sijoitteluTulos.valintatapajono.get(hakemus.oid, hakukohdeOid)
          val jononNimi = jononOid.flatMap(oid => jonotiedot.find(_.oid == oid)).flatMap(_.nimi)

          Some(
            Hakemus(
              haku = hakemus.applicationSystemId,
              hakuVuosi = haku.vuosi,
              hakuKausi = kausi,
              hakemusnumero = hakemus.oid,
              hakemusViimeinenMuokkausAikaleima = Some(hakemus.createdTime),
              hakemusJattoAikaleima = Some(hakemus.hakemusFirstSubmittedTime),
              valinnanAikaleima = sijoitteluTulos.valinnanAikaleima.get(hakemus.oid, hakukohdeOid),
              organisaatio = toive.organizationOid.getOrElse(""),
              hakukohde = toive.koulutusId.getOrElse(""),
              hakutoivePrioriteetti = if (usePriority) Some(toive.preferenceNumber) else None,
              hakukohdeKkId = hakukohteenkoulutukset.ulkoinenTunniste,
              avoinVayla = None, // TODO valinnoista?
              valinnanTila = sijoitteluTulos.valintatila.get(hakemus.oid, hakukohdeOid),
              vastaanottotieto = sijoitteluTulos.vastaanottotila.get(hakemus.oid, hakukohdeOid),
              valintatapajononTyyppi = jononTyyppi,
              valintatapajononNimi = jononNimi,
              hyvaksymisenEhto = sijoitteluTulos.hyvaksymisenEhto.get(hakemus.oid, hakukohdeOid),
              pisteet = sijoitteluTulos.pisteet.get(hakemus.oid, hakukohdeOid),
              ilmoittautumiset = lasnaolot,
              pohjakoulutus = hakemus.kkPohjakoulutusLomake,
              julkaisulupa = Some(hakemus.julkaisulupa),
              hKelpoisuus = hakukelpoisuus.status,
              hKelpoisuusLahde = hakukelpoisuus.source,
              hKelpoisuusMaksuvelvollisuus = hakukelpoisuus.maksuvelvollisuus,
              lukuvuosimaksu = resolveLukuvuosiMaksu(
                hakemus,
                hakukelpoisuus,
                lukuvuosimaksutByHakukohdeOid,
                hakukohdeOid
              ),
              hakukohteenKoulutukset = hakukohteenkoulutukset.koulutukset
                .map(koulutus =>
                  KkHakukohteenkoulutus(
                    komoOid = koulutus.komoOid,
                    tkKoulutuskoodi = koulutus.tkKoulutuskoodi,
                    kkKoulutusId = koulutus.kkKoulutusId,
                    koulutuksenAlkamiskausi = koulutus.koulutuksenAlkamiskausi.flatMap(_.arvo),
                    koulutuksenAlkamisvuosi = koulutus.koulutuksenAlkamisvuosi,
                    johtaaTutkintoon = koulutus.johtaaTutkintoon
                  )
                ),
              liitteet = None
            )
          )
        } else {
          None
        }
      }
    case _ => ???
  }

  private def extractSingleHakemus(
    h: HakijaHakemus,
    lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]],
    q: KkHakijaQuery,
    toive: HakutoiveDTO,
    haku: Haku,
    sijoitteluTulos: SijoitteluTulos,
    jonotiedot: Seq[ValintatapajononTiedot]
  ): Future[Option[Hakemus]] = h match {
    case hakemus: FullHakemus =>
      val hakemusOid = hakemus.oid
      val hakukohdeOid = toive.koulutusId.getOrElse("")
      val preferenceEligibilities = hakemus.preferenceEligibilities
      val hakukelpoisuus = getHakukelpoisuus(hakukohdeOid, preferenceEligibilities)
      for {
        hakukohteenkoulutukset: HakukohteenKoulutukset <-
          (hakukohdeAggregator.actor ? HakukohteenKoulutuksetQuery(hakukohdeOid))
            .mapTo[HakukohteenKoulutukset]
        kausi: String <- getKausi(haku.kausi, hakemusOid, koodisto)
        jononTyyppi: Option[String] <- jononTyyppiForHakemusF(
          sijoitteluTulos.valintatila.get(hakemusOid, hakukohdeOid),
          sijoitteluTulos.valintatapajono.get(hakemusOid, hakukohdeOid),
          jonotiedot
        )
        lasnaolot: Seq[Lasnaolo] <- getLasnaolot(
          sijoitteluTulos,
          hakukohdeOid,
          hakemusOid,
          hakukohteenkoulutukset.koulutukset
        )
      } yield {
        if (matchHakuehto(sijoitteluTulos, hakemusOid, hakukohdeOid)(q.hakuehto)) {
          for {
            answers <- hakemus.answers
          } yield Hakemus(
            haku = hakemus.applicationSystemId,
            hakuVuosi = haku.vuosi,
            hakuKausi = kausi,
            hakemusnumero = hakemusOid,
            hakemusViimeinenMuokkausAikaleima = None,
            hakemusJattoAikaleima = None,
            valinnanAikaleima = None,
            organisaatio = toive.organizationOid.getOrElse(""),
            hakukohde = toive.koulutusId.getOrElse(""),
            hakutoivePrioriteetti = None,
            hakukohdeKkId = hakukohteenkoulutukset.ulkoinenTunniste,
            avoinVayla = None, // TODO valinnoista?
            valinnanTila = sijoitteluTulos.valintatila.get(hakemusOid, hakukohdeOid),
            vastaanottotieto = sijoitteluTulos.vastaanottotila.get(hakemusOid, hakukohdeOid),
            valintatapajononTyyppi = jononTyyppi,
            valintatapajononNimi = None,
            hyvaksymisenEhto = None,
            pisteet = None,
            ilmoittautumiset = lasnaolot,
            pohjakoulutus = getPohjakoulutukset(answers.koulutustausta.getOrElse(Koulutustausta())),
            julkaisulupa = Some(hakemus.julkaisulupa),
            hKelpoisuus = hakukelpoisuus.status,
            hKelpoisuusLahde = hakukelpoisuus.source,
            hKelpoisuusMaksuvelvollisuus = hakukelpoisuus.maksuvelvollisuus,
            lukuvuosimaksu = resolveLukuvuosiMaksu(
              hakemus,
              hakukelpoisuus,
              lukuvuosimaksutByHakukohdeOid,
              hakukohdeOid
            ),
            hakukohteenKoulutukset = hakukohteenkoulutukset.koulutukset
              .map(koulutus =>
                KkHakukohteenkoulutus(
                  komoOid = koulutus.komoOid,
                  tkKoulutuskoodi = koulutus.tkKoulutuskoodi,
                  kkKoulutusId = koulutus.kkKoulutusId,
                  koulutuksenAlkamiskausi = None,
                  koulutuksenAlkamisvuosi = None,
                  johtaaTutkintoon = None
                )
              ),
            liitteet = attachmentToLiite(hakemus.attachmentRequests)
          )
        } else {
          None
        }
      }
    case hakemus: AtaruHakemus =>
      for {
        hakukohdeOid <- toive.koulutusId.fold[Future[String]](
          Future.failed(new RuntimeException("No hakukohde OID"))
        )(Future.successful)
        hakukohteenkoulutukset: HakukohteenKoulutukset <-
          (hakukohdeAggregator.actor ? HakukohteenKoulutuksetQuery(hakukohdeOid))
            .mapTo[HakukohteenKoulutukset]
        kausi: String <- getKausi(haku.kausi, hakemus.oid, koodisto)
        jononTyyppi: Option[String] <- jononTyyppiForHakemusF(
          sijoitteluTulos.valintatila.get(hakemus.oid, hakukohdeOid),
          sijoitteluTulos.valintatapajono.get(hakemus.oid, hakukohdeOid),
          jonotiedot
        )
        lasnaolot: Seq[Lasnaolo] <- getLasnaolot(
          sijoitteluTulos,
          hakukohdeOid,
          hakemus.oid,
          hakukohteenkoulutukset.koulutukset
        )
      } yield {
        if (matchHakuehto(sijoitteluTulos, hakemus.oid, hakukohdeOid)(q.hakuehto)) {
          val hakukelpoisuus = PreferenceEligibility(
            aoId = hakukohdeOid,
            status = hakemus.eligibilities.getOrElse(hakukohdeOid, "NOT_CHECKED"),
            source = None,
            maksuvelvollisuus =
              Some(hakemus.paymentObligations.getOrElse(hakukohdeOid, "NOT_CHECKED"))
          )
          Some(
            Hakemus(
              haku = hakemus.applicationSystemId,
              hakuVuosi = haku.vuosi,
              hakuKausi = kausi,
              hakemusnumero = hakemus.oid,
              hakemusViimeinenMuokkausAikaleima = None,
              hakemusJattoAikaleima = None,
              valinnanAikaleima = None,
              organisaatio = toive.organizationOid.getOrElse(""),
              hakukohde = toive.koulutusId.getOrElse(""),
              hakutoivePrioriteetti = None,
              hakukohdeKkId = hakukohteenkoulutukset.ulkoinenTunniste,
              avoinVayla = None, // TODO valinnoista?
              valinnanTila = sijoitteluTulos.valintatila.get(hakemus.oid, hakukohdeOid),
              vastaanottotieto = sijoitteluTulos.vastaanottotila.get(hakemus.oid, hakukohdeOid),
              valintatapajononTyyppi = jononTyyppi,
              valintatapajononNimi = None,
              hyvaksymisenEhto = None,
              pisteet = None,
              ilmoittautumiset = lasnaolot,
              pohjakoulutus = hakemus.kkPohjakoulutus,
              julkaisulupa = Some(hakemus.julkaisulupa),
              hKelpoisuus = hakukelpoisuus.status,
              hKelpoisuusLahde = hakukelpoisuus.source,
              hKelpoisuusMaksuvelvollisuus = hakukelpoisuus.maksuvelvollisuus,
              lukuvuosimaksu = resolveLukuvuosiMaksu(
                hakemus,
                hakukelpoisuus,
                lukuvuosimaksutByHakukohdeOid,
                hakukohdeOid
              ),
              hakukohteenKoulutukset = hakukohteenkoulutukset.koulutukset
                .map(koulutus =>
                  KkHakukohteenkoulutus(
                    komoOid = koulutus.komoOid,
                    tkKoulutuskoodi = koulutus.tkKoulutuskoodi,
                    kkKoulutusId = koulutus.kkKoulutusId,
                    koulutuksenAlkamiskausi = None,
                    koulutuksenAlkamisvuosi = None,
                    johtaaTutkintoon = None
                  )
                ),
              liitteet = None
            )
          )
        } else {
          None
        }
      }
    case _ => ???
  }

  private def getKkHakijaV1(
    haku: Haku,
    q: KkHakijaQuery,
    kokoHaunTulos: Option[SijoitteluTulos],
    hakukohdeOids: Seq[String]
  )(h: HakijaHakemus): Option[Future[Hakija]] = h match {
    case hakemus: FullHakemus =>
      for {
        answers: HakemusAnswers <- hakemus.answers
        henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
        henkiloOid <- hakemus.personOid
      } yield for {
        hakemukset <- getHakemukset(haku, hakemus, Map(), q, kokoHaunTulos, hakukohdeOids)
        maa <- getMaakoodi(henkilotiedot.asuinmaa.getOrElse(""), koodisto)
        toimipaikka <- getToimipaikka(
          maa,
          henkilotiedot.Postinumero,
          henkilotiedot.kaupunkiUlkomaa,
          koodisto
        )
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(
          henkilo = henkiloOid,
          komo = YoTutkinto.yotutkinto
        )).mapTo[Seq[VirallinenSuoritus]]
        kansalaisuus <- getMaakoodi(henkilotiedot.kansalaisuus.getOrElse(""), koodisto)
      } yield Hakija(
        hetu = getHetu(henkilotiedot.Henkilotunnus, henkilotiedot.syntymaaika, hakemus.oid),
        oppijanumero = hakemus.personOid.getOrElse(""),
        sukunimi = henkilotiedot.Sukunimi.getOrElse(""),
        etunimet = henkilotiedot.Etunimet.getOrElse(""),
        kutsumanimi = henkilotiedot.Kutsumanimi.getOrElse(""),
        lahiosoite = henkilotiedot.lahiosoite
          .flatMap(_.blankOption)
          .getOrElse(henkilotiedot.osoiteUlkomaa.getOrElse("")),
        postinumero = henkilotiedot.Postinumero
          .flatMap(_.blankOption)
          .getOrElse(henkilotiedot.postinumeroUlkomaa.getOrElse("")),
        postitoimipaikka = toimipaikka,
        maa = maa,
        kansalaisuus = Some(kansalaisuus),
        kaksoiskansalaisuus = None,
        kansalaisuudet = None,
        syntymaaika = None,
        matkapuhelin = henkilotiedot.matkapuhelinnumero1.flatMap(_.blankOption),
        puhelin = henkilotiedot.matkapuhelinnumero2.flatMap(_.blankOption),
        sahkoposti = henkilotiedot.Sähköposti.flatMap(_.blankOption),
        kotikunta = henkilotiedot.kotikunta.flatMap(_.blankOption).getOrElse("999"),
        sukupuoli = henkilotiedot.sukupuoli.getOrElse(""),
        aidinkieli = henkilotiedot.aidinkieli.flatMap(_.blankOption).getOrElse("99"),
        asiointikieli =
          getAsiointikieli(answers.lisatiedot.getOrElse(Map()).get("asiointikieli").getOrElse("9")),
        koulusivistyskieli =
          Some(henkilotiedot.koulusivistyskieli.flatMap(_.blankOption).getOrElse("99")),
        koulusivistyskielet = None,
        koulutusmarkkinointilupa = Some(hakemus.markkinointilupa),
        onYlioppilas = isYlioppilas(suoritukset),
        yoSuoritusVuosi = None,
        turvakielto = henkilotiedot.turvakielto.contains("true"),
        hakemukset = hakemukset.map(hakemus =>
          hakemus.copy(
            liitteet = None,
            julkaisulupa = hakemus.julkaisulupa,
            hKelpoisuusMaksuvelvollisuus = None
          )
        ),
        ensikertalainen = None
      )
    case hakemus: AtaruHakemus =>
      Some(for {
        hakemukset <- getHakemukset(haku, hakemus, Map(), q, kokoHaunTulos, hakukohdeOids)
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(
          henkilo = hakemus.henkilo.oidHenkilo,
          komo = YoTutkinto.yotutkinto
        )).mapTo[Seq[VirallinenSuoritus]]
      } yield {
        val syntymaaika = hakemus.henkilo.syntymaaika.map(s =>
          new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s))
        )
        Hakija(
          hetu = getHetu(hakemus.henkilo.hetu, syntymaaika, hakemus.oid),
          oppijanumero = hakemus.henkilo.oidHenkilo,
          sukunimi = hakemus.henkilo.sukunimi.getOrElse(""),
          etunimet = hakemus.henkilo.etunimet.getOrElse(""),
          kutsumanimi = hakemus.henkilo.kutsumanimi.getOrElse(""),
          lahiosoite = hakemus.lahiosoite,
          postinumero = hakemus.postinumero,
          postitoimipaikka = hakemus.postitoimipaikka.getOrElse(""),
          maa = hakemus.asuinmaa,
          kansalaisuus =
            Some(hakemus.henkilo.kansalaisuus.headOption.map(_.kansalaisuusKoodi).getOrElse("999")),
          kaksoiskansalaisuus = None,
          kansalaisuudet = None,
          syntymaaika = syntymaaika,
          matkapuhelin = Some(hakemus.matkapuhelin),
          puhelin = None,
          sahkoposti = Some(hakemus.email),
          kotikunta = hakemus.kotikunta.getOrElse("999"),
          sukupuoli = hakemus.henkilo.sukupuoli.getOrElse(""),
          aidinkieli = hakemus.henkilo.aidinkieli.map(_.kieliKoodi.toUpperCase).getOrElse("99"),
          asiointikieli = hakemus.asiointiKieli.toLowerCase match {
            case "fi" => "1"
            case "sv" => "2"
            case "en" => "3"
            case _    => "9"
          },
          koulusivistyskieli = Some("99"),
          koulusivistyskielet = None,
          koulutusmarkkinointilupa = Some(hakemus.markkinointilupa),
          onYlioppilas = isYlioppilas(suoritukset),
          yoSuoritusVuosi = None,
          turvakielto = hakemus.henkilo.turvakielto.getOrElse(false),
          hakemukset = hakemukset,
          ensikertalainen = None
        )
      })
    case _ => ???
  }

  private def getJononTyyppiFromKoodisto(koodiUri: String): Future[Option[String]] = {
    (koodisto.actor ? GetKoodi("valintatapajono", koodiUri))
      .mapTo[Option[Koodi]]
      .map {
        case Some(k) =>
          k.metadata.find(_.kieli == "FI") match {
            case Some(m) => Some(m.nimi)
            case None =>
              logger.warn(
                "VTKU-112: No metadata for kieli FI found for koodiuri {} in valintatapajono-koodisto, resolving as Tuntematon",
                koodiUri
              )
              Some("Tuntematon")
          }
        case None =>
          logger.warn(
            "VTKU-112: No koodi found for koodiuri {} in valintatapajono-koodisto, resolving as Tuntematon",
            koodiUri
          )
          Some("Tuntematon")
      }
  }

  def jononTyyppiForHakemusF(
    tila: Option[Valintatila],
    jonoOid: Option[String],
    jonoTiedot: Seq[ValintatapajononTiedot]
  )(implicit timeout: Timeout, ec: ExecutionContext): Future[Option[String]] = {
    if (tila.exists(Valintatila.isHyvaksytty)) {
      val tyyppi: Option[String] =
        jonoOid.flatMap(oid => jonoTiedot.find(_.oid == oid)).flatMap(_.tyyppi)
      tyyppi match {
        case Some(t) => getJononTyyppiFromKoodisto(t)
        case None =>
          logger.warn(
            "VTKU-112: No jonotieto found for jono {}, but it has a hyväksytty hakija!",
            jonoOid
          )
          Future.successful(None)
      }
    } else Future.successful(None)
  }

  private def getKkHakijaV5(
    haku: Haku,
    q: KkHakijaQuery,
    tulokset: SijoitteluTulos,
    hakukohdeOids: Seq[String],
    lukuvuosiMaksutByHenkiloAndHakukohde: Map[String, Map[String, List[Lukuvuosimaksu]]],
    jonotiedot: Seq[ValintatapajononTiedot],
    ensikertalaisuudet: Future[Map[String, Boolean]],
    usePriority: Future[Boolean],
    masterHenkilosForFullHakemukses: Future[Map[String, Henkilo]]
  )(h: HakijaHakemus): Option[Future[Hakija]] = h match {
    case hakemus: FullHakemus =>
      for {
        answers: HakemusAnswers <- hakemus.answers
        henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
        henkiloOid <- hakemus.personOid
      } yield for {
        hakemukset <- getHakemuksetV5(
          haku,
          hakemus,
          lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(henkiloOid, Map()),
          q,
          tulokset,
          hakukohdeOids,
          jonotiedot,
          usePriority
        )
        maa <- getMaakoodi(henkilotiedot.asuinmaa.getOrElse(""), koodisto)
        toimipaikka <- getToimipaikka(
          maa,
          henkilotiedot.Postinumero,
          henkilotiedot.kaupunkiUlkomaa,
          koodisto
        )
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(
          henkilo = henkiloOid,
          komo = YoTutkinto.yotutkinto
        )).mapTo[Seq[VirallinenSuoritus]]
        masterHenkilot <- masterHenkilosForFullHakemukses
      } yield {
        val masterHenkilo: Henkilo = getMasterHenkilo(h, masterHenkilot)
        val syntymaaika = masterHenkilo.syntymaaika.map(s =>
          new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s))
        )
        Hakija(
          hetu = getHetu(masterHenkilo.hetu, syntymaaika, hakemus.oid),
          oppijanumero = masterHenkilo.oidHenkilo,
          sukunimi = masterHenkilo.sukunimi.getOrElse(""),
          etunimet = masterHenkilo.etunimet.getOrElse(""),
          kutsumanimi = masterHenkilo.kutsumanimi.getOrElse(""),
          lahiosoite = henkilotiedot.lahiosoite
            .flatMap(_.blankOption)
            .getOrElse(henkilotiedot.osoiteUlkomaa.getOrElse("")),
          postinumero = henkilotiedot.Postinumero
            .flatMap(_.blankOption)
            .getOrElse(henkilotiedot.postinumeroUlkomaa.getOrElse("")),
          postitoimipaikka = toimipaikka,
          maa = maa,
          kansalaisuus = None,
          kaksoiskansalaisuus = None,
          kansalaisuudet = Some(masterHenkilo.kansalaisuus.map(_.kansalaisuusKoodi)),
          syntymaaika = masterHenkilo.syntymaaika,
          matkapuhelin = henkilotiedot.matkapuhelinnumero1.flatMap(_.blankOption),
          puhelin = henkilotiedot.matkapuhelinnumero2.flatMap(_.blankOption),
          sahkoposti = henkilotiedot.Sähköposti.flatMap(_.blankOption),
          kotikunta = henkilotiedot.kotikunta.flatMap(_.blankOption).getOrElse("999"),
          sukupuoli = masterHenkilo.sukupuoli.getOrElse(""),
          aidinkieli = getAidinkieli(masterHenkilo.aidinkieli),
          asiointikieli = getAsiointikieli(
            answers.lisatiedot.getOrElse(Map()).get("asiointikieli").getOrElse("9")
          ),
          koulusivistyskieli = None,
          koulusivistyskielet = Some(Seq.empty),
          koulutusmarkkinointilupa = Some(hakemus.markkinointilupa),
          onYlioppilas = isYlioppilas(suoritukset),
          yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
          turvakielto = masterHenkilo.turvakielto.contains("true"),
          hakemukset = hakemukset,
          ensikertalainen = None
        )
      }
    case hakemus: AtaruHakemus =>
      Some(for {
        hakemukset <- getHakemuksetV5(
          haku,
          hakemus,
          lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(hakemus.personOid.get, Map()),
          q,
          tulokset,
          hakukohdeOids,
          jonotiedot,
          usePriority
        )
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(
          henkilo = hakemus.henkilo.oidHenkilo,
          komo = YoTutkinto.yotutkinto
        )).mapTo[Seq[VirallinenSuoritus]]
        ens <- ensikertalaisuudet
      } yield {
        val syntymaaika = hakemus.henkilo.syntymaaika.map(s =>
          new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s))
        )
        Hakija(
          hetu = getHetu(hakemus.henkilo.hetu, syntymaaika, hakemus.oid),
          oppijanumero = hakemus.henkilo.oidHenkilo,
          sukunimi = hakemus.henkilo.sukunimi.getOrElse(""),
          etunimet = hakemus.henkilo.etunimet.getOrElse(""),
          kutsumanimi = hakemus.henkilo.kutsumanimi.getOrElse(""),
          lahiosoite = hakemus.lahiosoite,
          postinumero = hakemus.postinumero,
          postitoimipaikka = hakemus.postitoimipaikka.getOrElse(""),
          maa = hakemus.asuinmaa,
          kansalaisuus = None,
          kaksoiskansalaisuus = None,
          kansalaisuudet = Some(hakemus.henkilo.kansalaisuus.map(_.kansalaisuusKoodi)),
          syntymaaika = syntymaaika,
          matkapuhelin = Some(hakemus.matkapuhelin),
          puhelin = None,
          sahkoposti = Some(hakemus.email),
          kotikunta = hakemus.kotikunta.getOrElse("999"),
          sukupuoli = hakemus.henkilo.sukupuoli.getOrElse(""),
          aidinkieli = hakemus.henkilo.aidinkieli.map(_.kieliKoodi.toUpperCase).getOrElse("99"),
          asiointikieli = hakemus.asiointiKieli match {
            case "fi" => "1"
            case "sv" => "2"
            case "en" => "3"
            case _    => "9"
          },
          koulusivistyskieli = None,
          koulusivistyskielet = Some(Seq.empty),
          koulutusmarkkinointilupa = Some(hakemus.markkinointilupa),
          onYlioppilas = isYlioppilas(suoritukset),
          yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
          turvakielto = hakemus.henkilo.turvakielto.getOrElse(false),
          hakemukset = hakemukset,
          ensikertalainen = ens.get(hakemus.henkilo.oidHenkilo)
        )
      })
    case _ => ???
  }

  private def getMasterHenkilo(h: HakijaHakemus, masterHenkilot: Map[String, Henkilo]): Henkilo = {
    masterHenkilot.get(
      h.personOid.getOrElse("")
    ) match {
      case Some(mh) => mh
      case None =>
        logger.error(
          s"hakemuksen ${h.oid} henkilölle ${h.personOid} ei löytynyt henkilöä oppijanumerorekisteristä"
        )
        throw new RuntimeException(
          s"hakemuksen ${h.oid} henkilölle ${h.personOid} ei löytynyt henkilöä oppijanumerorekisteristä"
        )
    }
  }
  private def getKkHakijaV4(
    haku: Haku,
    q: KkHakijaQuery,
    tulokset: SijoitteluTulos,
    hakukohdeOids: Seq[String],
    lukuvuosiMaksutByHenkiloAndHakukohde: Map[String, Map[String, List[Lukuvuosimaksu]]],
    jonotiedot: Seq[ValintatapajononTiedot],
    masterHenkilosForFullHakemukses: Future[Map[String, Henkilo]]
  )(h: HakijaHakemus): Option[Future[Hakija]] = h match {
    case hakemus: FullHakemus =>
      for {
        answers: HakemusAnswers <- hakemus.answers
        henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
        henkiloOid <- hakemus.personOid
      } yield for {
        hakemukset <- getHakemuksetV4(
          haku,
          hakemus,
          lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(henkiloOid, Map()),
          q,
          tulokset,
          hakukohdeOids,
          jonotiedot
        )
        maa <- getMaakoodi(henkilotiedot.asuinmaa.getOrElse(""), koodisto)
        toimipaikka <- getToimipaikka(
          maa,
          henkilotiedot.Postinumero,
          henkilotiedot.kaupunkiUlkomaa,
          koodisto
        )
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(
          henkilo = henkiloOid,
          komo = YoTutkinto.yotutkinto
        )).mapTo[Seq[VirallinenSuoritus]]
        masterHenkilot <- masterHenkilosForFullHakemukses
      } yield {
        val masterHenkilo: Henkilo = getMasterHenkilo(h, masterHenkilot)
        val syntymaaika = masterHenkilo.syntymaaika.map(s =>
          new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s))
        )
        Hakija(
          hetu = getHetu(masterHenkilo.hetu, syntymaaika, hakemus.oid),
          oppijanumero = masterHenkilo.oidHenkilo,
          sukunimi = masterHenkilo.sukunimi.getOrElse(""),
          etunimet = masterHenkilo.etunimet.getOrElse(""),
          kutsumanimi = masterHenkilo.kutsumanimi.getOrElse(""),
          lahiosoite = henkilotiedot.lahiosoite
            .flatMap(_.blankOption)
            .getOrElse(henkilotiedot.osoiteUlkomaa.getOrElse("")),
          postinumero = henkilotiedot.Postinumero
            .flatMap(_.blankOption)
            .getOrElse(henkilotiedot.postinumeroUlkomaa.getOrElse("")),
          postitoimipaikka = toimipaikka,
          maa = maa,
          kansalaisuus = None,
          kaksoiskansalaisuus = None,
          kansalaisuudet = Some(masterHenkilo.kansalaisuus.map(_.kansalaisuusKoodi)),
          syntymaaika = masterHenkilo.syntymaaika,
          matkapuhelin = henkilotiedot.matkapuhelinnumero1.flatMap(_.blankOption),
          puhelin = henkilotiedot.matkapuhelinnumero2.flatMap(_.blankOption),
          sahkoposti = henkilotiedot.Sähköposti.flatMap(_.blankOption),
          kotikunta = henkilotiedot.kotikunta.flatMap(_.blankOption).getOrElse("999"),
          sukupuoli = masterHenkilo.sukupuoli.getOrElse(""),
          aidinkieli = getAidinkieli(masterHenkilo.aidinkieli),
          asiointikieli = getAsiointikieli(
            answers.lisatiedot.getOrElse(Map()).get("asiointikieli").getOrElse("9")
          ),
          koulusivistyskieli =
            Some(henkilotiedot.koulusivistyskieli.flatMap(_.blankOption).getOrElse("99")),
          koulusivistyskielet = None,
          koulutusmarkkinointilupa = Some(hakemus.markkinointilupa),
          onYlioppilas = isYlioppilas(suoritukset),
          yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
          turvakielto = masterHenkilo.turvakielto.contains("true"),
          hakemukset = hakemukset,
          ensikertalainen = None
        )
      }
    case hakemus: AtaruHakemus =>
      Some(for {
        hakemukset <- getHakemuksetV4(
          haku,
          hakemus,
          lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(hakemus.personOid.get, Map()),
          q,
          tulokset,
          hakukohdeOids,
          jonotiedot
        )
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(
          henkilo = hakemus.henkilo.oidHenkilo,
          komo = YoTutkinto.yotutkinto
        )).mapTo[Seq[VirallinenSuoritus]]
      } yield {
        val syntymaaika = hakemus.henkilo.syntymaaika.map(s =>
          new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s))
        )
        Hakija(
          hetu = getHetu(hakemus.henkilo.hetu, syntymaaika, hakemus.oid),
          oppijanumero = hakemus.henkilo.oidHenkilo,
          sukunimi = hakemus.henkilo.sukunimi.getOrElse(""),
          etunimet = hakemus.henkilo.etunimet.getOrElse(""),
          kutsumanimi = hakemus.henkilo.kutsumanimi.getOrElse(""),
          lahiosoite = hakemus.lahiosoite,
          postinumero = hakemus.postinumero,
          postitoimipaikka = hakemus.postitoimipaikka.getOrElse(""),
          maa = hakemus.asuinmaa,
          kansalaisuus = None,
          kaksoiskansalaisuus = None,
          kansalaisuudet = Some(hakemus.henkilo.kansalaisuus.map(_.kansalaisuusKoodi)),
          syntymaaika = syntymaaika,
          matkapuhelin = Some(hakemus.matkapuhelin),
          puhelin = None,
          sahkoposti = Some(hakemus.email),
          kotikunta = hakemus.kotikunta.getOrElse("999"),
          sukupuoli = hakemus.henkilo.sukupuoli.getOrElse(""),
          aidinkieli = hakemus.henkilo.aidinkieli.map(_.kieliKoodi.toUpperCase).getOrElse("99"),
          asiointikieli = hakemus.asiointiKieli match {
            case "fi" => "1"
            case "sv" => "2"
            case "en" => "3"
            case _    => "9"
          },
          koulusivistyskieli = Some("99"),
          koulusivistyskielet = None,
          koulutusmarkkinointilupa = Some(hakemus.markkinointilupa),
          onYlioppilas = isYlioppilas(suoritukset),
          yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
          turvakielto = hakemus.henkilo.turvakielto.getOrElse(false),
          hakemukset = hakemukset,
          ensikertalainen = None
        )
      })
    case _ => ???
  }

  private def getKkHakijaV3(
    haku: Haku,
    q: KkHakijaQuery,
    kokoHaunTulos: Option[SijoitteluTulos],
    hakukohdeOids: Seq[String],
    lukuvuosiMaksutByHenkiloAndHakukohde: Map[String, Map[String, List[Lukuvuosimaksu]]]
  )(h: HakijaHakemus): Option[Future[Hakija]] = h match {
    case hakemus: FullHakemus =>
      for {
        answers: HakemusAnswers <- hakemus.answers
        henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
        henkiloOid <- hakemus.personOid
      } yield for {
        hakemukset <- getHakemukset(
          haku,
          hakemus,
          lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(henkiloOid, Map()),
          q,
          kokoHaunTulos,
          hakukohdeOids
        )
        maa <- getMaakoodi(henkilotiedot.asuinmaa.getOrElse(""), koodisto)
        toimipaikka <- getToimipaikka(
          maa,
          henkilotiedot.Postinumero,
          henkilotiedot.kaupunkiUlkomaa,
          koodisto
        )
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(
          henkilo = henkiloOid,
          komo = YoTutkinto.yotutkinto
        )).mapTo[Seq[VirallinenSuoritus]]
        kansalaisuus <- getMaakoodi(henkilotiedot.kansalaisuus.getOrElse(""), koodisto)
        kaksoiskansalaisuus <- getMaakoodi(
          henkilotiedot.kaksoiskansalaisuus.getOrElse(""),
          koodisto
        )
      } yield Hakija(
        hetu = getHetu(henkilotiedot.Henkilotunnus, henkilotiedot.syntymaaika, hakemus.oid),
        oppijanumero = hakemus.personOid.getOrElse(""),
        sukunimi = henkilotiedot.Sukunimi.getOrElse(""),
        etunimet = henkilotiedot.Etunimet.getOrElse(""),
        kutsumanimi = henkilotiedot.Kutsumanimi.getOrElse(""),
        lahiosoite = henkilotiedot.lahiosoite
          .flatMap(_.blankOption)
          .getOrElse(henkilotiedot.osoiteUlkomaa.getOrElse("")),
        postinumero = henkilotiedot.Postinumero
          .flatMap(_.blankOption)
          .getOrElse(henkilotiedot.postinumeroUlkomaa.getOrElse("")),
        postitoimipaikka = toimipaikka,
        maa = maa,
        kansalaisuus = None,
        kaksoiskansalaisuus = None,
        kansalaisuudet =
          if (
            henkilotiedot.kaksoiskansalaisuus.isDefined && henkilotiedot.kaksoiskansalaisuus.get.nonEmpty
          ) Some(List(kansalaisuus, kaksoiskansalaisuus))
          else Some(List(kansalaisuus)),
        syntymaaika = henkilotiedot.syntymaaika,
        matkapuhelin = henkilotiedot.matkapuhelinnumero1.flatMap(_.blankOption),
        puhelin = henkilotiedot.matkapuhelinnumero2.flatMap(_.blankOption),
        sahkoposti = henkilotiedot.Sähköposti.flatMap(_.blankOption),
        kotikunta = henkilotiedot.kotikunta.flatMap(_.blankOption).getOrElse("999"),
        sukupuoli = henkilotiedot.sukupuoli.getOrElse(""),
        aidinkieli = henkilotiedot.aidinkieli.flatMap(_.blankOption).getOrElse("99"),
        asiointikieli =
          getAsiointikieli(answers.lisatiedot.getOrElse(Map()).get("asiointikieli").getOrElse("9")),
        koulusivistyskieli =
          Some(henkilotiedot.koulusivistyskieli.flatMap(_.blankOption).getOrElse("99")),
        koulusivistyskielet = None,
        koulutusmarkkinointilupa = Some(hakemus.markkinointilupa),
        onYlioppilas = isYlioppilas(suoritukset),
        yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
        turvakielto = henkilotiedot.turvakielto.contains("true"),
        hakemukset = hakemukset,
        ensikertalainen = None
      )
    case hakemus: AtaruHakemus =>
      Some(for {
        hakemukset: Seq[Hakemus] <- getHakemukset(
          haku,
          hakemus,
          lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(hakemus.personOid.get, Map()),
          q,
          kokoHaunTulos,
          hakukohdeOids
        )
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(
          henkilo = hakemus.henkilo.oidHenkilo,
          komo = YoTutkinto.yotutkinto
        )).mapTo[Seq[VirallinenSuoritus]]
      } yield {
        val syntymaaika = hakemus.henkilo.syntymaaika.map(s =>
          new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s))
        )
        Hakija(
          hetu = getHetu(hakemus.henkilo.hetu, syntymaaika, hakemus.oid),
          oppijanumero = hakemus.henkilo.oidHenkilo,
          sukunimi = hakemus.henkilo.sukunimi.getOrElse(""),
          etunimet = hakemus.henkilo.etunimet.getOrElse(""),
          kutsumanimi = hakemus.henkilo.kutsumanimi.getOrElse(""),
          lahiosoite = hakemus.lahiosoite,
          postinumero = hakemus.postinumero,
          postitoimipaikka = hakemus.postitoimipaikka.getOrElse(""),
          maa = hakemus.asuinmaa,
          kansalaisuus = None,
          kaksoiskansalaisuus = None,
          kansalaisuudet = Some(hakemus.henkilo.kansalaisuus.map(_.kansalaisuusKoodi)),
          syntymaaika = syntymaaika,
          matkapuhelin = Some(hakemus.matkapuhelin),
          puhelin = None,
          sahkoposti = Some(hakemus.email),
          kotikunta = hakemus.kotikunta.getOrElse("999"),
          sukupuoli = hakemus.henkilo.sukupuoli.getOrElse(""),
          aidinkieli = hakemus.henkilo.aidinkieli.map(_.kieliKoodi.toUpperCase).getOrElse("99"),
          asiointikieli = hakemus.asiointiKieli match {
            case "fi" => "1"
            case "sv" => "2"
            case "en" => "3"
            case _    => "9"
          },
          koulusivistyskieli = Some("99"),
          koulusivistyskielet = None,
          koulutusmarkkinointilupa = Some(hakemus.markkinointilupa),
          onYlioppilas = isYlioppilas(suoritukset),
          yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
          turvakielto = hakemus.henkilo.turvakielto.getOrElse(false),
          hakemukset = hakemukset,
          ensikertalainen = None
        )
      })
    case _ => ???
  }

  private def getKkHakijaV2(
    haku: Haku,
    q: KkHakijaQuery,
    kokoHaunTulos: Option[SijoitteluTulos],
    hakukohdeOids: Seq[String],
    lukuvuosiMaksutByHenkiloAndHakukohde: Map[String, Map[String, List[Lukuvuosimaksu]]]
  )(h: HakijaHakemus): Option[Future[Hakija]] = h match {
    case hakemus: FullHakemus =>
      for {
        answers: HakemusAnswers <- hakemus.answers
        henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
        henkiloOid <- hakemus.personOid
      } yield for {
        hakemukset <- getHakemukset(
          haku,
          hakemus,
          lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(henkiloOid, Map()),
          q,
          kokoHaunTulos,
          hakukohdeOids
        )
        maa <- getMaakoodi(henkilotiedot.asuinmaa.getOrElse(""), koodisto)
        toimipaikka <- getToimipaikka(
          maa,
          henkilotiedot.Postinumero,
          henkilotiedot.kaupunkiUlkomaa,
          koodisto
        )
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(
          henkilo = henkiloOid,
          komo = YoTutkinto.yotutkinto
        )).mapTo[Seq[VirallinenSuoritus]]
        kansalaisuus <- getMaakoodi(henkilotiedot.kansalaisuus.getOrElse(""), koodisto)
      } yield Hakija(
        hetu = getHetu(henkilotiedot.Henkilotunnus, henkilotiedot.syntymaaika, hakemus.oid),
        oppijanumero = hakemus.personOid.getOrElse(""),
        sukunimi = henkilotiedot.Sukunimi.getOrElse(""),
        etunimet = henkilotiedot.Etunimet.getOrElse(""),
        kutsumanimi = henkilotiedot.Kutsumanimi.getOrElse(""),
        lahiosoite = henkilotiedot.lahiosoite
          .flatMap(_.blankOption)
          .getOrElse(henkilotiedot.osoiteUlkomaa.getOrElse("")),
        postinumero = henkilotiedot.Postinumero
          .flatMap(_.blankOption)
          .getOrElse(henkilotiedot.postinumeroUlkomaa.getOrElse("")),
        postitoimipaikka = toimipaikka,
        maa = maa,
        kansalaisuus = Some(kansalaisuus),
        kaksoiskansalaisuus = henkilotiedot.kaksoiskansalaisuus,
        kansalaisuudet = None,
        syntymaaika = henkilotiedot.syntymaaika,
        matkapuhelin = henkilotiedot.matkapuhelinnumero1.flatMap(_.blankOption),
        puhelin = henkilotiedot.matkapuhelinnumero2.flatMap(_.blankOption),
        sahkoposti = henkilotiedot.Sähköposti.flatMap(_.blankOption),
        kotikunta = henkilotiedot.kotikunta.flatMap(_.blankOption).getOrElse("999"),
        sukupuoli = henkilotiedot.sukupuoli.getOrElse(""),
        aidinkieli = henkilotiedot.aidinkieli.flatMap(_.blankOption).getOrElse("99"),
        asiointikieli =
          getAsiointikieli(answers.lisatiedot.getOrElse(Map()).get("asiointikieli").getOrElse("9")),
        koulusivistyskieli =
          Some(henkilotiedot.koulusivistyskieli.flatMap(_.blankOption).getOrElse("99")),
        koulusivistyskielet = None,
        koulutusmarkkinointilupa = Some(hakemus.markkinointilupa),
        onYlioppilas = isYlioppilas(suoritukset),
        yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
        turvakielto = henkilotiedot.turvakielto.contains("true"),
        hakemukset = hakemukset,
        ensikertalainen = None
      )
    case hakemus: AtaruHakemus =>
      Some(for {
        hakemukset <- getHakemukset(
          haku,
          hakemus,
          lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(hakemus.personOid.get, Map()),
          q,
          kokoHaunTulos,
          hakukohdeOids
        )
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(
          henkilo = hakemus.henkilo.oidHenkilo,
          komo = YoTutkinto.yotutkinto
        )).mapTo[Seq[VirallinenSuoritus]]
      } yield {
        val syntymaaika = hakemus.henkilo.syntymaaika.map(s =>
          new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s))
        )
        Hakija(
          hetu = getHetu(hakemus.henkilo.hetu, syntymaaika, hakemus.oid),
          oppijanumero = hakemus.henkilo.oidHenkilo,
          sukunimi = hakemus.henkilo.sukunimi.getOrElse(""),
          etunimet = hakemus.henkilo.etunimet.getOrElse(""),
          kutsumanimi = hakemus.henkilo.kutsumanimi.getOrElse(""),
          lahiosoite = hakemus.lahiosoite,
          postinumero = hakemus.postinumero,
          postitoimipaikka = hakemus.postitoimipaikka.getOrElse(""),
          maa = hakemus.asuinmaa,
          kansalaisuus =
            Some(hakemus.henkilo.kansalaisuus.headOption.map(_.kansalaisuusKoodi).getOrElse("999")),
          kaksoiskansalaisuus = None,
          kansalaisuudet = None,
          syntymaaika = syntymaaika,
          matkapuhelin = Some(hakemus.matkapuhelin),
          puhelin = None,
          sahkoposti = Some(hakemus.email),
          kotikunta = hakemus.kotikunta.getOrElse("999"),
          sukupuoli = hakemus.henkilo.sukupuoli.getOrElse(""),
          aidinkieli = hakemus.henkilo.aidinkieli.map(_.kieliKoodi.toUpperCase).getOrElse("99"),
          asiointikieli = hakemus.asiointiKieli match {
            case "fi" => "1"
            case "sv" => "2"
            case "en" => "3"
            case _    => "9"
          },
          koulusivistyskieli = Some("99"),
          koulusivistyskielet = None,
          koulutusmarkkinointilupa = Some(hakemus.markkinointilupa),
          onYlioppilas = isYlioppilas(suoritukset),
          yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
          turvakielto = hakemus.henkilo.turvakielto.getOrElse(false),
          hakemukset = hakemukset,
          ensikertalainen = None
        )
      })
    case _ => ???
  }
}
