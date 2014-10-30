package fi.vm.sade.hakurekisteri.hakija

case class Henkilo(hetu: String,
                   syntymaaika: String,
                   oppijanumero: String,
                   sukupuoli: String,
                   sukunimi: String,
                   etunimet: String,
                   kutsumanimi: String,
                   lahiosoite: String,
                   postinumero: String,
                   maa: String,
                   matkapuhelin: String,
                   puhelin: String,
                   sahkoposti: String,
                   kotikunta: String,
                   kansalaisuus: String,
                   asiointiKieli: String,
                   eiSuomalaistaHetua: Boolean,
                   markkinointilupa: Option[Boolean])

