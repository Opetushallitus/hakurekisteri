package fi.vm.sade.hakurekisteri.tools

import org.scalactic._
import scala.xml.{Text, Node, Elem}

object XmlEquality extends XmlEquality

trait XmlEquality extends Explicitly {
  val normalized: Uniformity[Elem] = new Uniformity[Elem] {

    override def normalizedOrSame(b: Any): Any = b match {
      case e: Elem => normalized(e)
      case a => a
    }

    def trimTextZappingEmpty(node: Node): Seq[Node] =
      node match {
        case Text(text) if (text.trim.isEmpty) => Nil
        case Text(text) => List(Text(text.trim))
        case Elem(pre, lab, md, scp, children @ _*) =>
          Elem(pre, lab, md, scp, false, (children.flatMap(trimTextZappingEmpty)):_*)
        case _ => List(node)
      }

    override def normalized(elem: Elem): Elem = elem match {
      case Elem(pre, lab, md, scp, children @ _*) =>
        val mergedTextNodes = // Merge adjacent text nodes
          children.foldLeft(Nil: List[Node]) { (acc, ele) =>
            ele match {
              case eleTxt: Text =>
                acc.headOption match {
                  case Some(accTxt: Text) =>
                    Text(accTxt.text + eleTxt.text) :: acc.tail
                  case _ => ele :: acc
                }
              case _ => ele :: acc
            }
          }
        Elem(pre, lab, md, scp, false, (mergedTextNodes.flatMap(trimTextZappingEmpty)):_*)
    }

    override def normalizedCanHandle(b: Any): Boolean = b.isInstanceOf[Elem]
  }

}