import io.gatling.sbt.GatlingPlugin
import org.scalatra.sbt._
import sbt.{Def, _}
import sbt.Keys.{unmanagedResourceDirectories, _}
import sbtassembly.Plugin.{MergeStrategy, PathList}
import sbtassembly.Plugin.AssemblyKeys._

object Digiroad2Build extends Build {
  val Organization = "fi.liikennevirasto"
  val Digiroad2Name = "viite"
  val Version = "0.1.0-SNAPSHOT"

  val ScalaVersion = "2.11.7"
  val ScalatraVersion = "2.6.5" // "2.7.0" requires code changes. // "2.7.1" last scala 2.11 version
  val ScalaTestVersion = "3.2.0-SNAP7" // "3.2.0-SNAP10" (next scala 2.11 version) requires code changes. "object org.scalatest.prop.Configuration$ not found."

  val JodaConvertVersion = "2.2.3" // no dependencies
  val JodaTimeVersion = "2.12.7" // dep on joda-convert // TODO "Note that from Java SE 8 onwards, users are asked to migrate to java.time (JSR-310) - a core part of the JDK which replaces this project." (from https://mvnrepository.com/artifact/joda-time/joda-time)
  val SlickVersion = "3.0.3" // 3.1.x and further requires significant changes in the database code, or library change maybe. // 3.4.x and further requires scala 2.12
  val JodaSlickMapperVersion = "2.2.0" // provides slick 3.1.1, joda-time 2.7, and joda-convert 1.7

  val AkkaVersion = "2.5.32" // 2.6.x and up requires Scala 2.12 or greater
  val ApacheHTTPCoreVersion   = "5.2.4"
  val ApacheHTTPClientVersion = "5.3.1" // depends on httpCore
  val NewRelicApiVersion    = "8.12.0"
  val CommonsIOVersion      = "2.16.1"
  val JsonJacksonVersion    = "3.7.0-M11" // 3.7.0-M12 and up: could not find implicit value for evidence parameter of type org.json4s.AsJsonInput[org.json4s.StreamInput] //  4.0.6 last Scala 2.11 version
  val MockitoCoreVersion    = "4.11.0" // last version working with java8 runtime // 5.0.0 and up requires Java update to Java 11: "java.lang.UnsupportedClassVersionError: org/mockito/Mockito has been compiled by a more recent version of the Java Runtime (class file version 55.0), this version of the Java Runtime only recognizes class file versions up to 52.0"
  val LogbackClassicVersion = "1.3.14" // Java EE version. 1.4.x requires Jakarta instead of JavaEE
  val JettyVersion = "9.2.15.v20160210"
  val TestOutputOptions = Tests.Argument(TestFrameworks.ScalaTest, "-oNCXELOPQRMI") // List only problems, and their summaries. Set suitable logback level to get the effect.
  val AwsSdkVersion       = "2.26.7" // "2.17.148"
  val GeoToolsVersion     = "28.5" // "29.x" fails api/viite/roadaddress with Internal Server Error // available "31.1"
  val GeoToolsIFVersion   = GeoToolsVersion // Differs from GeoToolsVersion after "29.2"
  val JavaxServletVersion = "4.0.1"
  val JgridshiftVersion   = "1.0"
  val JtsCoreVersion      = "1.19.0"

  val jodaConvert    = "org.joda"             %  "joda-convert"  % JodaConvertVersion
  val jodaTime       = "joda-time"            %  "joda-time"     % JodaTimeVersion
  val akkaActor      = "com.typesafe.akka"    %% "akka-actor"    % AkkaVersion
  val akkaTestkit    = "com.typesafe.akka"    %% "akka-testkit"  % AkkaVersion
  val httpCore   = "org.apache.httpcomponents.core5"   % "httpcore5"   % ApacheHTTPCoreVersion
  val httpClient = "org.apache.httpcomponents.client5" % "httpclient5" % ApacheHTTPClientVersion
  val jsonJackson    = "org.json4s"         %% "json4s-jackson"  % JsonJacksonVersion
  val jsonNative     = "org.json4s"         %% "json4s-native"   % JsonJacksonVersion
  val mockitoCore    = "org.mockito"        %  "mockito-core"    % MockitoCoreVersion
  val logbackClassic = "ch.qos.logback"     % "logback-classic"  % LogbackClassicVersion

  val geoToolsDependencies: Seq[ModuleID] = Seq(
    "org.geotools" % "gt-graph" % GeoToolsVersion,
    "org.geotools" % "gt-main" % GeoToolsVersion,
    "org.geotools" % "gt-referencing" % GeoToolsVersion,
    "org.geotools" % "gt-metadata" % GeoToolsVersion,
    "org.geotools" % "gt-opengis" % GeoToolsIFVersion,
    "jgridshift" % "jgridshift" % JgridshiftVersion
  )

  // Common settings for all projects
  val projectSettings: Seq[Def.Setting[_]] = Seq(
    organization := Organization,
    version := Version,
    scalaVersion := ScalaVersion,
    scalacOptions ++= Seq("-unchecked", "-feature"),
    testOptions in Test += TestOutputOptions
  ) ++ CodeArtifactSettings.settings // chooses the correct resolvers and credentials based on the CODE_ARTIFACT_AUTH_TOKEN environment variable

  val BaseProjectName = "base"
  lazy val baseJar = Project(
    BaseProjectName,
    file(s"viite-backend/$BaseProjectName"),
    settings = Defaults.coreDefaultSettings ++ projectSettings ++ Seq(
      name := BaseProjectName,
      libraryDependencies ++= Seq(
        jodaTime, jodaConvert,
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test"
      )
    )
  )

  val GeoProjectName = "geo"
  lazy val geoJar = Project(
    GeoProjectName,
    file(s"viite-backend/$GeoProjectName"),
    settings = Defaults.coreDefaultSettings ++ projectSettings ++ Seq(
      name := GeoProjectName,
      libraryDependencies ++= Seq(
        jodaConvert,
        jodaTime,
        akkaActor,
        "org.locationtech.jts" % "jts-core" % "1.19.0",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test"
      ) ++ CodeArtifactSettings.withFallbackUrls(geoToolsDependencies)
    )
  ) dependsOn(baseJar)

  val DBProjectName = "database"
  lazy val DBJar = Project (
    DBProjectName,
    file(s"viite-backend/$DBProjectName"),
    settings = Defaults.coreDefaultSettings ++ projectSettings ++ Seq(
      name := DBProjectName,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.apache.commons" % "commons-lang3" % "3.14.0",
        "commons-codec"      % "commons-codec" % "1.17.0",
        "com.jolbox"         % "bonecp"        % "0.8.0.RELEASE",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "com.typesafe.slick" %% "slick"        % SlickVersion,
        jsonJackson,
        jodaConvert,
        jodaTime,
        "com.github.tototoshi" %% "slick-joda-mapper" % JodaSlickMapperVersion,
        "com.github.tototoshi" %% "scala-csv"         % "1.3.10",
        httpCore,
        httpClient,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        mockitoCore % "test",
        "org.flywaydb"   % "flyway-core"   % "3.2.1", // 4.0 requires init code changes. Versions "10.0.0"+ available
        "org.postgresql" % "postgresql"    % "42.7.3",
        "net.postgis" % "postgis-geometry" % "2023.1.0",
        "net.postgis" % "postgis-jdbc"     % "2023.1.0" // dep postgresql, and from 2.5.0 and up: postgis-geometry
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf"
    )
  ) dependsOn (baseJar, geoJar)

  val ViiteMainProjectName = "viite-main"
  lazy val viiteJar = Project (
    ViiteMainProjectName,
    file(s"viite-backend/$ViiteMainProjectName"),
    settings = Defaults.coreDefaultSettings ++ projectSettings ++ Seq(
      name := ViiteMainProjectName,
      parallelExecution in Test := false,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % ScalatraVersion,
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        jsonJackson,
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion % "test",
        mockitoCore    % "test",
        akkaTestkit    % "test",
        logbackClassic % "runtime",
        "commons-io" % "commons-io" % CommonsIOVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        httpCore,
        httpClient,
        "org.scalatra" %% "scalatra-swagger"  % ScalatraVersion,
        "com.github.nscala-time" %% "nscala-time" % "2.32.0",
        "software.amazon.awssdk" % "s3"  % AwsSdkVersion,
        "software.amazon.awssdk" % "sso" % AwsSdkVersion
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf"
    )
  ) dependsOn(baseJar, geoJar, DBJar % "compile->compile;test->test")

  val ApiCommonProjectName = "api-common"
  lazy val apiCommonJar = Project (
    ApiCommonProjectName,
    file(s"viite-backend/$ApiCommonProjectName"),
    settings = Defaults.coreDefaultSettings ++ projectSettings ++ Seq(
      name := ApiCommonProjectName,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        akkaActor,
        httpCore,
        httpClient,
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "compile, test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        "org.scalatra" %% "scalatra-swagger" % ScalatraVersion,
        mockitoCore % "test",
        jodaConvert,
        jodaTime,
        "org.eclipse.jetty" % "jetty-webapp" % JettyVersion % "compile",
        "org.eclipse.jetty" % "jetty-servlets" % JettyVersion % "compile",
        "org.eclipse.jetty" % "jetty-proxy" % JettyVersion % "compile",
        "org.eclipse.jetty" % "jetty-jmx" % JettyVersion % "compile",
        "javax.servlet"     % "javax.servlet-api" % JavaxServletVersion % "provided"
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf"
    )
  ) dependsOn(baseJar, geoJar, DBJar, viiteJar)

  val ApiProjectName = "api"
  lazy val ApiJar = Project (
    ApiProjectName,
    file(s"viite-backend/$ApiProjectName"),
    settings = Defaults.coreDefaultSettings ++ projectSettings ++ Seq(
      name := ApiProjectName,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % ScalatraVersion,
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        jsonJackson, jsonNative,
        "org.scala-lang.modules"   %% "scala-parser-combinators" % "1.1.2",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        mockitoCore    % "test",
        akkaTestkit    % "test",
        logbackClassic % "runtime",
        "commons-io" % "commons-io" % CommonsIOVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        httpCore,
        httpClient,
        "org.scalatra" %% "scalatra-swagger" % ScalatraVersion
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf"
    )
  ) dependsOn(baseJar, geoJar, DBJar, viiteJar % "test->test", apiCommonJar % "compile->compile;test->test")

  lazy val warProject = Project (
    Digiroad2Name,
    file("."),
    settings = Defaults.coreDefaultSettings ++ projectSettings
      ++ assemblySettings
      ++ net.virtualvoid.sbt.graph.Plugin.graphSettings
      ++ ScalatraPlugin.scalatraWithJRebel ++ Seq(
      name := Digiroad2Name,
      parallelExecution in Test := false,
      fork in (Compile,run) := true,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % ScalatraVersion,
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        jsonJackson,
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        "org.scalatra" %% "scalatra-swagger"  % ScalatraVersion,
        mockitoCore % "test",
        akkaTestkit % "test",
        logbackClassic % "runtime",
        "commons-io" % "commons-io" % CommonsIOVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        httpCore,
        httpClient,
        "org.eclipse.jetty" % "jetty-webapp" % JettyVersion % "container;compile",
        "org.eclipse.jetty" % "jetty-servlets" % JettyVersion % "container;compile",
        "org.eclipse.jetty" % "jetty-proxy" % JettyVersion % "container;compile",
        "javax.servlet"     % "javax.servlet-api" % JavaxServletVersion % "provided"
      )
    )
  ) dependsOn(baseJar, geoJar, DBJar, viiteJar, apiCommonJar, ApiJar) aggregate
    (baseJar, geoJar, DBJar, viiteJar, apiCommonJar, ApiJar)

  lazy val gatling = project.in(file(s"viite-integration-test/digiroad2-gatling"))
    .enablePlugins(GatlingPlugin)
    .settings(scalaVersion := ScalaVersion)
    .settings(libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "3.11.3" % "test",
      "io.gatling"            % "gatling-test-framework"    % "3.11.3" % "test"
    ))

  val assemblySettings: Seq[Def.Setting[_]] = sbtassembly.Plugin.assemblySettings ++ Seq(
    mainClass in assembly := Some("fi.liikennevirasto.digiroad2.ProductionServer"),
    test in assembly := {},
    mergeStrategy in assembly <<= (mergeStrategy in assembly) { old =>
    {
      case x if x.endsWith("about.html") => MergeStrategy.discard
      case x if x.endsWith("env.properties") => MergeStrategy.discard
      case x if x.endsWith("mime.types") => MergeStrategy.last
      case x if x.endsWith("public-suffix-list.txt") => MergeStrategy.first
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.discard
      case PathList("META-INF", "maven", "com.fasterxml.jackson.core", "jackson-core", _*) => MergeStrategy.discard
      case x if x.endsWith("module-info.class") => MergeStrategy.discard // for logback, and slf4j-api
      case x => old(x)
    } }
  )
}
