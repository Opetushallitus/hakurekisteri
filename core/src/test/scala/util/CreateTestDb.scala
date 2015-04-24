package util

import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, HakurekisteriDriver}
import HakurekisteriDriver.simple._
import scala.slick.jdbc.meta.MTable
import fi.vm.sade.hakurekisteri.batchimport.{ImportStatus, BatchState, ImportBatch, ImportBatchTable}
import java.util.UUID
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, VirallinenSuoritus, Suoritus, SuoritusTable}
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.Config
import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloSearchResponse
import scala.concurrent.{Future, ExecutionContext}
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.tools.Peruskoulu
import com.github.nscala_time.time.TypeImports._
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloSearchResponse
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import org.joda.time.{DateTime, LocalDate}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaTable, Opiskelija}
import dispatch.{Http, as, url}
import scala.util.Try
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, ArvosanaTable, Arvosana}
import generators.DataGen

object CreateTestDb extends App {
  println("Creating a test db")

  val kevaanVuosi = if (DateTime.now isBefore(new MonthDay(8, 18).toLocalDate(DateTime.now.getYear).toDateTimeAtStartOfDay()))
    DateTime.now.getYear
  else
    DateTime.now.getYear + 1

  val kevatJuhla = new MonthDay(6,4).toLocalDate(kevaanVuosi)
  val syksynAlku = new MonthDay(8, 18).toLocalDate(kevaanVuosi - 1)



  implicit val db = Database.forURL("jdbc:h2:file:data/sample", driver = "org.h2.Driver")

  implicit val system = ActorSystem("db-import")

  implicit val ec: ExecutionContext = system.dispatcher

  println(Config.globalConfig.integrations.henkiloConfig)

  val henkiloClient = new VirkailijaRestClient(Config.globalConfig.integrations.henkiloConfig, None)

  val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
  val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable])
  val arvosanaJournal = new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable])



  for (
    henkilos <- henkiloClient.readObject[HenkiloSearchResponse]("/resources/henkilo/?ht=OPPIJA&index=0&count=50&no=true&s=true&p=false", 200);
    aineet <- findPakolliset
  ) {
    for (
      henkilo <- henkilos.results
    ) createOppilas(henkilo.oidHenkilo, aineet)
    println()
    system.shutdown()
  }

  system.awaitTermination()

  def createOppilas(oid:String, aineet: Set[String]) {
    val suoritus = UUID.randomUUID()
    suoritusJournal.addModification(Updated(VirallinenSuoritus(Config.globalConfig.oids.perusopetusKomoOid, "1.2.246.562.10.39644336305", "KESKEN", kevatJuhla, oid, yksilollistaminen.Ei, "fi",  lahde = "Test").identify(suoritus)))
    opiskelijaJournal.addModification(Updated(Opiskelija("1.2.246.562.10.39644336305", "9", "9A", oid, syksynAlku.toDateTimeAtStartOfDay(), Some(kevatJuhla.toDateTimeAtStartOfDay()), "Test").identify(UUID.randomUUID())))
    for (
      aine <- aineet
    ) {
      val arvosana = Arvosana(suoritus, Arvio410(DataGen.int(5,10).generate.toString), aine, if (aine == "A1") Some("EN") else None , false, Some(kevatJuhla), "Test")
      arvosanaJournal.addModification(Updated(arvosana.identify(UUID.randomUUID())))
    }
    print(".")
  }

  def findPakolliset: Future[Set[String]] = {
    for (muut <- getAineetWith(findPerusasteenAineet, "oppiaineenkielisyys_0")) yield Set("A1") ++ muut.toSet
  }

  def getAineetWith(perusaste: Future[Seq[(Koodi, Set[String])]], elem: String): Future[Seq[String]] = {
    for (aineet <- perusaste) yield
      for (aine <- aineet;
           if aine._2.contains(elem)) yield aine._1("koodiArvo").toString
  }

  def findPerusasteenAineet =
    for (koodisto <- resolveKoodisto) yield
      for (koodi <- koodisto;
           if koodi._2.contains("onperusasteenoppiaine_1")) yield koodi

  def resolveKoodisto = {

    def withSisaltyy(k:Option[Seq[Koodi]]): Future[Seq[(Koodi, Set[String])]] = Future.sequence(
      for (koodi: Koodi <- k.getOrElse(Seq())) yield
        for (s <- sisaltyy(koodi)) yield (koodi, s.getOrElse(Seq()).map(_("koodiUri").toString).toSet))

    def yl: Future[Option[Seq[Map[String, Any]]]] = {
      val req = url(s"${Config.globalConfig.integrations.koodistoServiceUrl}/rest/json/oppiaineetyleissivistava/koodi/")
      val res: Future[String] = Http(req OK as.String)
      parseBody(res)
    }

    for (k: Option[Seq[Map[String, Any]]] <- yl;
         s <- withSisaltyy(k)) yield s
  }


  def parseBody(res: Future[String]): Future[Option[Seq[Map[String, Any]]]] = {
    import org.json4s.jackson.JsonMethods.parse
    for (result <- res) yield
      for (json <- Try(parse(result)).toOption) yield json.values.asInstanceOf[Seq[Map[String, Any]]]
  }

  type Koodi = Map[String, Any]
  def sisaltyy(koodi: Koodi): Future[Option[Seq[Map[String, Any]]]] = {
    val req = url(s"${Config.globalConfig.integrations.koodistoServiceUrl}/rest/json/relaatio/sisaltyy-alakoodit/${koodi("koodiUri")}")
    val res: Future[String] = Http(req OK as.String)
    parseBody(res)
  }

}