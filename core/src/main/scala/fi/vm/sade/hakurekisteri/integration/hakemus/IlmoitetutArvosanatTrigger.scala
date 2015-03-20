package fi.vm.sade.hakurekisteri.integration.hakemus

import java.util.UUID


import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus._
import akka.actor.ActorRef
import akka.pattern.AskTimeoutException
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, Arvosana}
import org.joda.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri._

/**
 * @author Jussi Jartamo
 */
object IlmoitetutArvosanatTrigger {

  import scala.language.implicitConversions

  implicit def osaaminen2RicherOsaaminen(osaaminen:Map[String,String]):RicherOsaaminen = RicherOsaaminen(osaaminen)
  implicit def koulutustausta2RicherKoulutustausta(koulutustausta:Map[String,String]):RicherKoulutustausta = RicherKoulutustausta(koulutustausta)

  def arvosanaToSuoritus(arvosana: Arvosana, s: Suoritus with Identified[UUID]): Arvosana = {
    Arvosana(suoritus = s.id, arvio = arvosana.arvio, aine = arvosana.aine, lisatieto = arvosana.lisatieto, valinnainen = arvosana.valinnainen, myonnetty = arvosana.myonnetty, source = arvosana.source)
  }

  def muodostaSuorituksetJaArvosanat(hakemus: FullHakemus, suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef)(implicit ec: ExecutionContext): Unit = {
    import akka.pattern.ask
    implicit val timeout: Timeout = 5.second

    def saveSuoritus(suor: Suoritus): Future[Suoritus with Identified[UUID]] =
      (suoritusRekisteri ? suor).mapTo[Suoritus with Identified[UUID]].recoverWith {

        case t: AskTimeoutException => saveSuoritus(suor)
      }

    createSuorituksetKoulutustausta(hakemus).foreach(saveSuoritus)

    createSuorituksetJaArvosanatFromOppimiset(hakemus).foreach(suoritusJaArvosanat => {
      for (
        suoritus <- saveSuoritus(suoritusJaArvosanat._1)
      ) {
        suoritusJaArvosanat._2.foreach(
          arvosana => {
            arvosanaRekisteri ! arvosanaToSuoritus(arvosana, suoritus)
          }
        )
      }
    })
  }

  def apply(suoritusRekisteri: ActorRef, arvosanaRekisteri: ActorRef)(implicit ec: ExecutionContext): Trigger = {
    Trigger {
      hakemus => {
        muodostaSuorituksetJaArvosanat(hakemus, suoritusRekisteri, arvosanaRekisteri)
      }
    }
  }

  def createArvosana(personOid: String, arvo: String, aine: String, lisatieto: Option[String], valinnainen: Boolean): Arvosana = {
    Arvosana(suoritus = null, arvio = Arvio410(arvo), aine, lisatieto, valinnainen, myonnetty = None, source = personOid)
  }

  def aineArvotToArvosanat(personOid: String, aine: String, arvot: Map[String, String]) = {
    Seq(
      arvot.get("VAL1").map(a => Seq(createArvosana(personOid, arvot("VAL1"), aine, arvot.get("OPPIAINE"), true))).getOrElse(Seq.empty),
      arvot.get("VAL2").map(a => Seq(createArvosana(personOid, arvot("VAL2"), aine, arvot.get("OPPIAINE"), true))).getOrElse(Seq.empty),
      arvot.get("VAL3").map(a => Seq(createArvosana(personOid, arvot("VAL3"), aine, arvot.get("OPPIAINE"), true))).getOrElse(Seq.empty),
    Seq(
      createArvosana(personOid, arvot(""), aine, arvot.get("OPPIAINE"), false)
      //Arvosana(suoritus = null, arvio = Arvio410(arvot("")), aine, lisatieto = arvot.get("OPPIAINE"), valinnainen = false, myonnetty = None, source = personOid))
    )).flatten
  }

  def createSuorituksetJaArvosanatFromOppimiset(hakemus: FullHakemus): Seq[(Suoritus, Seq[Arvosana])] = {
    // Arvosanojen merkkaaminen
    (for(
      personOid <- hakemus.personOid;
      answers <- hakemus.answers;
      osaaminen <- answers.osaaminen;
      koulutustausta <- answers.koulutustausta
    ) yield {
      // PK Arvosanat
      val peruskoulunArvosanat: Seq[(Suoritus, Seq[Arvosana])] = (for (
        valmistumisvuosi <- koulutustausta.PK_PAATTOTODISTUSVUOSI
      ) yield {
        val itseIlmoitettuSuoritus: Suoritus =
          ItseilmoitettuPeruskouluTutkinto(
            hakijaOid = personOid,
            valmistumisvuosi.toInt,
            suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI"))
        Seq((itseIlmoitettuSuoritus, osaaminen.getPeruskoulu.map({case (aine,arvot) => aineArvotToArvosanat(personOid, aine, arvot)}).flatten.toSeq))
      }).getOrElse(Seq.empty)

      // Lukio Arvosanat
      val lukionArvosanat: Seq[(Suoritus, Seq[Arvosana])] = (for (valmistumisvuosi <- koulutustausta.lukioPaattotodistusVuosi) yield {
        val itseIlmoitettuSuoritus: Suoritus =
          ItseilmoitettuLukioTutkinto(
            hakijaOid = personOid,
            valmistumisvuosi.toInt,
            suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI"))
        Seq((itseIlmoitettuSuoritus, osaaminen.getLukio.map({case (aine,arvot) => aineArvotToArvosanat(personOid, aine, arvot)}).flatten.toSeq))
      }).getOrElse(Seq.empty)

      (peruskoulunArvosanat ++ lukionArvosanat)
    }).getOrElse(Seq.empty)
  }

  def createSuorituksetKoulutustausta(hakemus: FullHakemus): Seq[VirallinenSuoritus] = {
    (for(
      personOid <- hakemus.personOid;
      answers <- hakemus.answers;
      koulutustausta <- answers.koulutustausta
    ) yield koulutustausta.lukioPaattotodistusVuosi.map(_.toInt).map(vuosi => {
        // Lukion suoritus
        Seq(ItseilmoitettuLukioTutkinto(
          hakijaOid = personOid,
          vuosi,
          suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI")))
      }).getOrElse(Seq.empty) ++
        // Peruskoulun suoritus
        koulutustausta.PK_PAATTOTODISTUSVUOSI.map(_.toInt).map(vuosi => {

          Seq(
            // AMMATTISTARTTI
            koulutustausta.LISAKOULUTUS_AMMATTISTARTTI.map(lk => {
              if("true".equals(lk)) {
                Seq(ItseilmoitettuTutkinto(
                  komoOid = Config.ammattistarttiKomoOid,
                  hakijaOid = personOid,
                  vuosi,
                  suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI")))
              } else {
                Seq.empty
              }
            }).getOrElse(Seq.empty),
            // LISÄOPETUSTALOUS
            koulutustausta.LISAKOULUTUS_TALOUS.map(lk => {
              if("true".equals(lk)) {
                Seq(ItseilmoitettuTutkinto(
                  komoOid = Config.lisaopetusTalousKomoOid,
                  hakijaOid = personOid,
                  vuosi,
                  suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI")))
              } else {
                Seq.empty
              }
            }).getOrElse(Seq.empty),
          // LISAOPETUSTUTKINTO
            koulutustausta.LISAKOULUTUS_KYMPPI.map(lk => {
              if("true".equals(lk)) {
                Seq(ItseilmoitettuTutkinto(
                  komoOid = Config.lisaopetusKomoOid,
                  hakijaOid = personOid,
                  vuosi,
                  suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI")))
              } else {
                Seq.empty
              }
            }).getOrElse(Seq.empty),
          // PERUSKOULUTUTKINTO AINA KUN PK_PAATTOTODISTUSVUOSI LOYTYY
            Seq(ItseilmoitettuPeruskouluTutkinto(
            hakijaOid = personOid,
            vuosi,
            suoritusKieli = koulutustausta.perusopetuksen_kieli.getOrElse("FI"))))
            .flatMap(s => s)
        }).getOrElse(Seq.empty)).getOrElse(Seq.empty)

  }
}


case class RicherOsaaminen(osaaminen: Map[String, String]) {
  val groupByKomoAndGroupByAine = osaaminen.filterKeys(_.contains("_")).filterKeys(!_.last.equals('_'))
    //
    .groupBy({case (key,value) => key.split("_").head})
    // Map(LK -> Map(AI -> 8, AI_OPPIAINE -> FI))
    .map({case (key,value) => (key, value.map({case (k,v) => (k.split(key + "_")(1),v) }) )})
    // Map(LK -> Map(AI -> Map( -> 8, OPPIAINE -> FI)))
    .map({
      case (key,value) => (key, ((value.groupBy({case (k,v) => k.split("_").head}))
        .mapValues(vals => vals.map({
        case (kxx,vxx) => (stringAfterFirstUnderscore(kxx),vxx)
      }).filter(v => {
        if(v._1.equals("")) {
          isAllDigits(v._2)
        } else if(v._1.equals("VAL1")) {
          isAllDigits(v._2)
        } else if(v._1.equals("VAL2")) {
          isAllDigits(v._2)
        } else if(v._1.equals("VAL3")) {
          isAllDigits(v._2)
        } else {
          true
        }
        //== "" &&  && isAllDigits(v._2.get("VAL1")) && isAllDigits(v._2.get("VAL2")) && isAllDigits(v._2.get("VAL3"))
      }))
        .filter(v => v._2.contains(""))
        ))
  })
  // Filtterointi
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
    // pohjakoulutus_yo_vuosi
    // pohjakoulutus_yo_kansainvalinen_suomessa_vuosi
    // pohjakoulutus_yo_ulkomainen_vuosi
    // pohjakoulutus_yo_ammatillinen_vuosi

    koulutustausta.get("pohjakoulutus_yo_vuosi").map(_.toInt)
  }
  def perusopetusVuosi: Option[Int] = {
    // "PK_PAATTOTODISTUSVUOSI" : "2011",
    // "POHJAKOULUTUS" : "1",
    // "perusopetuksen_kieli" : "XX"

    koulutustausta.get("PK_PAATTOTODISTUSVUOSI").map(_.toInt)
  }
  def lisaopetusVuosi: Option[Int] = {
    // "LISAKOULUTUS_KYMPPI" : "true",
    None
  }
  def lisaopetusTalousVuosi: Option[Int] = {
    // "LISAKOULUTUS_TALOUS" : "true",
    None
  }
  def ammattistarttiVuosi: Option[Int] = {
    // "LISAKOULUTUS_AMMATTISTARTTI" : "true",

    None
  }
  def valmentavaVuosi: Option[Int] = {

    None
  }
  def ammatilliseenvalmistavaVuosi: Option[Int] = {

    None
  }
  def ulkomainenkorvaavaVuosi: Option[Int] = {
    // pohjakoulutus_ulk_vuosi
    None
  }
  def lukioVuosi: Option[Int] = {
    // lukioPaattotodistusVuosi

    koulutustausta.get("lukioPaattotodistusVuosi").map(_.toInt)
  }
  def ammatillinenVuosi: Option[Int] = {
    // pohjakoulutus_am_vuosi
    // "ammatillinenTutkintoSuoritettu" : "false",
    // "KOULUTUSPAIKKA_AMMATILLISEEN_TUTKINTOON" : "false", ???
    koulutustausta.get("pohjakoulutus_am_vuosi").map(_.toInt)
  }
  def lukioonvalmistavaVuosi: Option[Int] = {

    None
  }

  // "LISAKOULUTUS_TALOUS" : "true",
  // "LISAKOULUTUS_AMMATTISTARTTI" : "true",
  // "PK_PAATTOTODISTUSVUOSI" : "2011",
  // "ammatillinenTutkintoSuoritettu" : "false",
  // "KOULUTUSPAIKKA_AMMATILLISEEN_TUTKINTOON" : "false",
  // "LISAKOULUTUS_KYMPPI" : "true",
  // "POHJAKOULUTUS" : "1",
  // "perusopetuksen_kieli" : "XX"


  // GITHUB KoulutustaustaPhase.java
  // amk_ope_tutkinto_vuosi
  // pohjakoulutus_muu_vuosi
  // pohjakoulutus_ulk_vuosi
  // pohjakoulutus_amt_vuosi
  // pohjakoulutus_am_vuosi
  // pohjakoulutus_yo_vuosi
  // pohjakoulutus_yo_kansainvalinen_suomessa_vuosi
  // pohjakoulutus_yo_ulkomainen_vuosi
  // pohjakoulutus_yo_ammatillinen_vuosi

  // JOKERIT
  // PK_PAATTOTODISTUSVUOSI
  // lukioPaattotodistusVuosi
  // pohjakoulutus_kk_pvm
  // aiempitutkinto_vuosi

  // Lisäarvot
  // LISAKOULUTUS_VAMMAISTEN
  // LISAKOULUTUS_TALOUS
  // LISAKOULUTUS_KYMPPI = true
  // LISAKOULUTUS_AMMATTISTARTTI
  // LISAKOULUTUS_KANSANOPISTO
  // LISAKOULUTUS_MAAHANMUUTTO
  // LISAKOULUTUS_MAAHANMUUTTO_LUKIO
}