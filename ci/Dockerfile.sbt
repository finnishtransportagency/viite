FROM hseeberger/scala-sbt
ARG JENKINS_UID=1000
RUN adduser -u ${JENKINS_UID} jenkins
RUN mkdir /home/jenkins/.m2
RUN mkdir /home/jenkins/.ivy
COPY confs/settings.xml /home/jenkins/.m2/settings.xml
RUN chown -R jenkins /home/jenkins