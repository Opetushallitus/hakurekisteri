package fi.vm.sade.hakurekisteri.domain

import java.util.Date


case class Suoritus(opilaitosOid: String, tila: String, luokkataso: String, arvioituValmistuminen: Date, luokka: String, henkiloOid: String)

