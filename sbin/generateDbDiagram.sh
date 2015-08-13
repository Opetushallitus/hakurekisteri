#!/bin/bash

echo "creating directory $1"
mkdir -p $1

echo "generating diagrams to $1"
/usr/sbin/sql2diagram db/schema.ddl $1/$2

echo "uploading diagrams from $1 to bamboo@pulpetti:/var/www/html/db/"
scp $1/* bamboo@pulpetti:/var/www/html/db/

echo "done."
exit 0