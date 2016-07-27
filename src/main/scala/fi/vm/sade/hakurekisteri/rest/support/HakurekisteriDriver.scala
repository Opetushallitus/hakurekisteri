package fi.vm.sade.hakurekisteri.rest.support

import java.sql.{PreparedStatement, ResultSet}
import java.util.UUID

import slick.ast.FieldSymbol
import slick.driver.PostgresDriver

object HakurekisteriDriver extends PostgresDriver {

  override val columnTypes = new super.JdbcTypes {
    override val uuidJdbcType = new UUIDJdbcType {
      override def sqlTypeName(sym: Option[FieldSymbol]) = "UUID"
      override def setValue(v: UUID, p: PreparedStatement, idx: Int) = p.setObject(idx, v.toString, sqlType)
      override def updateValue(v: UUID, r: ResultSet, idx: Int) = r.updateObject(idx, v.toString)
      override def getValue(r: ResultSet, idx: Int) = UUID.fromString(r.getString(idx))
      override def valueToSQLLiteral(value: UUID) = s"'${value.toString}'"
      override def hasLiteralForm = true
    }
  }

  override val api = new API with HakurekisteriColumns {
    override implicit lazy val uuidColumnType = columnTypes.uuidJdbcType
  }
}
