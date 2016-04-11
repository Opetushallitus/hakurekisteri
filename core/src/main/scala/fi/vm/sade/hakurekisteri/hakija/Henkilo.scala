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
                   postitoimipaikka: String,
                   maa: String,
                   matkapuhelin: String,
                   puhelin: String,
                   sahkoposti: String,
                   kotikunta: String,
                   kansalaisuus: String,
                   asiointiKieli: String,
                   eiSuomalaistaHetua: Boolean,
                   markkinointilupa: Option[Boolean],
                   kiinnostunutoppisopimuksesta: Option[Boolean],
                   huoltajannimi: String,
                   huoltajanpuhelinnumero: String,
                   huoltajansahkoposti: String,
                   lisakysymykset: Seq[Lisakysymys])

case class Lisakysymys(kysymysid: String, kysymystyyppi: String, kysymysteksti: String, vastaukset: Seq[LisakysymysVastaus])
case class LisakysymysVastaus(vastausid: String, vastausteksti: String)
