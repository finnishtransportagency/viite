FROM hseeberger/scala-sbt
ARG JENKINS_UID=1000
RUN adduser -u ${JENKINS_UID} jenkins
RUN mkdir /home/jenkins/.m2 && \
  mkdir /home/jenkins/.ivy2 && \
  mkdir /home/jenkins/.sbt
RUN export JAVA_OPTS="$JAVA_OPTS -Dhttp.proxyHost=172.17.208.16 -Dhttp.proxyPort=8085 -Dhttps.proxyHost=172.17.208.16 -Dhttps.proxyPort=8085"
COPY config/repositories /home/jenkins/.sbt/repositories
RUN chown -R jenkins /home/jenkins