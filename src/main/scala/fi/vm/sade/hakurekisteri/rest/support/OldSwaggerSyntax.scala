package fi.vm.sade.hakurekisteri.rest.support

import org.scalatra.swagger.{ModelProperty, AllowableValues, DataType}

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
    def apply(name: String, description: String, properties: Map[String, ModelField], baseModel: Option[String] = None, discriminator: Option[String] = None) =
      org.scalatra.swagger.Model(id = name, name = description, properties = properties.mapValues(_.property).toList, baseModel = baseModel, discriminator = discriminator)
  }

}
