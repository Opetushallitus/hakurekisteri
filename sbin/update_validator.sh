#!/bin/bash -e

rootdir=`dirname $0`/..
validatorroot=$rootdir/../validaattori
validatorversion=`git log --oneline -n 1|cut -d ' ' -f 1`

echo "## building validator js bundle"

(cd $validatorroot && lein with-profile hakurekisteri cljsbuild once prod)

echo "## copying"

cp $validatorroot/target/prod/hakurekisteri-validator.min.js $rootdir/web/src/main/webapp/static/js

echo "## done, validator revision $validatorversion"
