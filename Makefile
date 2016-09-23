clean:
	mvn clean

package:
	mvn clean package

source-to-image:
	mvn clean compile
	npm run build
	mvn package -DskipTests=true -DtestFailureIgnore=true

test:
	mvn clean test

createDevDb:
	mvn scala:run -Dlauncher=createDevDb

generateSchema:
	mvn scala:run -Dlauncher=generateSchema
