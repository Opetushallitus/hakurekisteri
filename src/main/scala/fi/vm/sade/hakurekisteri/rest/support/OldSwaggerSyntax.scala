package fi.vm.sade.hakurekisteri.rest.support

import org.scalatra.swagger.{ModelProperty, AllowableValues, DataType}

/**
 * Created by verneri on 23.10.2014.
 */
trait OldSwaggerSyntax {
  case class ModelField(name: String,
                        description: String,
                        dataType: DataType,
                        defaultValue: Option[String] = None,
                        allowableValues: AllowableValues = AllowableValues.AnyValue,
                        required: Boolean = true) {

    val property = ModelProperty(dataType, required = required, description = Some(description), allowableValues = allowableValues)

  }


  object Model {
    def apply(name: String, description: String, properties: Map[String, ModelField]) =
      org.scalatra.swagger.Model(name, description, properties = properties.mapValues(_.property).toList)
  }

}
