#!/bin/sh
if [ -e ~/.sbt.opts ]; then
  source ~/.sbt.opts
else
  SBT_OPTS="-Xms1512M -Xmx2536M -Xss1M -Dfile.encoding=utf-8"
fi
SBT_OPTS="$SBT_OPTS -XX:+CMSClassUnloadingEnabled -Dhttp.proxyHost=http://172.17.208.16 -Dhttps.proxyHost=http://172.17.208.16 -Dhttp.proxyPort=8085 -Dhttps.proxyPort=8085 -Dhttp.nonProxy=*.vally.local -Dhttps.nonProxy=*.vally.local"
echo $SBT_OPTS
java $SBT_OPTS -jar `dirname $0`/sbt-launch.jar "$@"
