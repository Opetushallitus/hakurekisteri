package fi.vm.sade.hakurekisteri.integration.henkilo

import java.util.concurrent.TimeUnit.MINUTES

import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import org.apache.commons.httpclient.HttpStatus

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

import scala.concurrent.ExecutionContext.Implicits.global

trait IOppijaNumeroRekisteri {
  /**
    Fetches linked henkilo oids from oppijanumerorekisteri.
    Return map where every oid is mathed to set of linked oids

    Example: Henkilos A and B are linked. C is not linked. Therefore this method returns map:
    A -> [A, B]
    B -> [A, B]
    C -> [C]
    */
  def fetchLinkedHenkiloOidsMap(henkiloOids: Set[String]): Map[String, Set[String]]

  def enrichWithAliases(henkiloOids: Set[String]): PersonOidsWithAliases = {
    val linkMap = fetchLinkedHenkiloOidsMap(henkiloOids)
    PersonOidsWithAliases(henkiloOids, linkMap, IOppijaNumeroRekisteri.combineLinkedHenkiloOids(henkiloOids, linkMap))
  }
}

object IOppijaNumeroRekisteri {
  /**
    Appends linked henkilo oids to henkiloOids Set.
   */
  def combineLinkedHenkiloOids(henkiloOids: Set[String], links: Map[String, Set[String]]): Set[String] = {
    henkiloOids.flatMap((oid: String) => links.getOrElse(oid, Set(oid)))
  }
}

class OppijaNumeroRekisteri(client: VirkailijaRestClient) extends IOppijaNumeroRekisteri {
  /**
    * TODO HOX NB HUOM : This is not correct data. See https://jira.oph.ware.fi/jira/browse/KJHH-914
    */
  override def fetchLinkedHenkiloOidsMap(henkiloOids: Set[String]): Map[String, Set[String]] = {
    val linkedPersonsF: Future[Map[String, Set[String]]] = client.readObjectFromUrl[Seq[HenkiloViite]]("oppijanumerorekisteri-service.duplicatesByPersonOids", acceptedResponseCode = HttpStatus.SC_OK).map(viitteet => {
      val masterOids = viitteet.map(_.masterOid)
      val linkedOids = viitteet.map(_.henkiloOid)
      val viitteetByMasterOid = viitteet.groupBy(_.masterOid).map(kv => (kv._1, kv._2.map(_.henkiloOid)))
      val viitteetByLinkedOid = viitteet.groupBy(_.henkiloOid).map(kv => (kv._1, kv._2.map(_.masterOid)))
      val allPairs: Map[String, Seq[String]] = viitteetByMasterOid ++ viitteetByLinkedOid
      henkiloOids.map((queriedOid: String) => {
        val allAliases: Seq[String] = allPairs.getOrElse(queriedOid, Nil)
        (queriedOid, (List(queriedOid) ++ allAliases).toSet)
      }).toMap
    })
    Await.result(linkedPersonsF, Duration(1, MINUTES))
  }
}

object MockOppijaNumeroRekisteri extends IOppijaNumeroRekisteri {

  /**
    Fetches linked henkilo oids from oppijanumerorekisteri.
    Return map where every oid is mathed to set of linked oids

    Example: Henkilos A and B are linked. C is not linked. Therefore this method returns map:
    A -> [A, B]
    B -> [A, B]
    C -> [C]
    */
  def fetchLinkedHenkiloOidsMap(henkiloOids: Set[String]): Map[String, Set[String]] = {
    henkiloOids.map(henkilo => (henkilo, Set(henkilo))).toMap
    //TODO fetch from oppijanumerorekisteri
  }
}

case class HenkiloViite(henkiloOid: String, masterOid: String)

case class PersonOidsWithAliases(henkiloOids: Set[String], links: Map[String, Set[String]], henkiloOidsWithLinkedOids: Set[String])
