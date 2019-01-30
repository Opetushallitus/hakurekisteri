package fi.vm.sade.hakurekisteri.integration.hakemus

import java.util.UUID

import akka.actor.ActorRef
import akka.event.Logging
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.storage.{Identified, InsertResource, LogMessage}
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object IlmoitetutArvosanatTrigger {

  import scala.language.implicitConversions

  implicit def osaaminen2RicherOsaaminen(osaaminen:Map[String,String]):RicherOsaaminen = RicherOsaaminen(osaaminen)
  implicit def koulutustausta2RicherKoulutustausta(koulutustausta:Map[String,String]):RicherKoulutustausta = RicherKoulutustausta(koulutustausta)

  def arvosanaForSuoritus(arvosana: Arvosana, s: Suoritus with Identified[UUID]): Arvosana = {
    arvosana.copy(suoritus = s.id)
  }

  def muodostaSuorituksetJaArvosanat(hakemus: HakijaHakemus, suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef,
                                     personOidsWithAliases: PersonOidsWithAliases, logBypassed: Boolean = false)
                                    (implicit ec: ExecutionContext): Unit = {
    implicit val timeout: Timeout = 2.minutes
    def saveSuoritus(suor: Suoritus): Future[Suoritus with Identified[UUID]] =
      (suoritusRekisteri ? InsertResource[UUID, Suoritus](suor, personOidsWithAliases)).mapTo[Suoritus with Identified[UUID]].recoverWith {
        case t: AskTimeoutException => saveSuoritus(suor)
      }
    def fetchExistingSuoritukset(henkiloOid: String): Future[Seq[Suoritus]] = {
      val q = SuoritusQuery(henkilo = Some(henkiloOid))
      (suoritusRekisteri ? SuoritusQueryWithPersonAliases(q, personOidsWithAliases)).mapTo[Seq[Suoritus]].recoverWith {
        case t: AskTimeoutException => fetchExistingSuoritukset(henkiloOid)
      }
    }
    def suoritusExists(suor: VirallinenSuoritus, suoritukset: Seq[Suoritus]): Boolean = suoritukset.exists {
      case s: VirallinenSuoritus => s.core == suor.core
      case _ => false
    }
    hakemus.personOid.foreach(henkiloOid => {
      fetchExistingSuoritukset(henkiloOid).foreach(suoritukset => {
        createSuorituksetJaArvosanatFromHakemus(hakemus).foreach {
          case (suor: VirallinenSuoritus, arvosanat) =>
            if (!suoritusExists(suor, suoritukset)) {
              for (
                suoritus: Suoritus with Identified[UUID] <- saveSuoritus(suor)
              ) arvosanat.foreach(arvosana =>
                arvosanaRekisteri ! InsertResource[UUID, Arvosana](arvosanaForSuoritus(arvosana, suoritus), personOidsWithAliases)
              )
            } else if (logBypassed) {
              suoritusRekisteri ! LogMessage(s"suoritus already exists: $suor", Logging.DebugLevel)
            }
          case (_, _) =>
          // VapaamuotoinenSuoritus will not be saved
        }
      })
    })
  }

  def apply(suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef)(implicit ec: ExecutionContext): Trigger = {
    Trigger {
      (hakemus, personOidsWithAliases: PersonOidsWithAliases) => {
        muodostaSuorituksetJaArvosanat(hakemus, suoritusRekisteri, arvosanaRekisteri, personOidsWithAliases.intersect(hakemus.personOid.toSet))
      }
    }
  }

  def createArvosana(personOid: String, arvo: String, aine: String, lisatieto: Option[String], valinnainen: Boolean, jarjestys: Option[Int] = None): Arvosana = {
    Arvosana(suoritus = null, arvio = Arvio410(arvo), aine, lisatieto, valinnainen, myonnetty = None, source = personOid, Map(), jarjestys = jarjestys)
  }

  def aineArvotToArvosanat(personOid: String, aine: String, arvot: Map[String, String]) = {
    Seq(
      arvot.get("VAL1").map(a => Seq(createArvosana(personOid, arvot("VAL1"), aine, arvot.get("OPPIAINE"), true, Some(1)))).getOrElse(Seq.empty),
      arvot.get("VAL2").map(a => Seq(createArvosana(personOid, arvot("VAL2"), aine, arvot.get("OPPIAINE"), true, Some(2)))).getOrElse(Seq.empty),
      arvot.get("VAL3").map(a => Seq(createArvosana(personOid, arvot("VAL3"), aine, arvot.get("OPPIAINE"), true, Some(3)))).getOrElse(Seq.empty),
      arvot.get("").map(a => Seq(createArvosana(personOid, arvot(""), aine, arvot.get("OPPIAINE"), false))).getOrElse(Seq.empty)
    ).flatten
  }

  def createSuorituksetJaArvosanatFromHakemus(hakemus: HakijaHakemus): Seq[(Suoritus, Seq[Arvosana])] = hakemus match {
    case hakemus: FullHakemus =>
      (for (
        personOid <- hakemus.personOid;
        answers <- hakemus.answers;
        koulutustausta <- answers.koulutustausta
      ) yield {
        try {
          val peruskoulu = createPkSuoritusArvosanat(hakemus, personOid, answers, koulutustausta)
          val pkLisapiste = createPkLisapisteSuoritukset(hakemus, personOid, answers, koulutustausta)
          val lukio = createLukioSuoritusArvosanat(hakemus, personOid, answers, koulutustausta)
          peruskoulu ++ lukio ++ pkLisapiste
        } catch {
          case anyException: Throwable => {
            anyException.printStackTrace()
            throw new Exception("Collecting peruskouluarvosanat from hakemus " + hakemus.oid + " with personID " + personOid, anyException)
          }
        }
      }).getOrElse(Seq.empty)
    case _ => Seq.empty
  }

  import fi.vm.sade.hakurekisteri.tools.RicherString._

  def createPkSuoritusArvosanat(hakemus: FullHakemus, personOid: String, answers: HakemusAnswers, koulutustausta: Koulutustausta): Seq[(Suoritus, Seq[Arvosana])] = {
    (for (
      valmistumisvuosiStr <- koulutustausta.PK_PAATTOTODISTUSVUOSI;
      valmistumisvuosi <- valmistumisvuosiStr.blankOption
    ) yield {
        val arvosanat: Seq[Arvosana] = answers.osaaminen match {
          case Some(osaaminen) => osaaminen.getPeruskoulu.map({ case (aine, arvot) => aineArvotToArvosanat(personOid, aine, arvot) }).flatten.toSeq
          case None => Seq.empty
        }
        val currentYear = new DateTime().year().get()
        if (arvosanat.nonEmpty || currentYear != valmistumisvuosi.toInt) {
          Seq(
            (ItseilmoitettuPeruskouluTutkinto(
            hakemusOid = hakemus.oid,
            hakijaOid = personOid,
            valmistumisvuosi.toInt,
            suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI")), arvosanat))
        } else {
          Seq.empty
        }
      }).getOrElse(Seq.empty)
  }

  def createLukioSuoritusArvosanat(hakemus: FullHakemus, personOid: String, answers: HakemusAnswers, koulutustausta: Koulutustausta): Seq[(Suoritus, Seq[Arvosana])] = {
    (for (
      valmistumisvuosiStr <- koulutustausta.lukioPaattotodistusVuosi;
      valmistumisvuosi <- valmistumisvuosiStr.blankOption
    ) yield {
        val arvosanat: Seq[Arvosana] = answers.osaaminen match {
          case Some(osaaminen) => osaaminen.getLukio.map({ case (aine, arvot) => aineArvotToArvosanat(personOid, aine, arvot) }).flatten.toSeq
          case None => Seq.empty
        }
        val currentYear = new LocalDate().getYear.toString
        val lahtokoulu = koulutustausta.lahtokoulu.flatMap(_.blankOption)
        if (arvosanat.nonEmpty || (valmistumisvuosi == currentYear && lahtokoulu.isDefined) || valmistumisvuosi != currentYear) {
          val tutkinto = ItseilmoitettuLukioTutkinto(
            myontaja = lahtokoulu.getOrElse(hakemus.oid),
            hakijaOid = personOid,
            valmistumisvuosi.toInt,
            suoritusKieli = koulutustausta.lukion_kieli.getOrElse("FI")
          )
          Seq((tutkinto, arvosanat))
        } else Seq.empty
      }).getOrElse(Seq.empty)
  }

  def createPkLisapisteSuoritukset(hakemus: FullHakemus, personOid: String,
                                   answers: HakemusAnswers, koulutustausta: Koulutustausta): Seq[(Suoritus, Seq[Arvosana])] = {
    (for (
      valmistumisvuosiStr <- koulutustausta.PK_PAATTOTODISTUSVUOSI;
      valmistumisvuosi <- valmistumisvuosiStr.blankOption
    ) yield {
        val pkVuosi = valmistumisvuosi.toInt
        Seq(
          // AMMATTISTARTTI
          koulutustausta.LISAKOULUTUS_AMMATTISTARTTI.map(lk => {
            if ("true".equals(lk)) {
              Seq(ItseilmoitettuTutkinto(
                komoOid = Oids.ammattistarttiKomoOid,
                hakemusOid = hakemus.oid,
                hakijaOid = personOid,
                pkVuosi,
                suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI")))
            } else {
              Seq.empty
            }
          }).getOrElse(Seq.empty),
          // LISÄOPETUSTALOUS
          koulutustausta.LISAKOULUTUS_TALOUS.map(lk => {
            if ("true".equals(lk)) {
              Seq(ItseilmoitettuTutkinto(
                komoOid = Oids.lisaopetusTalousKomoOid,
                hakemusOid = hakemus.oid,
                hakijaOid = personOid,
                pkVuosi,
                suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI")))
            } else {
              Seq.empty
            }
          }).getOrElse(Seq.empty),
          // LISAOPETUSTUTKINTO
          koulutustausta.LISAKOULUTUS_KYMPPI.map(lk => {
            if ("true".equals(lk)) {
              Seq(ItseilmoitettuTutkinto(
                komoOid = Oids.lisaopetusKomoOid,
                hakemusOid = hakemus.oid,
                hakijaOid = personOid,
                koulutustausta.KYMPPI_PAATTOTODISTUSVUOSI.flatMap(_.blankOption).map(_.toInt).getOrElse(pkVuosi),
                suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI")))
            } else {
              Seq.empty
            }
          }).getOrElse(Seq.empty)
        ).flatMap(s => s)
      }).getOrElse(Seq.empty).map(s => (s, Seq.empty))
  }
}


case class RicherOsaaminen(osaaminen: Map[String, String]) {
  val groupByKomoAndGroupByAine = osaaminen.filterKeys(_.contains("_")).filterKeys(!_.last.equals('_'))
    .groupBy({case (key,value) => key.split("_").head})
    .map({case (key,value) => (key, value.map({case (k,v) => (k.split(key + "_")(1),v) }) )})
    .map({
      case (key,value) => (key, value.groupBy({ case (k, v) => k.split("_").head })
        .mapValues(vals => vals.map({
          case (kxx, vxx) => (stringAfterFirstUnderscore(kxx), vxx)
        }).filter(v => {
          if (v._1.equals("")) {
            !v._2.isEmpty() && (isAllDigits(v._2) || "S".equals(v._2))
          } else if (v._1.equals("VAL1")) {
            !v._2.isEmpty() && (isAllDigits(v._2) || "S".equals(v._2))
          } else if (v._1.equals("VAL2")) {
            !v._2.isEmpty() && (isAllDigits(v._2) || "S".equals(v._2))
          } else if (v._1.equals("VAL3")) {
            !v._2.isEmpty() && (isAllDigits(v._2) || "S".equals(v._2))
          } else {
            true
          }
        }))
        .filter(v => {
          v._2.contains("") || v._2.contains("VAL1") || v._2.contains("VAL2") || v._2.contains("VAL3")
        }))
  })
  .filter(w => !w._2.isEmpty);

  private def stringAfterFirstUnderscore(source: String): String = if (!source.contains("_")) "" else source.substring(source.indexOf("_") + 1)
  private def isAllDigits(x: Option[String]) = if (x.isEmpty) true else x.get forall Character.isDigit
  private def isAllDigits(x: String) = x forall Character.isDigit

  def getLukio: Map[String,Map[String, String]] = {
    groupByKomoAndGroupByAine.get("LK").getOrElse(Map.empty)
  }
  def getPeruskoulu: Map[String,Map[String, String]] = {
    groupByKomoAndGroupByAine.get("PK").getOrElse(Map.empty)
  }

}

case class RicherKoulutustausta(koulutustausta: Map[String, String]) {

  def yotutkintoVuosi: Option[Int] = {
    koulutustausta.get("pohjakoulutus_yo_vuosi").map(_.toInt)
  }
  def perusopetusVuosi: Option[Int] = {
    koulutustausta.get("PK_PAATTOTODISTUSVUOSI").map(_.toInt)
  }
  def lisaopetusVuosi: Option[Int] = {
    None
  }
  def lisaopetusTalousVuosi: Option[Int] = {
    None
  }
  def ammattistarttiVuosi: Option[Int] = {
    None
  }
  def valmentavaVuosi: Option[Int] = {

    None
  }
  def ammatilliseenvalmistavaVuosi: Option[Int] = {

    None
  }
  def ulkomainenkorvaavaVuosi: Option[Int] = {
    None
  }
  def lukioVuosi: Option[Int] = {

    koulutustausta.get("lukioPaattotodistusVuosi").map(_.toInt)
  }
  def ammatillinenVuosi: Option[Int] = {
    koulutustausta.get("pohjakoulutus_am_vuosi").map(_.toInt)
  }
  def lukioonvalmistavaVuosi: Option[Int] = {

    None
  }
}
