FROM hseeberger/scala-sbt
ARG JENKINS_UID=1000
RUN adduser -u ${JENKINS_UID} jenkins
RUN mkdir /home/jenkins/.m2
RUN mkdir /home/jenkins/.ivy
RUN chown -R jenkins /home/jenkins