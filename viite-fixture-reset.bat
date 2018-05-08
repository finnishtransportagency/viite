if [%2]==[] sbt "project digiroad2-viite" "test:run-main fi.liikennevirasto.viite.util.DataFixture %1"
if [%2]<>[] sbt %2 "project digiroad2-viite" "test:run-main fi.liikennevirasto.viite.util.DataFixture %1"
