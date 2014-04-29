package fi.vm.sade.hakurekisteri.henkilo

import org.scalatra.commands._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriCommand
import org.json4s._
import org.scalatra.util.conversion.TypeConverter
import org.scalatra.DefaultValue
import scala.util.Try


class CreateHenkiloCommand extends HakurekisteriCommand[Henkilo] {



  implicit val yhteistiedotDefault: DefaultValue[YhteystiedotRyhma]  = default(YhteystiedotRyhma(-1,"dummy default value", "scalatra", readOnly = true, Seq()))
  implicit val stringToYhteistiedot: TypeConverter[String, YhteystiedotRyhma] = (_: String) =>  None
  implicit val jsonToYhteistiedot: TypeConverter[JValue, YhteystiedotRyhma] = safeOption(_.extractOpt[YhteystiedotRyhma])

  implicit val kieliDefault: DefaultValue[Kieli] = default(Kieli("fi","suomi"))
  implicit val stringToKieli: TypeConverter[String,Kieli] = (_: String ) => None
  implicit val jsonToKieli: TypeConverter[JValue,Kieli] = safeOption(_.extractOpt[Kieli])

  implicit val kansalaisuusDefault: DefaultValue[Kansalaisuus] = default(Kansalaisuus("246"))
  implicit val stringToKansalaisuus: TypeConverter[String,Kansalaisuus] = (_: String ) => None
  implicit val jsonToKansalaisuus: TypeConverter[JValue,Kansalaisuus] = safeOption(_.extractOpt[Kansalaisuus])

  implicit val YksilointitietoDefault: DefaultValue[Yksilointitieto] = default(Yksilointitieto("dummy"))
  implicit val stringToYksilointi:  TypeConverter[String,Yksilointitieto] = (_: String ) => None
  implicit val jsonToYksilointi: TypeConverter[JValue,Yksilointitieto] = safeOption(_.extractOpt[Yksilointitieto])

  implicit val kayttajatiedotDefault: DefaultValue[Kayttajatiedot] = default(Kayttajatiedot("No-user"))
  implicit val stringToKayttajatiedot:  TypeConverter[String,Kayttajatiedot] = (_: String ) => None
  implicit val jsonToKayttajatiedot: TypeConverter[JValue,Kayttajatiedot] = safeOption(_.extractOpt[Kayttajatiedot])


  val yhteysTiedotRyhma: Field[Seq[YhteystiedotRyhma]] = validatableSeqBinding(asSeq[YhteystiedotRyhma]("yhteystiedotRyhma")).notEmpty
  val yksiloity:Field[Boolean] = asBoolean("yksiloity").required
  val sukunimi:Field[String] = asString("sukunimi").required
  val kielisyys: Field[Seq[Kieli]] = validatableSeqBinding(asSeq[Kieli]("kielisyys")).notEmpty
  val yksilointitieto: Field[Yksilointitieto] =asType[Yksilointitieto]("yksilointitieto").optional
  val henkiloTyyppi: Field[String]  = asString("henkiloTyyppi").required
  val oidHenkilo: Field[String]  = asString("oidHenkilo").required
  val duplicate: Field[Boolean] = asBoolean("duplicate")
  val oppijanumero: Field[String] = asString("oppijanumero")
  val kayttajatiedot: Field[Kayttajatiedot] = asType[Kayttajatiedot]("kayttajatiedot").optional
  val kansalaisuus: Field[Seq[Kansalaisuus]] =asSeq[Kansalaisuus]("kansalaisuus")
  val passinnumero: Field[String] =asString("passinnumero")
  val asiointiKieli: Field[Kieli] =asType[Kieli]("asiointiKieli")
  val kutsumanimi: Field[String] =asString("kutsumanimi")
  val passivoitu: Field[Boolean] =asBoolean("passivoitu")
  val eiSuomalaistaHetua: Field[Boolean] =asBoolean("eiSuomalaistaHetua")
  val etunimet: Field[String] =asString("etunimet").required
  val sukupuoli: Field[String] = asString("sukupuoli")
  val turvakielto: Field[Boolean] =asBoolean("turvakielto")
  val hetu: Field[String] =asString("hetu")
  val syntymaaika: Field[String] =asString("syntymaaika")
  val markkinointiLupa: Field[Boolean] =asBoolean("markkinointiLupa").optional

  override def toResource: Henkilo = Henkilo(
    yhteysTiedotRyhma.value.get,
    yksiloity.value.get,
    sukunimi.value.get,
    kielisyys.value.get,
    yksilointitieto.value,
    henkiloTyyppi.value.get,
    oidHenkilo.value.get,
    duplicate.value.get,
    oppijanumero.value.get,
    kayttajatiedot.value,
    kansalaisuus.value.get,
    passinnumero.value.get,
    asiointiKieli.value.get,
    kutsumanimi.value.get,
    passivoitu.value.get,
    eiSuomalaistaHetua.value.get,
    etunimet.value.get,
    sukupuoli.value.get,
    turvakielto.value.get,
    hetu.value.get,
    syntymaaika.value.get,
    markkinointiLupa.value.flatMap((m: Boolean) => Try(JBool(m).extract[Boolean]).toOption)
  )

}