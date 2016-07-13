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
