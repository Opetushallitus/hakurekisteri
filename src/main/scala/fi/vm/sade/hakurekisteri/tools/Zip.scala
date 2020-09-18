package fi.vm.sade.hakurekisteri.tools

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.util.zip.{ZipEntry, ZipOutputStream, ZipInputStream}

import org.apache.commons.io.IOUtils

import scala.util.Try

object Zip {
  def zipit(i: InputStream): InputStream = {
    val b = new ByteArrayOutputStream()
    val z = new ZipOutputStream(b)
    val e = new ZipEntry("s.json")
    z.putNextEntry(e)
    IOUtils.copy(i, z)
    IOUtils.closeQuietly(i)
    z.closeEntry()
    z.close()
    IOUtils.closeQuietly(z)
    new ByteArrayInputStream(b.toByteArray)
  }

  def toInputStreams(z: ZipInputStream): Iterator[InputStream] = {
    Iterator
      .continually(Try(z.getNextEntry()).getOrElse(null))
      .takeWhile(_ != null)
      .map(_ => z)
  }

  def toInputStreams(zs: Iterator[ZipInputStream]): Iterator[InputStream] =
    zs.toList.toIterator.flatMap(toInputStreams)

}
