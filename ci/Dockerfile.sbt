FROM hseeberger/scala-sbt
ARG JENKINS_UID=1000
RUN adduser -u ${JENKINS_UID} jenkins
RUN mkdir /home/jenkins/.m2 && \
  mkdir /home/jenkins/.ivy2 && \
  mkdir /home/jenkins/.sbt
COPY config/repositories /home/jenkins/.sbt/repositories
#RUN curl -L http://livibuild04.vally.local/nexus/repository/maven-public/com/oracle/ojdbc6/11.2.0.3.0/ojdbc6-11.2.0.3.0.pom
RUN chown -R jenkins /home/jenkins
RUN echo "-Dhttp.proxyHost=172.17.208.16 >> /usr/share/sbt/conf/sbtconfig.txt"
RUN echo "-Dhttp.proxyPort=8085 >> /usr/share/sbt/conf/sbtconfig.txt"
RUN echo "-Dhttp.nonProxy=*.vally.local >> /usr/share/sbt/conf/sbtconfig.txt"
