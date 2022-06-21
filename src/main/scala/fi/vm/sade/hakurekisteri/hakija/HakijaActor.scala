package fi.vm.sade.hakurekisteri.hakija

import akka.actor.{Actor, ActorLogging}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.hakija.ForkedSeq._
import fi.vm.sade.hakurekisteri.hakija.TupledFuture._
import fi.vm.sade.hakurekisteri.hakija.representation._
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.hakemus.Hakupalvelu
import fi.vm.sade.hakurekisteri.integration.koodisto.{
  GetKoodi,
  GetRinnasteinenKoodiArvoQuery,
  Koodi,
  KoodistoActorRef
}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Organisaatio, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila.Ilmoittautumistila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila._
import fi.vm.sade.hakurekisteri.integration.valintatulos._
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.suoritus.{Komoto, Suoritus}
import fi.vm.sade.hakurekisteri.web.kkhakija.Query

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex
import scala.util.{Failure, Try}

case class Hakukohde(koulutukset: Set[Komoto], hakukohdekoodi: String, oid: String)

case class Hakutoive(
  jno: Int,
  hakukohde: Hakukohde,
  kaksoistutkinto: Option[Boolean],
  urheilijanammatillinenkoulutus: Option[Boolean],
  harkinnanvaraisuusperuste: Option[String],
  aiempiperuminen: Option[Boolean],
  terveys: Option[Boolean],
  yhteispisteet: Option[BigDecimal],
  organisaatioParentOids: Set[String],
  koulutuksenKieli: Option[String],
  valinta: Option[Valintatila],
  vastaanotto: Option[Vastaanottotila],
  ilmoittautumistila: Option[Ilmoittautumistila]
)

sealed trait Lasnaolo
case class Lasna(kausi: Kausi) extends Lasnaolo
case class Poissa(kausi: Kausi) extends Lasnaolo
case class PoissaEiKulutaOpintoaikaa(kausi: Kausi) extends Lasnaolo
case class Puuttuu(kausi: Kausi) extends Lasnaolo

sealed trait Kausi
case class Kevat(vuosi: Int) extends Kausi
case class Syksy(vuosi: Int) extends Kausi

case class Hakemus(
  hakutoiveet: Seq[Hakutoive],
  hakemusnumero: String,
  julkaisulupa: Boolean,
  hakuOid: String,
  lisapistekoulutus: Option[String],
  liitteet: Seq[Liite],
  osaaminen: Osaaminen
)

case class Hakija(
  henkilo: Henkilo,
  suoritukset: Seq[Suoritus],
  opiskeluhistoria: Seq[Opiskelija],
  hakemus: Hakemus
)
object Hakija {
  val mies: Regex = "\\d{6}[-A]\\d{2}[13579].".r
  val nainen: Regex = "\\d{6}[-A]\\d{2}[24680].".r
  val valid: Regex = "([12])".r

  def resolveSukupuoli(hakija: Hakija): String =
    (hakija.henkilo.hetu, hakija.henkilo.sukupuoli) match {
      case (mies(), _)           => "1"
      case (nainen(), _)         => "2"
      case (_, valid(sukupuoli)) => sukupuoli
      case _                     => "0"
    }
}

case class Osaaminen(
  yleinen_kielitutkinto_fi: Option[String],
  valtionhallinnon_kielitutkinto_fi: Option[String],
  yleinen_kielitutkinto_sv: Option[String],
  valtionhallinnon_kielitutkinto_sv: Option[String],
  yleinen_kielitutkinto_en: Option[String],
  valtionhallinnon_kielitutkinto_en: Option[String],
  yleinen_kielitutkinto_se: Option[String],
  valtionhallinnon_kielitutkinto_se: Option[String]
)

class HakijaActor(
  hakupalvelu: Hakupalvelu,
  organisaatioActor: OrganisaatioActorRef,
  koodistoActor: KoodistoActorRef,
  valintaTulosActor: ValintaTulosActorRef,
  config: Config
) extends Actor
    with ActorLogging {
  implicit val executionContext: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )
  implicit val defaultTimeout: Timeout = 120.seconds
  val tuntematonOppilaitos = "00000"

  def receive = {
    case q: HakijaQuery => {
      Try(q.version match {
        case 1 => XMLQuery(q) pipeTo sender
        case 2 => JSONQuery(q) pipeTo sender
        case 3 => JSONQueryV3(q) pipeTo sender
        case 4 => JSONQueryV4(q) pipeTo sender
        case 5 => JSONQueryV5(q) pipeTo sender
      }) match {
        case Failure(fail) =>
          log.error(s"Unexpected failure ${fail}")
        case _ =>
      }
    }
    case something =>
      log.error(s"Unexpected query ${something}")
  }

  def resolveOppilaitosKoodi(o: Organisaatio): Future[Option[String]] = o.oppilaitosKoodi match {
    case None    => findOppilaitoskoodi(o.parentOid)
    case Some(k) => Future.successful(Some(k))
  }

  def getOrg(oid: String): Future[Option[Organisaatio]] = {
    (organisaatioActor.actor ? oid)
      .mapTo[Option[Organisaatio]]
      .recover { case t =>
        log.error(t, s"Fetching organisaatio for oid $oid failed")
        None
      }
  }

  def findOppilaitoskoodi(parentOid: Option[String]): Future[Option[String]] = parentOid match {
    case None => Future.successful(Some(tuntematonOppilaitos))
    case Some(oid) =>
      getOrg(oid).flatMap(
        _.fold[Future[Option[String]]](Future.successful(Some(tuntematonOppilaitos)))(
          resolveOppilaitosKoodi
        )
      )
  }

  def hakutoive2XMLHakutoive(ht: Hakutoive): Future[Option[XMLHakutoive]] = {
    for (
      orgData: Option[(Organisaatio, String)] <- findOrgData(ht.hakukohde.koulutukset.head.tarjoaja)
    )
      yield for ((org: Organisaatio, oppilaitos: String) <- orgData)
        yield XMLHakutoive(ht, org, oppilaitos)
  }

  def getXmlHakutoiveet(hakija: Hakija): Future[Seq[XMLHakutoive]] = {
    val futureToiveet = for (ht <- hakija.hakemus.hakutoiveet) yield hakutoive2XMLHakutoive(ht)
    futureToiveet.join.map(_.flatten)
  }

  def extractOption(t: (Option[Organisaatio], Option[String])): Option[(Organisaatio, String)] =
    t._1 match {
      case None    => None
      case Some(o) => Some((o, t._2.get))
    }

  def findOrgData(tarjoaja: String): Future[Option[(Organisaatio, String)]] = {
    getOrg(tarjoaja).flatMap((o) => findOppilaitoskoodi(o.map(_.oid)).map(k => extractOption(o, k)))
  }

  def createHakemus(hakija: Hakija)(
    opiskelija: Option[Opiskelija],
    org: Option[Organisaatio],
    ht: Seq[XMLHakutoive],
    os: Option[XMLOsaaminen]
  ) = XMLHakemus(hakija, opiskelija, org, ht, os)

  def getXmlHakemus(hakija: Hakija): Future[XMLHakemus] = {
    val (opiskelutieto, lahtokoulu) = getOpiskelijaTiedot(hakija)
    val ht: Future[Seq[XMLHakutoive]] = getXmlHakutoiveet(hakija)
    val osaaminen: Future[Option[XMLOsaaminen]] = Future.successful(
      Option(
        XMLOsaaminen(
          hakija.hakemus.osaaminen.yleinen_kielitutkinto_fi,
          hakija.hakemus.osaaminen.valtionhallinnon_kielitutkinto_fi,
          hakija.hakemus.osaaminen.yleinen_kielitutkinto_sv,
          hakija.hakemus.osaaminen.valtionhallinnon_kielitutkinto_sv,
          hakija.hakemus.osaaminen.yleinen_kielitutkinto_en,
          hakija.hakemus.osaaminen.valtionhallinnon_kielitutkinto_en,
          hakija.hakemus.osaaminen.yleinen_kielitutkinto_se,
          hakija.hakemus.osaaminen.valtionhallinnon_kielitutkinto_se
        )
      )
    )
    val data = (opiskelutieto, lahtokoulu, ht, osaaminen).join

    data.tupledMap(createHakemus(hakija))
  }

  def getOpiskelijaTiedot(
    hakija: Hakija
  ): (Future[Option[Opiskelija]], Future[Option[Organisaatio]]) = hakija.opiskeluhistoria match {
    case opiskelijaTiedot :: _ =>
      (Future.successful(Some(opiskelijaTiedot)), getOrg(opiskelijaTiedot.oppilaitosOid))
    case _ => (Future.successful(None), Future.successful(None))
  }

  def getMaakoodi(koodiArvo: String): Future[String] = koodiArvo.toLowerCase match {
    case "fin" => Future.successful("246")
    case ""    => Future.successful("")
    case arvo =>
      val maaFuture = (koodistoActor.actor ? GetRinnasteinenKoodiArvoQuery(
        "maatjavaltiot1",
        arvo,
        "maatjavaltiot2"
      )).mapTo[String]
      maaFuture.failed.foreach { case t: Throwable =>
        log.error(t, s"failed to fetch country $koodiArvo")
      }
      maaFuture
  }

  def getPostitoimipaikka(
    maa: String,
    postitoimipaikka: String,
    postinumero: String
  ): Future[String] = maa match {
    case "246" =>
      val postitoimipaikkaFuture =
        (koodistoActor.actor ? GetKoodi("posti", s"posti_$postinumero")).mapTo[Option[Koodi]]
      postitoimipaikkaFuture.failed.foreach { case t: Throwable =>
        log.error(t, s"failed to fetch postoffice for code $postinumero")
      }
      postitoimipaikkaFuture.map(koodi => {
        koodi
          .map(
            _.metadata
              .find(_.kieli.toLowerCase == "fi")
              .map(_.nimi)
              .getOrElse("")
          )
          .getOrElse("")
      })
    case arvo => Future.successful(postitoimipaikka)
  }

  def hakija2XMLHakija(hakija: Hakija): Future[XMLHakija] = {
    getXmlHakemus(hakija).map(data2XmlHakija(hakija))
  }

  def hakija2JSONHakija(hakija: Hakija): Future[JSONHakija] = {
    getXmlHakemus(hakija).map(data2JsonHakija(hakija))
  }

  def hakija2JSONHakijaV3(hakija: Hakija): Future[JSONHakija] = {
    getXmlHakemus(hakija).map(data2JsonHakijaV3(hakija))
  }

  def hakija2JSONHakijaV4(hakija: Hakija): Future[JSONHakijaV4] = {
    getXmlHakemus(hakija).map(data2JsonHakijaV4(hakija))
  }

  def hakija2JSONHakijaV5(hakija: Hakija): Future[JSONHakijaV5] = {
    getXmlHakemus(hakija).map(data2JsonHakijaV5(hakija))
  }

  def data2XmlHakija(hakija: Hakija)(hakemus: XMLHakemus) = {
    val hakutoiveet2 = hakemus.hakutoiveet.map(toive => toive.copy(koulutuksenKieli = None))
    val hakemus2 = hakemus.copy(osaaminen = None, hakutoiveet = hakutoiveet2)
    XMLHakija(hakija, hakemus2)
  }

  def data2JsonHakijaV3(hakija: Hakija)(hakemus: XMLHakemus) = {
    JSONHakija(hakija, hakemus)
  }

  def data2JsonHakijaV4(hakija: Hakija)(hakemus: XMLHakemus) = {
    JSONHakijaV4(hakija, hakemus)
  }

  def data2JsonHakijaV5(hakija: Hakija)(hakemus: XMLHakemus) = {
    JSONHakijaV5(hakija, hakemus)
  }

  def data2JsonHakija(hakija: Hakija)(hakemus: XMLHakemus) = {
    val hakutoiveet2 = hakemus.hakutoiveet.map(toive => toive.copy(koulutuksenKieli = None))
    val hakemus2 = hakemus.copy(osaaminen = None, hakutoiveet = hakutoiveet2)
    JSONHakija(hakija, hakemus2)
  }

  def hakijat2XmlHakijat(hakijat: Seq[Hakija]): Future[Seq[XMLHakija]] =
    hakijat.map(hakija2XMLHakija).join

  def hakijat2JsonHakijatV2(hakijat: Seq[Hakija]): Future[Seq[JSONHakija]] =
    hakijat.map(hakija2JSONHakija).join

  def hakijat2JsonHakijatV3(hakijat: Seq[Hakija]): Future[Seq[JSONHakija]] =
    hakijat.map(hakija2JSONHakijaV3).join

  def hakijat2JsonHakijatV4(hakijat: Seq[Hakija]): Future[Seq[JSONHakijaV4]] =
    hakijat.map(hakija2JSONHakijaV4).join

  def hakijat2JsonHakijatV5(hakijat: Seq[Hakija]): Future[Seq[JSONHakijaV5]] =
    hakijat.map(hakija2JSONHakijaV5).join

  def matchSijoitteluAndHakemus(hakijas: Seq[Hakija])(tulos: SijoitteluTulos): Seq[Hakija] =
    hakijas
      .map(tila(tulos.valintatila, tulos.vastaanottotila, tulos.ilmoittautumistila))
      .map(yhteispisteet(tulos.pisteet))

  def yhteispisteet(pisteet: Map[(String, String), BigDecimal])(h: Hakija): Hakija = {
    val toiveet = h.hakemus.hakutoiveet.map((ht) => {
      val oid: String = ht.hakukohde.oid
      val yhteispisteet: Option[BigDecimal] = pisteet.get(h.hakemus.hakemusnumero, oid)
      ht.copy(yhteispisteet = yhteispisteet)
    })
    h.copy(hakemus = h.hakemus.copy(hakutoiveet = toiveet))
  }

  def tila(
    valinta: Map[(String, String), Valintatila],
    vastaanotto: Map[(String, String), Vastaanottotila],
    ilmoittautumistila: Map[(String, String), Ilmoittautumistila]
  )(h: Hakija): Hakija = {
    val hakemusnumero: String = h.hakemus.hakemusnumero
    h.copy(hakemus =
      h.hakemus.copy(hakutoiveet =
        for (ht <- h.hakemus.hakutoiveet)
          yield ht.copy(
            valinta = valinta.get(hakemusnumero, ht.hakukohde.oid),
            vastaanotto = vastaanotto.get(hakemusnumero, ht.hakukohde.oid),
            ilmoittautumistila = ilmoittautumistila.get(hakemusnumero, ht.hakukohde.oid)
          )
      )
    )
  }

  def combine2sijoittelunTulos(user: Option[User])(hakijat: Seq[Hakija]): Future[Seq[Hakija]] = {
    Future.foldLeft(
      hakijat.groupBy(_.hakemus.hakuOid).map { case (hakuOid, hakijas) =>
        valintaTulosActor.actor
          .?(HaunValintatulos(hakuOid))(timeout = config.valintaTulosTimeout)
          .mapTo[SijoitteluTulos]
          .map(matchSijoitteluAndHakemus(hakijas))
      }
    )(Seq[Hakija]())(_ ++ _)
  }

  def hakutoiveFilter(predicate: (XMLHakutoive) => Boolean)(xh: XMLHakija): XMLHakija =
    xh.copy(hakemus = xh.hakemus.copy(hakutoiveet = xh.hakemus.hakutoiveet.filter(predicate)))

  def matchOrganisaatio(oid: Option[String], parentOidPath: Set[String]): Boolean = oid match {
    case Some(o) => parentOidPath.isEmpty || parentOidPath.contains(o)
    case None    => true
  }

  def matchHakukohdekoodi(koodi: Option[String], koulutuskoodi: String): Boolean = koodi match {
    case Some(k) => koulutuskoodi == k || k.split('_').last == koulutuskoodi
    case None    => true
  }

  def matchesHakukohdeKoodi(h: Hakutoive, q: HakijaQuery): Boolean = {
    def getSuffix(s: String): String = s.split('_').last

    q.hakukohdekoodi.isEmpty || getSuffix(h.hakukohde.hakukohdekoodi) == getSuffix(
      q.hakukohdekoodi.get
    )
  }

  def matchesOrganisation(h: Hakutoive, q: HakijaQuery): Boolean =
    q.organisaatio.isEmpty || h.organisaatioParentOids.contains(q.organisaatio.get)

  def getAllowedOrgsForUser(user: User): Set[String] = {
    user.orgsFor("READ", "Hakukohde")
  }

  def filterByQuery(q: HakijaQuery)(toiveet: Seq[Hakutoive]): Seq[Hakutoive] = {
    val hakutoives: Seq[Hakutoive] = q.hakuehto match {
      case Hakuehto.Kaikki => toiveet
      case Hakuehto.Hyvaksytyt =>
        toiveet.filter(h =>
          (h.valinta.exists(Valintatila.isHyvaksytty) ||
            h.vastaanotto.contains(Vastaanottotila.PERUNUT) ||
            h.vastaanotto.contains(Vastaanottotila.PERUUTETTU)) &&
            matchOrganisaatio(q.organisaatio, h.organisaatioParentOids) &&
            matchHakukohdekoodi(q.hakukohdekoodi, h.hakukohde.hakukohdekoodi)
        )
      case Hakuehto.Vastaanottaneet =>
        toiveet.filter(h =>
          h.vastaanotto.exists(Vastaanottotila.isVastaanottanut) &&
            matchOrganisaatio(q.organisaatio, h.organisaatioParentOids) &&
            matchHakukohdekoodi(q.hakukohdekoodi, h.hakukohde.hakukohdekoodi)
        )
      case Hakuehto.Hylatyt =>
        toiveet.filter(h =>
          h.valinta.contains(Valintatila.HYLATTY) &&
            matchOrganisaatio(q.organisaatio, h.organisaatioParentOids) &&
            matchHakukohdekoodi(q.hakukohdekoodi, h.hakukohde.hakukohdekoodi)
        )
    }

    if (q.version == 1)
      hakutoives
    else if (q.version == 5 && q.haku.getOrElse("").length == 35) {
      hakutoives //In the Ataru + Kouta case, this filtering of hakutoivees is done earlier, immediately after fetching hakemukses.
    } else
      hakutoives.filter(h => matchesHakukohdeKoodi(h, q) && matchesOrganisation(h, q))
  }

  def filterHakijatHakutoiveetByQuery(q: HakijaQuery)(hakijat: Seq[Hakija]): Future[Seq[Hakija]] = {
    val orgsForUser = q.user.map(u => getAllowedOrgsForUser(u)).getOrElse(Set.empty)
    if (orgsForUser.nonEmpty) {
      Future.successful(hakijat.flatMap(filterHakutoiveetByQuery(q, orgsForUser)(_)))
    } else {
      log.warning(
        s"Ei sallittuja hakukohteiden lukuoikeuksia käyttäjälle (query $q), joten ei palauteta tietoja"
      )
      Future.successful(Seq.empty)
    }
  }

  def filterByAllowed(
    orgs: Set[String],
    hakija: String,
    hakutoivees: Seq[Hakutoive]
  ): Seq[Hakutoive] = {
    if (orgs.nonEmpty) {
      log.info(s"first ${hakutoivees.head.organisaatioParentOids}")
      hakutoivees.foreach(ht =>
        if (ht.organisaatioParentOids.isEmpty) {
          log.warning(s"hakutoiveella $ht ei ole sallittuja organisaatiota!")
        }
      )
      val filtered = hakutoivees.filter(ht => orgs.intersect(ht.organisaatioParentOids).nonEmpty)
      if (filtered.size < hakutoivees.size) {
        log.info(
          s"Filtteröitiin hakijalta pois $hakija ${hakutoivees.size - filtered.size}/${hakutoivees.size} hakutoivetta pois oikeuksien puuttumisen takia"
        )
      }
      filtered
    } else {
      log.warning("Ei sallittuja organisaatioita, joten filtteröidään kaikki hakutoiveet pois!")
      Seq()
    }
  }

  def filterHakutoiveetByQuery(q: HakijaQuery, orgs: Set[String])(
    hakija: Hakija
  ): Option[Hakija] = {
    val allowedHakutoiveet =
      filterByAllowed(orgs, hakija.hakemus.hakemusnumero, hakija.hakemus.hakutoiveet)
    val filteredHakutoiveet = filterByQuery(q)(allowedHakutoiveet)

    if (filteredHakutoiveet.nonEmpty) {
      Some(
        hakija.copy(
          hakija.henkilo,
          hakija.suoritukset,
          hakija.opiskeluhistoria,
          hakemus = hakija.hakemus.copy(hakutoiveet = filteredHakutoiveet)
        )
      )
    } else {
      None
    }
  }

  def enrichHakijat(hakijat: Seq[Hakija]): Future[Seq[Hakija]] = Future.sequence(for {
    hakija <- hakijat
  } yield for {
    kansalaisuus <- getMaakoodi(hakija.henkilo.kansalaisuus.getOrElse(""))
    kaksoiskansalaisuus <- getMaakoodi(hakija.henkilo.kaksoiskansalaisuus.getOrElse(""))
    kansalaisuudet <- Future.sequence(
      hakija.henkilo.kansalaisuudet.getOrElse(List.empty).map(k => getMaakoodi(k))
    )
    maa <- getMaakoodi(hakija.henkilo.maa)
    postitoimipaikka <- getPostitoimipaikka(
      maa,
      hakija.henkilo.postitoimipaikka,
      hakija.henkilo.postinumero
    )
  } yield {
    val h = hakija.henkilo
    Hakija(
      henkilo = Henkilo(
        hetu = h.hetu,
        syntymaaika = h.syntymaaika,
        oppijanumero = h.oppijanumero,
        sukupuoli = h.sukupuoli,
        sukunimi = h.sukunimi,
        etunimet = h.etunimet,
        kutsumanimi = h.kutsumanimi,
        turvakielto = h.turvakielto,
        lahiosoite = h.lahiosoite,
        postinumero = h.postinumero,
        maa = maa,
        postitoimipaikka = postitoimipaikka,
        matkapuhelin = h.matkapuhelin,
        puhelin = h.puhelin,
        sahkoposti = h.sahkoposti,
        kotikunta = h.kotikunta,
        kansalaisuus = Some(kansalaisuus),
        kaksoiskansalaisuus = if (kaksoiskansalaisuus.nonEmpty) Some(kaksoiskansalaisuus) else None,
        kansalaisuudet = Some(kansalaisuudet),
        asiointiKieli = h.asiointiKieli,
        opetuskieli = h.opetuskieli,
        eiSuomalaistaHetua = h.eiSuomalaistaHetua,
        markkinointilupa = h.markkinointilupa,
        kiinnostunutoppisopimuksesta = h.kiinnostunutoppisopimuksesta,
        huoltajannimi = h.huoltajannimi,
        huoltajanpuhelinnumero = h.huoltajanpuhelinnumero,
        huoltajansahkoposti = h.huoltajansahkoposti,
        oppivelvollisuusVoimassaAsti = h.oppivelvollisuusVoimassaAsti,
        oikeusMaksuttomaanKoulutukseenVoimassaAsti = h.oikeusMaksuttomaanKoulutukseenVoimassaAsti,
        lisakysymykset = h.lisakysymykset,
        liitteet = h.liitteet,
        muukoulutus = h.muukoulutus,
        aidinkieli = h.aidinkieli
      ),
      suoritukset = hakija.suoritukset,
      opiskeluhistoria = hakija.opiskeluhistoria,
      hakemus = hakija.hakemus
    )
  })

  def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = {
    hakupalvelu
      .getHakijatByQuery(q)
      .flatMap(enrichHakijat)
      .flatMap(combine2sijoittelunTulos(q.user))
      .flatMap(filterHakijatHakutoiveetByQuery(q))
  }

  val hakijaWithValittu: (XMLHakija) => XMLHakija = hakutoiveFilter(_.valinta == Some("1"))

  val hakijaWithVastaanotettu = hakutoiveFilter(_.vastaanotto == Some("3")) _

  def XMLQuery(q: HakijaQuery): Future[XMLHakijat] =
    getHakijat(q).flatMap(hakijat2XmlHakijat).map(XMLHakijat)
  def JSONQuery(q: HakijaQuery): Future[JSONHakijat] =
    getHakijat(q).flatMap(hakijat2JsonHakijatV2).map(JSONHakijat)
  def JSONQueryV3(q: HakijaQuery): Future[JSONHakijat] =
    getHakijat(q).flatMap(hakijat2JsonHakijatV3).map(JSONHakijat)
  def JSONQueryV4(q: HakijaQuery): Future[JSONHakijatV4] =
    getHakijat(q).flatMap(hakijat2JsonHakijatV4).map(JSONHakijatV4)
  def JSONQueryV5(q: HakijaQuery): Future[JSONHakijatV5] =
    getHakijat(q).flatMap(hakijat2JsonHakijatV5).map(JSONHakijatV5)

}

case class HakijaQuery(
  haku: Option[String],
  organisaatio: Option[String],
  hakukohdekoodi: Option[String],
  hakuehto: Hakuehto.Hakuehto,
  user: Option[User],
  version: Int
) extends Query

object Hakuehto extends Enumeration {
  type Hakuehto = Value
  val Kaikki, Hyvaksytyt, Vastaanottaneet, Hylatyt = Value
}
