#!/usr/bin/env bash
set -e
JAVA_OPTS="-Xms1512M -Xmx4096m -Xss1M -XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=1024M -XX:+HeapDumpOnOutOfMemoryError -javaagent:newrelic.jar"
java $JAVA_OPTS -jar `dirname $0`/sbt-launch.jar ${2} 'project digiroad2-viite' "test:run-main fi.liikennevirasto.digiroad2.util.DataFixture ${1}"