package generators

import scala.annotation.tailrec
import scala.util.Random
import java.util.UUID
import org.joda.time.LocalDate
import org.scalatest.matchers.Matcher

trait DataGen[T] {
  self =>

  def generate: T

  def map[S](f: T => S): DataGen[S] = new DataGen[S] {
    override def generate: S = f(self.generate)
  }

  def flatMap[S](f: T => DataGen[S]): DataGen[S] = f(self.generate)

  def filter(f: T => Boolean) = new DataGen[T] {

    @tailrec def genValid: T = self.generate match {
      case valid if f(valid) => valid
      case invalid           => genValid
    }

    override def generate: T = genValid
  }

  def withFilter(f: T => Boolean) = filter(f)

}

object DataGen {

  def int(min: Int, max: Int): DataGen[Int] = new DataGen[Int] {
    override def generate: Int = min + Random.nextInt(max - min + 1)
  }

  def seq[T](item: DataGen[T], size: Int): DataGen[Seq[T]] = new DataGen[Seq[T]] {
    override def generate: Seq[T] = for {
      i <- 0 until size
    } yield item.generate
  }

  def values[T](items: T*): DataGen[T] = new DataGen[T] {
    override def generate: T = items(Random.nextInt(items.size))
  }

  def always[T](item: T): DataGen[T] = new DataGen[T] {
    override def generate: T = item
  }

  def uuid: DataGen[UUID] = new DataGen[UUID] {
    override def generate: UUID = UUID.randomUUID()
  }

  def combine[T](generators: Seq[DataGen[T]]): DataGen[Seq[T]] = new DataGen[Seq[T]] {
    override def generate: Seq[T] = for (generator <- generators) yield generator.generate
  }

  def set[T](generators: Seq[DataGen[T]]): DataGen[Set[T]] = new DataGen[Set[T]] {
    override def generate: Set[T] = ???
  }

  def kk = DataGen.int(1, 12)
  def maxPaiva(kk: Int): Int = kk match {
    case 1 | 3 | 5 | 7 | 8 | 10 | 12 => 31
    case 2                           => 28
    case _                           => 30
  }
  def paiva = for (
    kk <- kk;
    pv <- DataGen.int(1, maxPaiva(kk))
  ) yield new LocalDate(1901 + new Random().nextInt(99), kk, pv)

  def sukupuoli = DataGen.values("mies", "nainen")
  val merkit = "0123456789ABCDEFHJKLMNPRSTUVWXY"
  val valimerkit = "+-A"
  def hetu = for (
    syntymaPaiva <- paiva;
    sukupuoli <- sukupuoli;
    luku <- DataGen.int(0, 49)
  ) yield {
    val alku = syntymaPaiva.toString("ddMMyy")
    val finalLuku = sukupuoli match {
      case "mies" => luku * 2 + 1
      case _      => luku * 2
    }
    val loppu = "9" + "%02d".format(finalLuku)
    val merkki = merkit((alku + loppu).toInt % 31)
    val valimerkki = valimerkit((syntymaPaiva.getYear / 100) - 18)
    alku + valimerkki + loppu + merkki
  }

  def oid(kanta: String) = for (loppu <- DataGen.seq(DataGen.int(0, 9), 11))
    yield kanta + loppu.mkString

  def henkiloOid = oid("1.2.246.562.24.")

}

trait DataGeneratorSupport {
  import scala.language.implicitConversions
  implicit def matcherForGenerator[T](m: Matcher[T]): Matcher[DataGen[T]] = m compose {
    (dg: DataGen[T]) => dg.generate
  }

}
