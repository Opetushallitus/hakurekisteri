package fi.vm.sade.hakurekisteri.integration.henkilo

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusHenkilotiedot
import fi.vm.sade.hakurekisteri.integration.mocks.HenkiloMock
import org.apache.commons.httpclient.HttpStatus
import org.json4s.jackson.JsonMethods._
import org.json4s.{DefaultFormats, _}
import support.PersonAliasesProvider

import scala.collection.Iterator
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Fields:
  *
  *    oidToLinkedOids: map where every oid is mathed to set of linked oids
  *
  *                    Example: Henkilos A and B are linked. C is not linked. Therefore this method returns map:
  *                    A -> [A, B]
  *                    B -> [A, B]
  *                    C -> [C]
  *
  *    oidToMasterOid: map where every oid is mapped to its masterOid
  */
case class LinkedHenkiloOids(
  oidToLinkedOids: Map[String, Set[String]],
  oidToMasterOid: Map[String, String]
)

trait IOppijaNumeroRekisteri {
  def fetchLinkedHenkiloOidsMap(henkiloOids: Set[String]): Future[LinkedHenkiloOids]

  def enrichWithAliases(henkiloOids: Set[String]): Future[PersonOidsWithAliases] = {
    fetchLinkedHenkiloOidsMap(henkiloOids)
      .map(_.oidToLinkedOids)
      .map(PersonOidsWithAliases(henkiloOids, _))
  }

  def getByHetu(hetu: String): Future[Henkilo]

  def getByOids(oids: Set[String]): Future[Map[String, Henkilo]]
}

object IOppijaNumeroRekisteri {

  /**
    *    Appends linked henkilo oids to henkiloOids Set.
    */
  def combineLinkedHenkiloOids(
    henkiloOids: Set[String],
    links: Map[String, Set[String]]
  ): Set[String] = {
    henkiloOids.flatMap((oid: String) => links.getOrElse(oid, Set(oid)))
  }
}

class OppijaNumeroRekisteri(client: VirkailijaRestClient, val system: ActorSystem, config: Config)
    extends IOppijaNumeroRekisteri {
  private val logger = Logging.getLogger(system, this)

  override def fetchLinkedHenkiloOidsMap(henkiloOids: Set[String]): Future[LinkedHenkiloOids] = {
    logger.info(
      s"fetchLinkedHenkiloOidsMap for ${henkiloOids.size} henkilos, first 100: ${henkiloOids.take(100)}"
    )
    if (henkiloOids.isEmpty) {
      Future.successful(LinkedHenkiloOids(Map(), Map()))
    } else {
      queryFromOppijaNumeroRekisteri(henkiloOids)
    }
  }

  private def queryFromOppijaNumeroRekisteri(
    henkiloOids: Set[String]
  ): Future[LinkedHenkiloOids] = {
    val queryObject: Map[String, Set[String]] = Map("henkiloOids" -> henkiloOids)
    logger.debug(s"Querying with $queryObject")

    client
      .postObject[Map[String, Set[String]], Seq[HenkiloViite]](
        "oppijanumerorekisteri-service.duplicatesByPersonOids"
      )(resource = queryObject, acceptedResponseCode = HttpStatus.SC_OK)
      .map(viitteet => {
        val oidToLinkedOids = {
          val viitteetByMasterOid =
            viitteet.groupBy(_.masterOid).map(kv => (kv._1, kv._2.map(_.henkiloOid)))
          val viitteetByLinkedOid =
            viitteet.groupBy(_.henkiloOid).map(kv => (kv._1, kv._2.map(_.masterOid)))
          val allPairs: Map[String, Seq[String]] = viitteetByMasterOid ++ viitteetByLinkedOid
          henkiloOids
            .map((queriedOid: String) => {
              val allAliases: Seq[String] = allPairs.getOrElse(queriedOid, Nil)
              (queriedOid, (List(queriedOid) ++ allAliases).toSet)
            })
            .toMap
        }
        val oidToMasterOid = viitteet
          .filter(viite => oidToLinkedOids.contains(viite.henkiloOid))
          .map(viite => (viite.henkiloOid, viite.masterOid))
          .toMap
        LinkedHenkiloOids(oidToLinkedOids, oidToMasterOid)
      })
  }

  override def getByHetu(hetu: String): Future[Henkilo] = {
    logger.debug(s"Querying with hetu ${hetu.substring(0, 6)}XXXX")
    client.readObject[Henkilo]("oppijanumerorekisteri-service.henkilo.byHetu", hetu)(
      acceptedResponseCode = HttpStatus.SC_OK
    )
  }

  override def getByOids(oids: Set[String]): Future[Map[String, Henkilo]] = {
    if (oids.isEmpty) {
      Future.successful(Map.empty)
    } else {
      Future
        .sequence(
          oids
            .grouped(config.integrations.oppijaNumeroRekisteriMaxOppijatBatchSize)
            .map(oids =>
              client.postObject[Set[String], Map[String, Henkilo]](
                "oppijanumerorekisteri-service.henkilotByOids"
              )(resource = oids, acceptedResponseCode = 200)
            )
        )
        .map(_.reduce((m, mm) => m ++ mm))
    }
  }
}

object MockOppijaNumeroRekisteri extends IOppijaNumeroRekisteri {
  implicit val formats = DefaultFormats
  val masterOid = "1.2.246.562.24.67587718272"
  val henkiloOid = "1.2.246.562.24.58099330694"
  val linkedTestPersonOids = Seq(henkiloOid, masterOid)

  def fetchLinkedHenkiloOidsMap(henkiloOids: Set[String]): Future[LinkedHenkiloOids] = {
    Future.successful({
      val oidToLinkedOids = henkiloOids.map { queriedOid =>
        if (linkedTestPersonOids.contains(queriedOid)) {
          (queriedOid, linkedTestPersonOids.toSet)
        } else {
          (queriedOid, Set(queriedOid))
        }
      }.toMap
      LinkedHenkiloOids(oidToLinkedOids, Map(henkiloOid -> masterOid))
    })
  }

  def getByHetu(hetu: String): Future[Henkilo] = {
    val json = parse(HenkiloMock.getHenkiloByOid("1.2.246.562.24.71944845619"))
    Future.successful(json.extract[Henkilo])
  }

  def getByOids(oids: Set[String]): Future[Map[String, Henkilo]] =
    Future.successful(oids.zipWithIndex.map { case (oid, i) =>
      oid -> Henkilo(
        oidHenkilo = oid,
        hetu = Some(s"Hetu$i"),
        henkiloTyyppi = "OPPIJA",
        etunimet = Some(s"Etunimi$i"),
        kutsumanimi = Some(s"Kutsumanimi$i"),
        sukunimi = Some(s"Sukunimi$i"),
        aidinkieli = Some(Kieli("fi")),
        kansalaisuus = List(Kansalaisuus("246")),
        syntymaaika = Some("1989-09-24"),
        sukupuoli = Some("1"),
        turvakielto = Some(false)
      )
    }.toMap)
}

object MockPersonAliasesProvider extends PersonAliasesProvider {
  override def enrichWithAliases(henkiloOids: Set[String]): Future[PersonOidsWithAliases] =
    MockOppijaNumeroRekisteri.enrichWithAliases(henkiloOids)
}

case class HenkiloViite(henkiloOid: String, masterOid: String)

case class PersonOidsWithAliases(
  henkiloOids: Set[String],
  aliasesByPersonOids: Map[String, Set[String]],
  henkiloOidsWithLinkedOids: Set[String]
) {
  def grouped(size: Int): Iterator[PersonOidsWithAliases] = {
    henkiloOids
      .grouped(size)
      .map(oidsOfGroup => {
        val aliasMapOfGroup: Map[String, Set[String]] =
          aliasesByPersonOids.filter(oidWithAliases => oidsOfGroup.contains(oidWithAliases._1))
        PersonOidsWithAliases(oidsOfGroup, aliasMapOfGroup)
      })
  }

  def diff(henkiloOidsToRemove: Set[String]): PersonOidsWithAliases = {
    PersonOidsWithAliases(
      henkiloOids -- henkiloOidsToRemove,
      aliasesByPersonOids -- henkiloOidsToRemove
    )
  }

  def intersect(henkiloOidsToInclude: Set[String]): PersonOidsWithAliases = {
    PersonOidsWithAliases(
      henkiloOids.intersect(henkiloOidsToInclude),
      aliasesByPersonOids.filter { case (key, value) => henkiloOidsToInclude.contains(key) }
    )
  }

  def isEmpty: Boolean = aliasesByPersonOids.isEmpty

  private def isSinglePersonWithoutAliases: Boolean =
    henkiloOids.size == 1 && aliasesByPersonOids(henkiloOids.head).size == 1

  def uniquePersonOid: Option[String] = {
    if (isSinglePersonWithoutAliases) Some(henkiloOids.head) else None
  }
}

object PersonOidsWithAliases {

  /**
    * @return a dummy object with just the given oids as their aliases, so we don't have to migrate everything at once
    */
  @Deprecated // The places where this is used should be updated to use real linked data"
  def apply(henkiloOids: Set[String]): PersonOidsWithAliases =
    PersonOidsWithAliases(henkiloOids, henkiloOids.map(h => (h, Set(h))).toMap, henkiloOids)

  def apply(
    queriedOids: Set[String],
    aliasesByPersonOids: Map[String, Set[String]]
  ): PersonOidsWithAliases = {
    val combinedOidSet =
      IOppijaNumeroRekisteri.combineLinkedHenkiloOids(queriedOids, aliasesByPersonOids)
    PersonOidsWithAliases(queriedOids, aliasesByPersonOids, combinedOidSet)
  }
}
