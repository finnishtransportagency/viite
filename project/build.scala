import io.gatling.sbt.GatlingPlugin
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

  val ScalaVersion = "2.11.7"
  val ScalatraVersion = "2.6.3"
  val ScalaTestVersion = "3.2.0-SNAP7"
  val JodaConvertVersion = "2.0.1"
  val JodaTimeVersion = "2.9.9"
  val AkkaVersion = "2.3.16"
  val HttpClientVersion = "4.5.5"
  val NewRelicApiVersion = "3.1.1"
  val CommonsIOVersion = "2.6"
  val JsonJacksonVersion = "3.5.3"
  val MockitoCoreVersion = "2.18.3"
  val LogbackClassicVersion = "1.2.3"
  val JettyVersion = "9.2.15.v20160210"

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
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      libraryDependencies ++= Seq(
        "org.joda" % "joda-convert" % JodaConvertVersion,
        "joda-time" % "joda-time" % JodaTimeVersion,
        "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
        "javax.media" % "jai_core" % "1.1.3" from "https://repo.osgeo.org/repository/release/javax/media/jai_core/1.1.3/jai_core-1.1.3.jar",
        "org.geotools" % "gt-graph" % "19.0" from "http://livibuild04.vally.local/nexus/repository/maven-public/org/geotools/gt-graph/19.0/gt-graph-19.0.jar",
        "org.geotools" % "gt-main" % "19.0" from "http://livibuild04.vally.local/nexus/repository/maven-public/org/geotools/gt-main/19.0/gt-main-19.0.jar",
        "org.geotools" % "gt-api" % "19.0" from "http://livibuild04.vally.local/nexus/repository/maven-public/org/geotools/gt-api/19.0/gt-api-19.0.jar",
        "org.geotools" % "gt-referencing" % "19.0" from "http://livibuild04.vally.local/nexus/repository/maven-public/org/geotools/gt-referencing/19.0/gt-referencing-19.0.jar",
        "org.geotools" % "gt-metadata" % "19.0" from "http://livibuild04.vally.local/nexus/repository/maven-public/org/geotools/gt-metadata/19.0/gt-metadata-19.0.jar",
        "org.geotools" % "gt-opengis" % "19.0" from "http://livibuild04.vally.local/nexus/repository/maven-public/org/geotools/gt-opengis/19.0/gt-opengis-19.0.jar",
        "jgridshift" % "jgridshift" % "1.0" from "https://repo.osgeo.org/repository/release/jgridshift/jgridshift/1.0/jgridshift-1.0.jar",
        "com.vividsolutions" % "jts-core" % "1.14.0" from "http://livibuild04.vally.local/nexus/repository/maven-public/com/vividsolutions/jts-core/1.14.0/jts-core-1.14.0.jar",
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
      //      resolvers ++= Seq(Classpaths.typesafeReleases,
      //        "maven-public" at "http://livibuild04.vally.local/nexus/repository/maven-public/",
      //        "ivy-public" at "http://livibuild04.vally.local/nexus/repository/ivy-public/"),
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.apache.commons" % "commons-lang3" % "3.2",
        "commons-codec" % "commons-codec" % "1.9",
        "com.jolbox" % "bonecp" % "0.8.0.RELEASE",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "com.typesafe.slick" %% "slick" % "3.0.0",
        "org.json4s"   %% "json4s-jackson" % JsonJacksonVersion,
        "org.joda" % "joda-convert" % JodaConvertVersion,
        "joda-time" % "joda-time" % JodaTimeVersion,
        "com.github.tototoshi" %% "slick-joda-mapper" % "2.2.0",
        "com.github.tototoshi" %% "scala-csv" % "1.3.5",
        "org.apache.httpcomponents" % "httpclient" % HttpClientVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        "org.mockito" % "mockito-core" % MockitoCoreVersion % "test",
        "com.googlecode.flyway" % "flyway-core" % "2.3.1" % "test",
        //        "com.oracle" % "ojdbc6" % "11.2.0.3.0",
        //        "com.oracle" % "sdoapi" % "11.2.0",
        //        "com.oracle" % "sdoutl" % "11.2.0"
        "com.oracle" % "ojdbc6" % "11.2.0.3.0" from "http://livibuild04.vally.local/nexus/repository/maven-public/com/oracle/ojdbc6/11.2.0.3.0/ojdbc6-11.2.0.3.0.jar",
        "com.oracle" % "sdoapi" % "11.2.0" from "http://livibuild04.vally.local/nexus/repository/maven-public/com/oracle/sdoapi/11.2.0/sdoapi-11.2.0.jar",
        "com.oracle" % "sdoutl" % "11.2.0" from "http://livibuild04.vally.local/nexus/repository/maven-public/com/oracle/sdoutl/11.2.0/sdoutl-11.2.0.jar"
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
        "org.scalatra" %% "scalatra-swagger"  % ScalatraVersion,
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
        "org.scalatra" %% "scalatra-swagger" % ScalatraVersion,
        "org.mockito" % "mockito-core" % MockitoCoreVersion % "test",
        "org.joda" % "joda-convert" % JodaConvertVersion,
        "joda-time" % "joda-time" % JodaTimeVersion,
        "org.eclipse.jetty" % "jetty-webapp" % JettyVersion % "compile",
        "org.eclipse.jetty" % "jetty-servlets" % JettyVersion % "compile",
        "org.eclipse.jetty" % "jetty-proxy" % JettyVersion % "compile",
        "org.eclipse.jetty" % "jetty-jmx" % JettyVersion % "compile",
        "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "provided;test" artifacts Artifact("javax.servlet", "jar", "jar")
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
        "org.json4s"   %% "json4s-native" % "3.5.2",
        "org.scala-lang.modules"   %% "scala-parser-combinators" % "1.1.0",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        "org.mockito" % "mockito-core" % MockitoCoreVersion % "test",
        "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % "test",
        "ch.qos.logback" % "logback-classic" % LogbackClassicVersion % "runtime",
        "commons-io" % "commons-io" % CommonsIOVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        "org.apache.httpcomponents" % "httpclient" % "4.3.3",
        "org.scalatra" %% "scalatra-swagger"  % ScalatraVersion
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
        "org.scalatra" %% "scalatra-swagger"  % ScalatraVersion,
        "org.mockito" % "mockito-core" % MockitoCoreVersion % "test",
        "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % "test",
        "ch.qos.logback" % "logback-classic" % LogbackClassicVersion % "runtime",
        "commons-io" % "commons-io" % CommonsIOVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        "org.apache.httpcomponents" % "httpclient" % "4.3.3",
        "org.eclipse.jetty" % "jetty-webapp" % JettyVersion % "container;compile",
        "org.eclipse.jetty" % "jetty-servlets" % JettyVersion % "container;compile",
        "org.eclipse.jetty" % "jetty-proxy" % JettyVersion % "container;compile",
        "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "container;provided;test" artifacts (Artifact("javax.servlet", "jar", "jar"))
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / "conf" /  env,
      unmanagedResourceDirectories in Test += baseDirectory.value / "conf" /  testEnv
    )
  ) dependsOn(geoJar, oracleJar, viiteJar, commonApiJar, viiteApiJar) aggregate
    (geoJar, oracleJar, viiteJar, commonApiJar, viiteApiJar)

  lazy val gatling = project.in(file("digiroad2-gatling"))
    .enablePlugins(GatlingPlugin)
    .settings(scalaVersion := ScalaVersion)
    .settings(libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "2.1.7" % "test",
      "io.gatling" % "gatling-test-framework" % "2.1.7" % "test"))

  val assemblySettings: Seq[Def.Setting[_]] = sbtassembly.Plugin.assemblySettings ++ Seq(
    mainClass in assembly := Some("fi.liikennevirasto.digiroad2.ProductionServer"),
    test in assembly := {},
    mergeStrategy in assembly <<= (mergeStrategy in assembly) { old =>
    {
      case x if x.endsWith("about.html") => MergeStrategy.discard
      case x => old(x)
    } }
  )
}
