#!/bin/bash
set -e
shopt -s nullglob

if test "$#" -ne 3; then
    echo "Illegal number of parameters: $#"
    exit 1
fi

DEST=$1
ARTIFACTID=$2
VERSION=$3
TIMESTAMP=$(date)

set_property() {
    ENTRY=`printf "$1=%s\n" "$2"`
    echo "$ENTRY" >> "${DEST}"
}

cp /dev/null $DEST
set_property "artifactId" "${ARTIFACTID}"
set_property "version" "${VERSION}"
set_property "buildNumber" "${TRAVIS_BUILD_NUMBER}"
set_property "branch" "${TRAVIS_BRANCH}"
set_property "commit" "${TRAVIS_COMMIT}"
set_property "date" "${TIMESTAMP}"
