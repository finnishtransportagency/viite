#!/bin/sh
echo ${2} ${3} 'project digiroad2-viite' "test:run-main fi.liikennevirasto.viite.util.DataFixture ${1}"
./sbt ${2} ${3} 'project digiroad2-viite' "test:run-main fi.liikennevirasto.viite.util.DataFixture ${1}"
