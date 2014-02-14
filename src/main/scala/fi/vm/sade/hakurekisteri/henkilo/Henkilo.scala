package fi.vm.sade.hakurekisteri.henkilo

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID


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


object Henkilo extends {
  def identify(o:Henkilo): Henkilo with Identified = o match {
    case o: Henkilo with Identified => o
    case _ => new Henkilo(o.yhteystiedotRyhma,
  o.yksiloity: Boolean,
  o.sukunimi: String,
  o.kielisyys: List[Kieli],
  o.yksilointitieto: Yksilointitieto,
  o.henkiloTyyppi: String,
  o.oidHenkilo: String,
  o.duplicate: Boolean,
  o.oppijanumero: String,
  o.kayttajatiedot: Kayttajatiedot,
  o.kansalaisuus: List[Kansalaisuus],
  o.passinnumero: String,
  o.asiointiKieli: Kieli,
  o.kutsumanimi: String,
  o.passivoitu: Boolean,
  o.eiSuomalaistaHetua: Boolean,
  o.etunimet: String,
  o.sukupuoli: String,
  o.turvakielto: Boolean,
  o.hetu: String,
  o.syntymaaika: String) with Identified{
      override val id: UUID = UUID.randomUUID()
    }
  }
}