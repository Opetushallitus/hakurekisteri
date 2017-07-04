package fi.vm.sade.hakurekisteri.web.suoritus

import java.util.Locale

import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriCommand, LocalDateSupport}
import org.joda.time.LocalDate
import org.scalatra.commands._


class CreateSuoritusCommand extends HakurekisteriCommand[Suoritus] with LocalDateSupport {

  val komo: Field[String] = asType[String]("komo").notBlank
  val myontaja: Field[String] = asType[String]("myontaja").notBlank
  val tila: Field[String] = asType[String]("tila").notBlank
  val valmistuminen: Field[LocalDate] = asType[LocalDate]("valmistuminen").required
  val henkiloOid: Field[String]  = asType[String]("henkiloOid").notBlank
  val yks: Field[Yksilollistetty]  = asType[Yksilollistetty]("yksilollistaminen").optional(yksilollistaminen.Ei)
  val languages = Seq(Locale.getISOLanguages:_*) ++ Seq(Locale.getISOLanguages:_*).map(_.toUpperCase)
  val suoritusKieli: Field[String] = asType[String]("suoritusKieli").required.allowableValues(languages:_*)
  val vahvistettu: Field[Boolean] = asType[Boolean]("vahvistettu").optional(false)

  override def toResource(user: String): Suoritus = VirallinenSuoritus(
    komo.value.get,
    myontaja.value.get,
    tila.value.get,
    valmistuminen.value.get,
    henkiloOid.value.get,
    yks.value.get, suoritusKieli.value.get, vahv = vahvistettu.value.get, lahde = user, lahdeArvot = Map())}


