FROM amazoncorretto:11-alpine-jdk
RUN apk --no-cache add curl
RUN mkdir /src
RUN mkdir /src/main
RUN mkdir /src/main/webapp
RUN mkdir /src/main/webapp/viite/
RUN mkdir /src/main/webapp/viite/node_modules
COPY dist/ /src/main/webapp/viite/
COPY viite-UI/ /src/main/webapp/viite/
COPY src/main/webapp/ /src/main/webapp/viite/
COPY node_modules/ /src/main/webapp/viite/node_modules/
COPY target/scala-2.12/Viite-assembly-1.0.0.jar /viite.jar
EXPOSE 9080/tcp
ENTRYPOINT ["java","-Xms512m","-Xmx2048m","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Europe/Helsinki","-XX:-OmitStackTraceInFastThrow","-jar","/viite.jar"]
