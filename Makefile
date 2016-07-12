clean:
	mvn clean

jar:
	mvn clean package

test:
	mvn clean test

createDevDb:
	mvn scala:run -Dlauncher=createDevDb

generateSchema:
	mvn scala:run -Dlauncher=generateSchema

generateDbDiagram:
	sbin/generateDbDiagram.sh target/sql2diagram suoritusrekisteri-$ver
