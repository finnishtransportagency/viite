FROM hseeberger/scala-sbt
ARG JENKINS_UID=1000
RUN adduser -u ${JENKINS_UID} jenkins
RUN mkdir /home/jenkins/.m2 && \
  mkdir /home/jenkins/.ivy2 && \
  mkdir /home/jenkins/.sbt
RUN chown -R jenkins /home/jenkins