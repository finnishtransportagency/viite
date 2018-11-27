FROM hseeberger/scala-sbt
ARG JENKINS_UID=1000
RUN adduser -u ${JENKINS_UID} jenkins
RUN mkdir /home/jenkins/.m2
RUN mkdir /home/jenkins/.ivy
RUN mkdir /home/jenkins/.sbt
COPY config/repositories /home/jenkins/.sbt/repositories
RUN chown -R jenkins /home/jenkins
RUN curl http://livibuild04.vally.local/nexus/repository/ivy-releases/org.scala-sbt/sbt/0.13.5/jars