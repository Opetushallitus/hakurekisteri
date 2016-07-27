package util

import java.util.UUID

import akka.actor.ActorSystem
import com.github.nscala_time.time.TypeImports._
import dispatch.{Http, as, url}
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvosana, ArvosanaTable}
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloSearchResponse
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaTable}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusTable, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.{Config, Oids}
import generators.DataGen
import org.joda.time.DateTime

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

object CreateDevDb extends App {
  println("Creating a development db")

  val kevaanVuosi = if (DateTime.now isBefore(new MonthDay(8, 18).toLocalDate(DateTime.now.getYear).toDateTimeAtStartOfDay()))
    DateTime.now.getYear
  else
    DateTime.now.getYear + 1

  val kevatJuhla = new MonthDay(6,4).toLocalDate(kevaanVuosi)
  val syksynAlku = new MonthDay(8, 18).toLocalDate(kevaanVuosi - 1)



  implicit val db = Database.forURL(Config.globalConfig.h2DatabaseUrl, driver = "org.h2.Driver")

  implicit val system = ActorSystem("db-import")

  implicit val ec: ExecutionContext = system.dispatcher

  println(Config.globalConfig.integrations.henkiloConfig)

  val henkiloClient = new VirkailijaRestClient(Config.globalConfig.integrations.henkiloConfig, None)

  val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
  val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable])
  val arvosanaJournal = new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable])

  for (
    henkilos <- henkiloClient.readObject[HenkiloSearchResponse]("authentication-service.henkilo.find", Map("ht" -> "OPPIJA", "index" -> 0, "count" -> 50, "no" -> true, "s" -> true, "p" -> false))(200);
    aineet <- findPakolliset
  ) {
    for (
      henkilo <- henkilos.results
    ) createOppilas(henkilo.oidHenkilo, aineet)
    println()
    Await.result(system.terminate(), 15.seconds)
    db.close()
  }

  def createOppilas(oid:String, aineet: Set[String]) {
    val suoritus = UUID.randomUUID()
    suoritusJournal.addModification(Updated(VirallinenSuoritus(Oids.perusopetusKomoOid, "1.2.246.562.10.39644336305", "KESKEN", kevatJuhla, oid, yksilollistaminen.Ei, "fi",  lahde = "Test").identify(suoritus)))
    opiskelijaJournal.addModification(Updated(Opiskelija("1.2.246.562.10.39644336305", "9", "9A", oid, syksynAlku.toDateTimeAtStartOfDay(), Some(kevatJuhla.toDateTimeAtStartOfDay()), "Test").identify(UUID.randomUUID())))
    for (
      aine <- aineet
    ) {
      val arvosana = Arvosana(suoritus, Arvio410(DataGen.int(5,10).generate.toString), aine, if (aine == "A1") Some("EN") else None , false, Some(kevatJuhla), "Test", Map())
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