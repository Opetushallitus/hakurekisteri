package fi.vm.sade.hakurekisteri.opiskelija

import org.scalatra.test.scalatest.ScalatraFunSuite
import akka.actor.{Props, ActorSystem}
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriCrudCommands, HakurekisteriResource, HakurekisteriSwagger}
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.DateTime
import fi.vm.sade.hakurekisteri.storage.repository.InMemJournal
import fi.vm.sade.hakurekisteri.opiskelija._
import fi.vm.sade.hakurekisteri.acceptance.tools.{TestSecurity, FakeAuthorizer}
import scala.collection.immutable.HashMap
import org.scalatest.FunSuite
import scala.util.Random
import fi.vm.sade.hakurekisteri.tools.OidTools

class OpiskelijaActorSpec extends FunSuite {
  
  val startDates = Array(DateTime.parse("2011-08-01"));
  val endDates = Array(DateTime.parse("2014-05-31"), None);

  test("perustesti") {
    
    for(i <- 1 to 1000) {
      
    }
  }

  def genOpiskelija():Opiskelija = {
    val oppiLaitosOid = genOppilaitosOid();
    val luokkataso = genLuokkataso();
    val luokka = genLuokka();
    val henkiloOid = genHenkiloOid;
    val alkuPaiva = startDates(Random.nextInt(startDates.size));
    val loppuPaiva = None;
    
    return new Opiskelija(genOppilaitosOid(), genLuokkataso(), genLuokka(), genHenkiloOid(), genAlkuPaiva(), genLoppuPaiva(), source = "Test");
  }

  def genOppilaitosOid():String = {
    return OidTools.genRandomIBMCheckOID("1.2.246", "562.25");
  }

  def genLuokkataso():String = {
    return Random.nextString(4);
  }

  def genLuokka():String = {
    return Random.nextString(4);
  }

  def genHenkiloOid():String = {
    return OidTools.genRandomIBMCheckOID("1.2.246", "562.24");
  }

  def genAlkuPaiva():DateTime = {
    return DateTime.now();
  }

  def genLoppuPaiva():Option[DateTime] = {
    return None
  }
}
