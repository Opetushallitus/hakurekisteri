package fi.vm.sade.scalaproperties

import java.util

import scala.collection.Map

/**
  * Vendored from the archived Opetushallitus/scala-utils repository, module scala-properties_2.12
  * (previously fi.vm.sade:scala-properties_2.12). That repository is no longer maintained and the
  * artifact was only ever published to the decommissioned OPH Artifactory, so the code lives here
  * instead. The package name is kept unchanged so existing call sites work as before.
  *
  * The Java base class fi.vm.sade.properties.OphProperties still comes from the maintained
  * fi.vm.sade.java-utils:java-properties, which is available from GitHub Packages.
  *
  * Behaviour is that of the original, with two adjustments required by this build's
  * -unchecked -deprecation -Xfatal-warnings settings:
  *   - the generic type patterns in convertToJava carry @unchecked; the type arguments were always
  *     erased, so this only silences a warning the original build did not enforce
  *   - the deprecated `/:` fold operator is written as foldLeft
  */
class OphProperties(files: String*) extends fi.vm.sade.properties.OphProperties(files: _*) {
  private val excludeCCFields = List("$outer")

  private def caseClassToMap(cc: Product) = {
    val declaredFields =
      cc.getClass.getDeclaredFields.toList.filter(f => !excludeCCFields.contains(f.getName))
    declaredFields.foldLeft(Map[AnyRef, AnyRef]()) { (a, f) =>
      f.setAccessible(true)
      a + (f.getName -> f.get(cc))
    }
  }

  private def removeOption(map: Map[AnyRef, AnyRef]) = {
    for ((k, v) <- map if v != None)
      yield (
        k,
        v match {
          case Some(option: AnyRef) => option
          case _                    => v
        }
      )
  }

  private def toJavaMap(map: Map[AnyRef, AnyRef]) = {
    val dest = new util.LinkedHashMap[AnyRef, AnyRef](map.size)
    val option: Map[AnyRef, AnyRef] = removeOption(map)
    option.foreach { case (k, v) => dest.put(k, convertToJava(v)) }
    dest
  }

  private def toJavaList(seq: Seq[AnyRef]) = {
    val dest = new util.ArrayList[AnyRef](seq.size)
    seq.foreach { case (u) => dest.add(convertToJava(u)) }
    dest
  }

  private def convertToJava(o: AnyRef): AnyRef = o match {
    case seq: Seq[AnyRef @unchecked] =>
      toJavaList(seq)
    case map: Map[AnyRef @unchecked, AnyRef @unchecked] =>
      toJavaMap(map)
    case cc: Product =>
      toJavaMap(caseClassToMap(cc))
    case _ =>
      o
  }

  override def convertParams(params: AnyRef*): Array[AnyRef] = {
    params.map(convertToJava).toArray
  }
}
