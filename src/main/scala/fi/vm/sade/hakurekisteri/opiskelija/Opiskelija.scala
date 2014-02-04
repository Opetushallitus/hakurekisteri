package fi.vm.sade.hakurekisteri.opiskelija

import java.util.Date

case class Opiskelija(oppilaitosOid: String, luokkataso: String, luokka: String, henkiloOid: String, alkuPaiva: Date = new Date, loppuPaiva: Option[Date] = None)
