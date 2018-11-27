FROM hseeberger/scala-sbt
ARG JENKINS_UID=1000
RUN adduser -u ${JENKINS_UID} jenkins
RUN mkdir /home/jenkins/.m2 && \
  mkdir /home/jenkins/.ivy && \
  mkdir /home/jenkins/.sbt && \
  apk update && apk upgrade && \
  apk add openssh-client
COPY config/repositories /home/jenkins/.sbt/repositories
RUN chown -R jenkins /home/jenkins
RUN curl http://livibuild04.vally.local/nexus/repository/ivy-releases/org.scala-sbt/sbt/0.13.5/jars