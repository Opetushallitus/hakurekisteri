package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.hakija._
import org.json4s.CustomSerializer
import org.json4s.JsonAST._
import org.json4s.JsonDSL._

class LasnaoloSerializer extends CustomSerializer[Lasnaolo] (format => (
  {
    case json: JObject =>
      val JString(kausiString) = json \ "kausi"
      val kausiParser = "(\\d\\d\\d\\d)([KS])".r
      val kausi = kausiString match {
        case kausiParser(vuosi, "K") => Kevat(vuosi.toInt)
        case kausiParser(vuosi, "S") => Syksy(vuosi.toInt)
        case _ => throw new IllegalArgumentException(s"kausi $kausiString is not valid of form yyyyS or yyyyK")
      }
      val JInt(tila) = json \ "tila"
      tila.toInt match {
        case 1 => Lasna(kausi)
        case 2 => Poissa(kausi)
        case 3 => PoissaEiKulutaOpintoaikaa(kausi)
        case 4 => Puuttuu(kausi)
        case _ => throw new IllegalArgumentException(s"tila $tila is not in [1, 2, 3, 4]")
      }
  },
  {
    case l: Lasnaolo => l match {
      case Lasna(Kevat(v))                      => ("kausi" -> s"${v}K") ~ ("tila" -> 1)
      case Lasna(Syksy(v))                      => ("kausi" -> s"${v}S") ~ ("tila" -> 1)
      case Poissa(Kevat(v))                     => ("kausi" -> s"${v}K") ~ ("tila" -> 2)
      case Poissa(Syksy(v))                     => ("kausi" -> s"${v}S") ~ ("tila" -> 2)
      case PoissaEiKulutaOpintoaikaa(Kevat(v))  => ("kausi" -> s"${v}K") ~ ("tila" -> 3)
      case PoissaEiKulutaOpintoaikaa(Syksy(v))  => ("kausi" -> s"${v}S") ~ ("tila" -> 3)
      case Puuttuu(Kevat(v))                    => ("kausi" -> s"${v}K") ~ ("tila" -> 4)
      case Puuttuu(Syksy(v))                    => ("kausi" -> s"${v}S") ~ ("tila" -> 4)
      case _ => throw new IllegalArgumentException(s"could not serialize lasnaolo $l")
    }
  }
  )
)

