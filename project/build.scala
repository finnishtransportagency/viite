import org.scalatra.sbt._
import sbt.Keys._
import sbt.{Def, _}
import sbtassembly.Plugin.AssemblyKeys._
import sbtassembly.Plugin.MergeStrategy

object Digiroad2Build extends Build {
  val Organization = "fi.liikennevirasto"
  val Digiroad2Name = "viite"
  val Digiroad2GeoName = "digiroad2-geo"
  val Version = "0.1.0-SNAPSHOT"

  val ScalaVersion = "2.11.12"
  val ScalatraVersion = "2.6.5"
  val ScalaTestVersion = "3.2.0-SNAP7"
  val JodaConvertVersion = "2.2.0"
  val JodaTimeVersion = "2.10.1"
  val AkkaVersion = "2.5.22"
  val HttpClientVersion = "4.5.8"
  val NewRelicApiVersion = "5.0.0"
  val ScalatraSwaggerVersion = "2.6.5"
  val CommonsIOVersion = "2.6"
  val JsonJacksonVersion = "3.6.5"
  val MockitoCoreVersion = "2.27.0"
  val LogbackClassicVersion = "1.2.3"
  val JettyVersion = "9.2.28.v20190418"

  val env: String = if (System.getProperty("digiroad2.env") != null) System.getProperty("digiroad2.env") else "dev"
  val testEnv: String = if (System.getProperty("digiroad2.env") != null) System.getProperty("digiroad2.env") else "test"
  lazy val geoJar = Project (
    Digiroad2GeoName,
    file(Digiroad2GeoName),
    settings = Defaults.defaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2GeoName,
      version := Version,
      scalaVersion := ScalaVersion,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      resolvers := Seq(Classpaths.typesafeReleases,
        "osgeo" at "http://download.osgeo.org/webdav/geotools/",
        "opengeo" at "http://repo.boundlessgeo.com/main/"),
      libraryDependencies ++= Seq(
        "org.joda" % "joda-convert" % JodaConvertVersion,
        "joda-time" % "joda-time" % JodaTimeVersion,
        "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
        "javax.media" % "jai_core" % "1.1.3" from "http://download.osgeo.org/webdav/geotools/javax/media/jai_core/1.1.3/jai_core-1.1.3.jar",
        "org.geotools" % "gt-graph" % "21.0",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test"
      )
    )
  )

  val Digiroad2OracleName = "digiroad2-oracle"
  lazy val oracleJar = Project (
    Digiroad2OracleName,
    file(Digiroad2OracleName),
    settings = Defaults.defaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2OracleName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers ++= Seq(Classpaths.typesafeReleases,
        "maven-public" at "http://livibuild04.vally.local/nexus/repository/maven-public/",
        "ivy-public" at "http://livibuild04.vally.local/nexus/repository/ivy-public/"),
      scalacOptions ++= Seq("-unchecked", "-feature"),
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.apache.commons" % "commons-lang3" % "3.9",
        "commons-codec" % "commons-codec" % "1.12",
        "com.jolbox" % "bonecp" % "0.8.0.RELEASE",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "com.typesafe.slick" %% "slick" % "3.0.0",
        "org.json4s"   %% "json4s-jackson" % JsonJacksonVersion,
        "org.joda" % "joda-convert" % JodaConvertVersion,
        "joda-time" % "joda-time" % JodaTimeVersion,
        "com.github.tototoshi" %% "slick-joda-mapper" % "2.0.0",
        "com.github.tototoshi" %% "scala-csv" % "1.3.5",
        "org.apache.httpcomponents" % "httpclient" % HttpClientVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        "org.mockito" % "mockito-core" % MockitoCoreVersion % "test",
        "org.flywaydb" % "flyway-core" % "4.2.0" % "test",
        "com.oracle" % "ojdbc6" % "11.2.0.3.0",
        "com.oracle" % "sdoapi" % "11.2.0",
        "com.oracle" % "sdoutl" % "11.2.0"
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / "conf" /  env,
      unmanagedResourceDirectories in Test += baseDirectory.value / "conf" /  testEnv,
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf" /  env
    )
  ) dependsOn geoJar

  val Digiroad2ViiteName = "digiroad2-viite"
  lazy val viiteJar = Project (
    Digiroad2ViiteName,
    file(Digiroad2ViiteName),
    settings = Defaults.defaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2ViiteName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      parallelExecution in Test := false,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % ScalatraVersion,
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        "org.json4s"   %% "json4s-jackson" % JsonJacksonVersion,
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion % "test",
        "org.mockito" % "mockito-core" % MockitoCoreVersion % "test",
        "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % "test",
        "ch.qos.logback" % "logback-classic" % LogbackClassicVersion % "runtime",
        "commons-io" % "commons-io" % CommonsIOVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        "org.apache.httpcomponents" % "httpclient" % HttpClientVersion,
        "org.scalatra" %% "scalatra-swagger"  % ScalatraSwaggerVersion,
        "com.github.nscala-time" %% "nscala-time" % "2.22.0"
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / "conf" /  env,
      unmanagedResourceDirectories in Test += baseDirectory.value / "conf" /  testEnv,
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf" /  env
    )
  ) dependsOn(geoJar, oracleJar % "compile->compile;test->test")

  val Digiroad2ApiName = "digiroad2-api-common"
  lazy val commonApiJar = Project (
    Digiroad2ApiName,
    file(Digiroad2ApiName),
    settings = Defaults.defaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2ApiName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      //      parallelExecution in Test := false,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
        "org.apache.httpcomponents" % "httpclient" % HttpClientVersion,
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "compile, test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        "org.scalatra" %% "scalatra-swagger" % ScalatraSwaggerVersion,
        "org.mockito" % "mockito-core" % MockitoCoreVersion % "test",
        "org.joda" % "joda-convert" % JodaConvertVersion,
        "joda-time" % "joda-time" % JodaTimeVersion,
        "org.eclipse.jetty" % "jetty-webapp" % JettyVersion % "compile",
        "org.eclipse.jetty" % "jetty-servlets" % JettyVersion % "compile",
        "org.eclipse.jetty" % "jetty-proxy" % JettyVersion % "compile",
        "org.eclipse.jetty" % "jetty-jmx" % JettyVersion % "compile",
        "javax.servlet" % "javax.servlet-api" % "4.0.1" % "provided;test" artifacts Artifact("javax.servlet-api", "jar", "jar")
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / "conf" /  env,
      unmanagedResourceDirectories in Test += baseDirectory.value / "conf" /  testEnv,
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf" /  env
    )
  ) dependsOn(geoJar, oracleJar, viiteJar)

  val Digiroad2ViiteApiName = "digiroad2-api-viite"
  lazy val viiteApiJar = Project (
    Digiroad2ViiteApiName,
    file(Digiroad2ViiteApiName),
    settings = Defaults.defaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2ViiteApiName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      //      parallelExecution in Test := false,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % ScalatraVersion,
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        "org.json4s"   %% "json4s-jackson" % JsonJacksonVersion,
        "org.json4s"   %% "json4s-native" % "3.6.5",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        "org.mockito" % "mockito-core" % MockitoCoreVersion % "test",
        "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % "test",
        "ch.qos.logback" % "logback-classic" % LogbackClassicVersion % "runtime",
        "commons-io" % "commons-io" % CommonsIOVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        "org.apache.httpcomponents" % "httpclient" % HttpClientVersion,
        "org.scalatra" %% "scalatra-swagger"  % ScalatraSwaggerVersion
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / "conf" /  env,
      unmanagedResourceDirectories in Test += baseDirectory.value / "conf" /  testEnv,
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf" /  env
    )
  ) dependsOn(geoJar, oracleJar, viiteJar, commonApiJar % "compile->compile;test->test")

  lazy val warProject = Project (
    Digiroad2Name,
    file("."),
    settings = Defaults.defaultSettings
      ++ assemblySettings
      ++ net.virtualvoid.sbt.graph.Plugin.graphSettings
      ++ ScalatraPlugin.scalatraWithJRebel ++ Seq(
      organization := Organization,
      name := Digiroad2Name,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      parallelExecution in Test := false,
      fork in (Compile,run) := true,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % ScalatraVersion,
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        "org.json4s"   %% "json4s-jackson" % JsonJacksonVersion,
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        "org.scalatra" %% "scalatra-swagger"  % ScalatraSwaggerVersion,
        "org.mockito" % "mockito-core" % MockitoCoreVersion % "test",
        "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % "test",
        "ch.qos.logback" % "logback-classic" % LogbackClassicVersion % "runtime",
        "commons-io" % "commons-io" % CommonsIOVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        "org.apache.httpcomponents" % "httpclient" % HttpClientVersion,
        "org.eclipse.jetty" % "jetty-webapp" % JettyVersion % "container;compile",
        "org.eclipse.jetty" % "jetty-servlets" % JettyVersion % "container;compile",
        "org.eclipse.jetty" % "jetty-proxy" % JettyVersion % "container;compile",
        "javax.servlet" % "javax.servlet-api" % "4.0.1" % "provided;test" artifacts Artifact("javax.servlet-api", "jar", "jar"),
        "systems.uom" % "systems-common-java8" % "systems-common-java8-0.7.2"
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / "conf" /  env,
      unmanagedResourceDirectories in Test += baseDirectory.value / "conf" /  testEnv
    )
  ) dependsOn(geoJar, oracleJar, viiteJar, commonApiJar, viiteApiJar) aggregate
    (geoJar, oracleJar, viiteJar, commonApiJar, viiteApiJar)

  val assemblySettings: Seq[Def.Setting[_]] = sbtassembly.Plugin.assemblySettings ++ Seq(
    mainClass in assembly := Some("fi.liikennevirasto.digiroad2.ProductionServer"),
    test in assembly := {},
    mergeStrategy in assembly <<= (mergeStrategy in assembly) { old =>
    {
      case x if x.endsWith("about.html") => MergeStrategy.discard
      case x => old(x)
    } } ,
    excludedJars in assembly <<= (fullClasspath in assembly) map { cp =>
      cp filter {_.data.getName == "uom-se-1.0.8.jar"}
    }
  )
}
