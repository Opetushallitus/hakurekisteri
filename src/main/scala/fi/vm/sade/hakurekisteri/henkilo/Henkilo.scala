package fi.vm.sade.hakurekisteri.henkilo


case class Henkilo (
                     yhteystiedotRyhma: List[YhteystiedotRyhma],
                     yksiloity: Boolean,
                     sukunimi: String,
                     kielisyys: List[Kieli],
                     yksilointitieto: Yksilointitieto,
                     henkiloTyyppi: String,
                     oidHenkilo: String,
                     duplicate: Boolean,
                     oppijanumero: String,
                     kayttajatiedot: Kayttajatiedot,
                     kansalaisuus: List[Kansalaisuus],
                     passinnumero: String,
                     asiointiKieli: Kieli,
                     kutsumanimi: String,
                     passivoitu: Boolean,
                     eiSuomalaistaHetua: Boolean,
                     etunimet: String,
                     id: Int,
                     sukupuoli: String,
                     turvakielto: Boolean,
                     hetu: String,
                     syntymaaika: String)


case class Yhteystiedot (id: Int,
                         yhteystietoTyyppi: String,
                         yhteystietoArvo: String)



case class Kansalaisuus (
                          kansalaisuusKoodi: String)

case class Kayttajatiedot (
                            username: String)

case class Kieli (
                   kieliKoodi: String,
                   kieliTyyppi: String)

case class YhteystiedotRyhma (
                               id: Int,
                               ryhmaKuvaus: String,
                               ryhmaAlkuperaTieto: String,
                               readOnly: Boolean,
                               yhteystiedot: List[Yhteystiedot])


case class Yksilointitieto (
                             hetu: String)
