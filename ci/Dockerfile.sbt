FROM hseeberger/scala-sbt
ARG JENKINS_UID=1000
RUN adduser -u ${JENKINS_UID} jenkins
RUN mkdir /home/jenkins/.m2
RUN mkdir /home/jenkins/.ivy
RUN mkdir /home/jenkins/.sbt
COPY config/repositories /home/jenkins/.sbt/repositories
RUN chown -R jenkins /home/jenkins
RUN curl http://livibuild04.vally.local/nexus/repository/3rdparty/com/oracle/ojdbc6/11.2.0.3.0/ojdbc6-11.2.0.3.0.jar