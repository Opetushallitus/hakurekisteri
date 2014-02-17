package fi.vm.sade.hakurekisteri.henkilo

import org.scalatra.commands._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriCommand
import org.json4s._
import org.scalatra.util.conversion.TypeConverter
import org.scalatra.DefaultValue


class CreateHenkiloCommand extends HakurekisteriCommand[Henkilo] {

  implicit def getValue[A](option: Field[A]) : A = option.value.get

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
  val yksilointitieto: Field[Yksilointitieto] =asType[Yksilointitieto]("yksilointitieto").required
  val henkiloTyyppi: Field[String]  = asString("henkiloTyyppi")
  val oidHenkilo: Field[String]  = asString("oidHenkilo")
  val duplicate: Field[Boolean] = asBoolean("duplicate")
  val oppijanumero: Field[String] = asString("oppijanumero")
  val kayttajatiedot: Field[Kayttajatiedot] = asType[Kayttajatiedot]("kayttajatiedot")
  val kansalaisuus: Field[Seq[Kansalaisuus]] =asSeq[Kansalaisuus]("kansalaisuus")
  val passinnumero: Field[String] =asString("passinnumero")
  val asiointiKieli: Field[Kieli] =asType[Kieli]("asiointiKieli")
  val kutsumanimi: Field[String] =asString("kutsunmanimi")
  val passivoitu: Field[Boolean] =asBoolean("passivoitu")
  val eiSuomalaistaHetua: Field[Boolean] =asBoolean("eiSuomalaistaHetua")
  val etunimet: Field[String] =asString("etunimet")
  val sukupuoli: Field[String] = asString("sukupuoli")
  val turvakielto: Field[Boolean] =asBoolean("turvakielto")
  val hetu: Field[String] =asString("hetu")
  val syntymaaika: Field[String] =asString("syntymaaika")




  override def toResource: Henkilo = Henkilo(yhteysTiedotRyhma, yksiloity, sukunimi, kielisyys, yksilointitieto,
    henkiloTyyppi: String,
    oidHenkilo: String,
    duplicate: Boolean,
    oppijanumero: String,
    kayttajatiedot: Kayttajatiedot,
    kansalaisuus: Seq[Kansalaisuus],
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







}