package fi.vm.sade.hakurekisteri.tools

import java.io.{InputStream, FilterInputStream}

case class ProgressInputStream(l: Int => Unit)(i: InputStream) extends FilterInputStream(i) {

  var bytesRead = 0

  override def read(): Int = {
    val i = super.read()
    bytesRead += i
    l(bytesRead)
    i
  }

  override def read(b: Array[Byte]): Int = {
    super.read(b) // call-through
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    val i = super.read(b, off, len)
    bytesRead += i
    l(bytesRead)
    i
  }

  override def skip(n: Long) = throw new UnsupportedOperationException()
  override def mark(readlimit: Int) = throw new UnsupportedOperationException()
  override def reset() = throw new UnsupportedOperationException()
  override def markSupported() = false
}
