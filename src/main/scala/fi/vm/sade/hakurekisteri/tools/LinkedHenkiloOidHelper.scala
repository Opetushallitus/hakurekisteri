package fi.vm.sade.hakurekisteri.tools

object LinkedHenkiloOidHelper {

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

  /**
    Appends linked henkilo oids to henkiloOids Set.
   */
  def combineLinkedHenkiloOids(henkiloOids: Set[String], links: Map[String, Set[String]]): Set[String] = {
    henkiloOids.flatMap((oid: String) => links.getOrElse(oid, Set(oid)))
  }

}
