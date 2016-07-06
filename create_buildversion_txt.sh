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
BRANCH=$(git rev-parse --abbrev-ref HEAD | sed 's+/+-+g')
COMMIT=$(git rev-parse HEAD)
TIMESTAMP=$(date)

set_property() {
    ENTRY=`printf "$1=%s\n" "$2"`
    echo "POW $DEST"
    echo "$ENTRY" >> "${DEST}"
}

cp /dev/null $DEST
set_property "artifactId" "${ARTIFACTID}"
set_property "version" "${VERSION}"
set_property "branch" "${BRANCH}"
set_property "commit" "${COMMIT}"
set_property "date" "${TIMESTAMP}"
