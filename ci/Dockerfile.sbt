FROM hseeberger/scala-sbt
ARG JENKINS_UID=1000
RUN adduser -u ${JENKINS_UID} jenkins
RUN mkdir /home/jenkins/.m2 && \
  mkdir /home/jenkins/.ivy2 && \
  mkdir /home/jenkins/.sbt
COPY confs/settings.xml /home/jenkins/.m2/settings.xml
RUN chown -R jenkins /home/jenkins