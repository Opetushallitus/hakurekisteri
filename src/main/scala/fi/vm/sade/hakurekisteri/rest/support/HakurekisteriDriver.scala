package fi.vm.sade.hakurekisteri.rest.support

import java.sql.{PreparedStatement, ResultSet}
import java.util.UUID

import com.github.nscala_time.time.Imports._
import slick.ast.{FieldSymbol, Node}
import slick.jdbc.{JdbcStatementBuilderComponent, PostgresProfile}

object HakurekisteriDriver extends PostgresProfile {

  override val columnTypes: HakurekisteriDriver.JdbcTypes = new super.JdbcTypes {
    override val uuidJdbcType: UUIDJdbcType = new UUIDJdbcType {
      override def sqlTypeName(sym: Option[FieldSymbol]): String = "VARCHAR"
      override def setValue(v: UUID, p: PreparedStatement, idx: Int): Unit = p.setObject(idx, v.toString, sqlType)
      override def updateValue(v: UUID, r: ResultSet, idx: Int): Unit = r.updateObject(idx, v.toString)
      override def getValue(r: ResultSet, idx: Int): UUID = UUID.fromString(r.getString(idx))
      override def valueToSQLLiteral(value: UUID) = s"'${value.toString}'"
      override def hasLiteralForm = true
    }
  }
  override val api: HakurekisteriDriver.API with HakurekisteriColumns = new API with HakurekisteriColumns {
    override implicit lazy val uuidColumnType: columnTypes.UUIDJdbcType = columnTypes.uuidJdbcType
  }

  import api._

  val startOfYear = SimpleExpression.unary[String, DateTime] {
    case (year, qb) =>
      qb.sqlBuilder += "extract(epoch from to_timestamp("
      qb.expr(year)
      qb.sqlBuilder += " || '-01-01', 'YYYY-MM-DD')) * 1000"
  }

  val startOfAutumnDate = SimpleExpression.unary[String, LocalDate] {
    case (year, qb) =>
      qb.expr(year)
      qb.sqlBuilder += " || '-08-01'"
  }

  val startOfAutumn = SimpleExpression.unary[String, DateTime] {
    case (year, qb) =>
      qb.sqlBuilder += "extract(epoch from to_timestamp("
      qb.expr(year)
      qb.sqlBuilder += " || '-08-01', 'YYYY-MM-DD')) * 1000"
  }

  private def yearOfExpr(millis: Node, qb: JdbcStatementBuilderComponent#QueryBuilder): Unit = {
    qb.sqlBuilder += "extract(year from to_timestamp("
    qb.expr(millis)
    qb.sqlBuilder += " / 1000))"
  }

  def yearOf(c: Rep[DateTime]): Rep[String] = SimpleExpression.unary[DateTime, String](yearOfExpr).apply(c)

  def yearOf(c: Rep[Option[DateTime]])(implicit d: DummyImplicit): Rep[String] = SimpleExpression.unary[Option[DateTime], String](yearOfExpr).apply(c)

  def yearOf(c: Rep[Option[LocalDate]])(implicit d1: DummyImplicit, d2: DummyImplicit): Rep[String] = SimpleExpression.unary[Option[LocalDate], String]({
    case (date, qb) =>
      qb.sqlBuilder += "extract(year from to_timestamp("
      qb.expr(date)
      qb.sqlBuilder += ", 'YYYY-MM-DD'))"
  }).apply(c)
}
