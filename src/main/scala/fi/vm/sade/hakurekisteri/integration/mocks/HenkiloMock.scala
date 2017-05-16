package fi.vm.sade.hakurekisteri.integration.mocks


object HenkiloMock {

  def getResourceJson(filename: String): String = {
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename),"UTF-8")
      .getLines
      .mkString
  }

  def henkilotByHenkiloOidList(oids: List[String]): String = {
    if (oids.contains("1.2.246.562.24.71944845619") && oids.size == 1) getResourceJson("/mock-data/henkilo/henkilo-aarne-oidList.json")
    else if (oids.contains("1.2.246.562.24.49719248091") && oids.size == 5) getResourceJson("/mock-data/henkilo/henkilo-all-oidList.json")
    else if (oids.contains("1.2.246.562.24.58099330694") && oids.size == 1) getResourceJson("/mock-data/henkilo/henkilo-matti-oidList.json")
    else "[]"
  }

  def getHenkiloByQParam(q: String): String = {
    if (q.equals("123456-789")) getResourceJson("/mock-data/henkilo/henkilo-aarne.json")
    else if (q.equals("1.2.246.562.24.71944845619")) getResourceJson("/mock-data/henkilo/henkilo-aarne.json")
    else if (q.equals("1.2.246.562.24.98743797763")) getResourceJson("/mock-data/henkilo/henkilo-tyyne.json")
    else getResourceJson("/mock-data/henkilo/henkilo-empty.json")
  }

  def getHenkiloByOid(oid: String): String = {
    if (oid.equals("1.2.246.562.24.71944845619")) getResourceJson("/mock-data/henkilo/henkilo-aarne.json")
    else if (oid.equals("1.2.246.562.24.98743797763")) getResourceJson("/mock-data/henkilo/henkilo-tyyne.json")
    else if (oid.equals("1.2.246.562.24.58099330694")) getResourceJson("/mock-data/henkilo/henkilo-matti.json")
    else getResourceJson("/mock-data/henkilo/henkilo-empty.json")
  }

}
