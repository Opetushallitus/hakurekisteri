package fi.vm.sade.hakurekisteri.integration.koski

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
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object KoskiArvosanaTrigger {

  import scala.language.implicitConversions
  
  def muodostaSuorituksetJaArvosanat(henkilo: KoskiHenkiloContainer, suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef,
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

    henkilo.henkilö.oid.foreach(henkiloOid => {
      fetchExistingSuoritukset(henkiloOid).foreach(suoritukset => {
        createSuorituksetJaArvosanatFromKoski(henkilo).foreach {

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

  def apply(suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef)(implicit ec: ExecutionContext): KoskiTrigger = {
    KoskiTrigger {
      (koskiHenkilo: KoskiHenkiloContainer, personOidsWithAliases: PersonOidsWithAliases) => {
        muodostaSuorituksetJaArvosanat(koskiHenkilo, suoritusRekisteri, arvosanaRekisteri, personOidsWithAliases.intersect(koskiHenkilo.henkilö.oid.toSet))
      }
    }
  }

  def createArvosana(personOid: String, arvo: String, aine: String, lisatieto: Option[String], valinnainen: Boolean, jarjestys: Option[Int] = None): Arvosana = {
    Arvosana(suoritus = null, arvio = Arvio410(arvo), aine, lisatieto, valinnainen, myonnetty = None, source = personOid, Map(), jarjestys = jarjestys)
  }

  def createSuorituksetJaArvosanatFromKoski(henkilo: KoskiHenkiloContainer): Seq[(Suoritus, Seq[Arvosana])] = {
    val retVal = Seq.empty
    getSuoritusArvosanatFromOpiskeluoikeus(henkilo.henkilö.oid.getOrElse(""), henkilo.opiskeluoikeudet)
  }

  import fi.vm.sade.hakurekisteri.tools.RicherString._
  def parseLocalDate(s: String): LocalDate =
    if (s.length() > 10) {
      DateTimeFormat.forPattern("yyyy-MM-ddZ").parseLocalDate(s)
    } else {
      DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate(s)
    }

  def getSuoritusArvosanatFromOpiskeluoikeus(personOid: String, opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]): Seq[(Suoritus, Seq[Arvosana])] = {
    val suoritukset = Seq.empty;
    (for (
      opiskeluoikeus <- opiskeluoikeudet
    ) yield {
      suoritukset ++ createSuoritusArvosanat(personOid, opiskeluoikeus.suoritukset);
    })
    suoritukset
  }

  def parseYear(dateStr: String): Int = {
    val dateFormat = "yyyy-MM-dd"
    val dtf = java.time.format.DateTimeFormatter.ofPattern(dateFormat)
    val d = java.time.LocalDate.parse(dateStr, dtf)
    d.getYear
  }

  def matchOpetusOid(koulutusmoduuliTunnisteKoodiarvo: String): String = {
    koulutusmoduuliTunnisteKoodiarvo match {
      case "201101" => Oids.perusopetusKomoOid
      case "039993" | "039994" | "999901" | "999902" => Oids.valmaKomoOid
      case "039999" | "999903" => Oids.telmaKomoOid
      case "039997" | "999906" => Oids.lukioonvalmistavaKomoOid
      case "020075" => Oids.lisaopetusKomoOid
      case _ => "999999"
    }
  }

  def arvosanaForSuoritus(arvosana: Arvosana, s: Suoritus with Identified[UUID]): Arvosana = {
    arvosana.copy(suoritus = s.id)
  }

  def osasuoritusToArvosana(personOid: String, suoritusAika: String, orgOid: String, suoritukset: Seq[KoskiSuoritus]): Seq[Arvosana] = {
    val result = Seq()
    for(
      suoritus <- suoritukset
    ){
      val arviointi = suoritus.arviointi
      val arvosana = arviointi match {
        case None => ""
        case Some(arviointi: KoskiArviointi) => arviointi.arvosana.koodiarvo
      }
      result ++ Seq(createArvosana(personOid, arvosana, suoritus.koulutusmoduuli.tunniste.koodiarvo, None, !suoritus.pakollinen.getOrElse(false), None))
    }
    result
  }

  def createSuoritusArvosanat(personOid: String, suoritukset: Seq[KoskiSuoritus]): Seq[(Suoritus, Seq[Arvosana])] = {
    val result = Seq.empty
    for ( suoritus <- suoritukset ) {
        val vahvistus = suoritus.vahvistus.getOrElse(KoskiVahvistus("1970-01-01", KoskiOrganisaatio("")))
        val suorituskieli = suoritus.suorituskieli
        val valmistuminen = vahvistus.päivä
        val valmistumisvuosiStr = parseYear(valmistuminen)
        val currentYear = new DateTime().year().get()
        // ei tämän vuoden suorituksia
        val oid = matchOpetusOid(suoritus.koulutusmoduuli.tunniste.koodiarvo);
        val arvosanat: Seq[Arvosana] = osasuoritusToArvosana(personOid, valmistuminen, oid, suoritus.osasuoritukset.getOrElse(Seq()))

        if ((arvosanat.nonEmpty || currentYear != valmistumisvuosiStr.toInt) && oid != "999999") {
          val suor = VirallinenSuoritus(
              oid,
              vahvistus.myöntäjäOrganisaatio.oid,
              "VALMIS",
              parseLocalDate(valmistuminen),
              personOid,
              suoritus.yksilöllistettyOppimäärä match {
                case Some(true) => yksilollistaminen.Alueittain
                case _ => yksilollistaminen.Ei
              },
              "", //suorituskieli.koodiarvo,
              None,
              true,
              "Koski");
          LogMessage(suor.toString(), Logging.ErrorLevel)
          result ++ Seq(suor, arvosanat)
        }
      }
      result
  }
}