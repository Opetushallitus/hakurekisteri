clean:
	mvn clean

jar:
	mvn clean package

test:
	mvn clean test

zip: jar
	sbin/build_deploy_zip.sh suoritusrekisteri

createDevDb:
	mvn scala:run -Dlauncher=createDevDb

generateSchema:
	mvn scala:run -Dlauncher=generateSchema
