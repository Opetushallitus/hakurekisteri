#!/bin/bash
set -e

OUTPUTDIR=target/sql2diagram
DDL=db/schema.ddl

echo "creating directory $OUTPUTDIR"
mkdir -p $OUTPUTDIR

echo "generating schema file $DDL"
make generateSchema

echo "generating diagrams to $OUTPUTDIR"
/usr/sbin/sql2diagram $DDL $OUTPUTDIR/suoritusrekisteri-$(mvn org.apache.maven.plugins:maven-help-plugin:2.1.1:evaluate -Dexpression=project.version | grep -v INFO)

echo "uploading diagrams from $OUTPUTDIR to bamboo@pulpetti:/var/www/html/db/"
scp $OUTPUTDIR/* bamboo@pulpetti:/var/www/html/db/

echo "done."
exit 0