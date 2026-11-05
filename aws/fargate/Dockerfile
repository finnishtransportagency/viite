FROM amazoncorretto:11-alpine-jdk
RUN apk update && apk upgrade && apk --no-cache add curl
COPY dist/ /src/main/webapp/viite/
COPY src/main/webapp/WEB-INF/ /src/main/webapp/viite/WEB-INF/
COPY target/scala-2.12/Viite-assembly-1.0.0.jar /viite.jar
EXPOSE 9080/tcp
ENTRYPOINT ["java","-Xms512m","-Xmx2048m","-Djava.security.egd=file:/dev/./urandom","-Duser.timezone=Europe/Helsinki","-XX:-OmitStackTraceInFastThrow","-jar","/viite.jar"]
