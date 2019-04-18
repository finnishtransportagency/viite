#!/bin/sh
if [[ -e ~/.java.opts ]]; then
  source ~/.java.opts
else
  javaopts="-Xms2048m -Xmx4096m"
fi
jarfile="target/scala-2.11/viite-assembly-0.1.0-SNAPSHOT.jar"
javaopts="-javaagent:newrelic.jar $javaopts -Dfile.encoding=UTF8 -Djava.security.egd=file:///dev/urandom -jar $jarfile"
logfile="digiroad2.boot.log"
jmxmonitoring="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9020 -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.rmi.port=9020"

nohup java $jmxmonitoring $javaopts > $logfile &