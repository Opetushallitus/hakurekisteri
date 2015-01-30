package fi.vm.sade.hakurekisteri.rest.support

object Kausi extends Enumeration {
  type Kausi = Value
  val Kevät = Value("K")
  val Syksy = Value("S")

  def fromKoodiUri(koodiUri: String): Kausi = koodiUri.toLowerCase match {
    case s if s == "kausi_s" || s.startsWith("kausi_s#") => Syksy
    case k if k == "kausi_k" || k.startsWith("kausi_k#") => Kevät
    case _ => throw new IllegalArgumentException(s"unrecognised kausi uri $koodiUri")
  }
}
