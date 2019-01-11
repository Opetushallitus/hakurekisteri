package fi.vm.sade.hakurekisteri.hakija

case class Henkilo(hetu: String,
                   syntymaaika: String,
                   oppijanumero: String,
                   sukupuoli: String,
                   sukunimi: String,
                   etunimet: String,
                   kutsumanimi: String,
                   turvakielto: String,
                   lahiosoite: String,
                   postinumero: String,
                   postitoimipaikka: String,
                   maa: String,
                   matkapuhelin: String,
                   puhelin: String,
                   sahkoposti: String,
                   kotikunta: String,
                   kansalaisuus: Option[String],
                   kaksoiskansalaisuus: Option[String],
                   kansalaisuudet: Option[List[String]],
                   asiointiKieli: String,
                   opetuskieli: String,
                   eiSuomalaistaHetua: Boolean,
                   markkinointilupa: Option[Boolean],
                   kiinnostunutoppisopimuksesta: Option[Boolean],
                   huoltajannimi: String,
                   huoltajanpuhelinnumero: String,
                   huoltajansahkoposti: String,
                   muukoulutus: Option[String],
                   lisakysymykset: Seq[Lisakysymys],
                   liitteet: Seq[Liite])

case class Lisakysymys(kysymysid: String, hakukohdeOids: Seq[String], kysymystyyppi: String, kysymysteksti: String, vastaukset: Seq[LisakysymysVastaus])
case class LisakysymysVastaus(vastausid: Option[String], vastausteksti: String)
case class Liite(koulutusId: String, koulutusRyhmaId: String, tila: String, saapumisenTila: String, nimi: String, vastaanottaja: String)
