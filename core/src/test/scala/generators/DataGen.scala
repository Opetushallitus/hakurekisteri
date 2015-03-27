package generators

import scala.annotation.tailrec
import scala.util.Random
import java.util.UUID

trait DataGen[T] {
  self =>

  def generate: T

  def map[S](f:T => S): DataGen[S] = new DataGen[S] {
    override def generate: S = f(self.generate)
  }

  def flatMap[S](f: T => DataGen[S]): DataGen[S] = f(self.generate)

  def filter(f: T => Boolean) = new DataGen[T] {

    @tailrec def genValid: T = self.generate match {
      case valid if f(valid) => valid
      case invalid => genValid
    }

    override def generate: T = genValid
  }

}

object DataGen {

  def int(min: Int, max:Int): DataGen[Int] = new DataGen[Int]{
    override def generate: Int = min + Random.nextInt(max - min + 1)
  }

  def seq[T](item: DataGen[T], size: Int):DataGen[Seq[T]] = new DataGen[Seq[T]]{
    override def generate: Seq[T] = for {
      i <- 0 until size
    } yield item.generate
  }

  def values[T](items: T*): DataGen[T] = new DataGen[T]{
    override def generate: T = items(Random.nextInt(items.size))
  }

  def always[T](item: T): DataGen[T] = new DataGen[T]{
    override def generate: T = item
  }

  def uuid: DataGen[UUID] = new DataGen[UUID]{
    override def generate: UUID = UUID.randomUUID()
  }

  def combine[T](generators: Seq[DataGen[T]]):DataGen[Seq[T]] = new DataGen[Seq[T]] {
    override def generate: Seq[T] = for (
      generator <- generators
    ) yield generator.generate
  }

}

