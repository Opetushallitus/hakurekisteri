#!/bin/bash
set -e
shopt -s nullglob

if test "$#" -ne 1; then
    echo "Illegal number of parameters: $#"
    exit 1
fi

SERVICE=$1
BRANCH=$(git rev-parse --abbrev-ref HEAD | sed 's+/+-+g')
ZIP=${PWD}/target/${SERVICE}_${BRANCH}_$(date +%Y%m%d%H%M%S).zip

echo "Building $ZIP"

cd ./target
JAR=( ./*-allinone.jar )
[ ${#JAR[@]} -lt 1 ] && { printf "No runnable jar found\n"; exit 1; }
[ ${#JAR[@]} -gt 1 ] && { printf "Multiple runnable jars found: %s\n" "${JAR[*]}"; exit 1; }
cp "${JAR[0]}" "${SERVICE}.jar"
zip -m "${ZIP}" "${SERVICE}.jar"
cd -
cd target/classes
zip -r "${ZIP}" oph-configuration
cd -
