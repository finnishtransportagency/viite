import io.gatling.sbt.GatlingPlugin
import org.scalatra.sbt._
import sbt.{Def, _}
import sbt.Keys.{unmanagedResourceDirectories, _}
import sbtassembly.Plugin.{MergeStrategy, PathList}
import sbtassembly.Plugin.AssemblyKeys._

object Digiroad2Build extends Build {
  val Organization = "fi.liikennevirasto"
  val Digiroad2Name = "viite"
  val Digiroad2GeoName = "digiroad2-geo"
  val Version = "0.1.0-SNAPSHOT"

  val ScalaVersion = "2.11.7"
  val ScalatraVersion = "2.6.5"
  val ScalaTestVersion = "3.2.0-SNAP7"

  val JodaConvertVersion = "2.2.3" // no dependencies
  val JodaTimeVersion = "2.12.5" // dep on joda-convert // TODO "Note that from Java SE 8 onwards, users are asked to migrate to java.time (JSR-310) - a core part of the JDK which replaces this project." (from https://mvnrepository.com/artifact/joda-time/joda-time)
  val SlickVersion = "3.0.0"
  val JodaSlickMapperVersion = "2.2.0" // provides slick 3.1.1, joda-time 2.7, and joda-convert 1.7

  val AkkaVersion = "2.5.32" // 2.6.x and up requires Scala 2.12 or greater
  val HttpClientVersion = "4.5.14"
  val NewRelicApiVersion = "3.1.1"
  val CommonsIOVersion = "2.6"
  val JsonJacksonVersion = "3.7.0-M7" // with "3.7.0-M8" test does not compile
  val MockitoCoreVersion = "4.11.0" // 5.0.0 and up requires Java update to Java 11: "java.lang.UnsupportedClassVersionError: org/mockito/Mockito has been compiled by a more recent version of the Java Runtime (class file version 55.0), this version of the Java Runtime only recognizes class file versions up to 52.0"
  val LogbackClassicVersion = "1.3.6" // Java EE version. 1.4.x requires Jakarta instead of JavaEE
  val JettyVersion = "9.2.15.v20160210"
  val TestOutputOptions = Tests.Argument(TestFrameworks.ScalaTest, "-oNCXELOPQRMI") // List only problems, and their summaries. Set suitable logback level to get the effect.
  val AwsSdkVersion = "2.17.148"
  val GeoToolsVersion = "27.2"

  val jodaConvert    = "org.joda"             %  "joda-convert"  % JodaConvertVersion
  val jodaTime       = "joda-time"            %  "joda-time"     % JodaTimeVersion
  val akkaActor      = "com.typesafe.akka"    %% "akka-actor"    % AkkaVersion
  val akkaTestkit    = "com.typesafe.akka"    %% "akka-testkit"  % AkkaVersion
  val httpClient = "org.apache.httpcomponents" %  "httpclient"   % HttpClientVersion //dep on commons-codec & httpcomponents
  val jsonJackson    = "org.json4s"         %% "json4s-jackson"  % JsonJacksonVersion
  val jsonNative     = "org.json4s"         %% "json4s-native"   % JsonJacksonVersion
  val mockitoCore    = "org.mockito"        %  "mockito-core"    % MockitoCoreVersion
  val logbackClassic = "ch.qos.logback"     % "logback-classic"  % LogbackClassicVersion

  // Get build id to check if executing in aws environment.
  val awsBuildId: String = scala.util.Properties.envOrElse("CODEBUILD_BUILD_ID", null)

  lazy val geoJar = Project (
    Digiroad2GeoName,
    file(Digiroad2GeoName),
    settings = Defaults.coreDefaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2GeoName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      testOptions in Test += TestOutputOptions,
      libraryDependencies ++= Seq(
        jodaConvert,
        jodaTime,
        akkaActor,
        "javax.media" % "jai_core" % "1.1.3" from "https://repo.osgeo.org/repository/release/javax/media/jai_core/1.1.3/jai_core-1.1.3.jar",
        "org.geotools" % "gt-graph" % GeoToolsVersion from s"https://repo.osgeo.org/repository/release/org/geotools/gt-graph/$GeoToolsVersion/gt-graph-$GeoToolsVersion.jar",
        "org.geotools" % "gt-main" % GeoToolsVersion from s"https://repo.osgeo.org/repository/release/org/geotools/gt-main/$GeoToolsVersion/gt-main-$GeoToolsVersion.jar",
        "org.geotools" % "gt-referencing" % GeoToolsVersion from s"https://repo.osgeo.org/repository/release/org/geotools/gt-referencing/$GeoToolsVersion/gt-referencing-$GeoToolsVersion.jar",
        "org.geotools" % "gt-metadata" % GeoToolsVersion from s"https://repo.osgeo.org/repository/release/org/geotools/gt-metadata/$GeoToolsVersion/gt-metadata-$GeoToolsVersion.jar",
        "org.geotools" % "gt-opengis" % GeoToolsVersion from s"https://repo.osgeo.org/repository/release/org/geotools/gt-opengis/$GeoToolsVersion/gt-opengis-$GeoToolsVersion.jar",
        "jgridshift" % "jgridshift" % "1.0" from "https://repo.osgeo.org/repository/release/jgridshift/jgridshift/1.0/jgridshift-1.0.jar",
        "org.locationtech.jts" % "jts-core" % "1.18.2" from "https://repo1.maven.org/maven2/org/locationtech/jts/jts-core/1.18.2/jts-core-1.18.2.jar",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test"
      )
    )
  )
    

  val viiteDatabaseProjectName = "viite-DB"
  lazy val DBJar = Project (
    viiteDatabaseProjectName,
    file(viiteDatabaseProjectName),
    settings = Defaults.coreDefaultSettings ++ Seq(
      organization := Organization,
      name := viiteDatabaseProjectName,
      version := Version,
      scalaVersion := ScalaVersion,
      //      resolvers ++= Seq(Classpaths.typesafeReleases,
      //        "maven-public" at "http://livibuild04.vally.local/nexus/repository/maven-public/",
      //        "ivy-public" at "http://livibuild04.vally.local/nexus/repository/ivy-public/"),
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      testOptions in Test += TestOutputOptions,
      libraryDependencies ++= Seq(
        "org.apache.commons" % "commons-lang3" % "3.2",
        "commons-codec" % "commons-codec" % "1.15",
        "com.jolbox" % "bonecp" % "0.8.0.RELEASE",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "com.typesafe.slick" %% "slick" % SlickVersion,
        jsonJackson,
        jodaConvert,
        jodaTime,
        "com.github.tototoshi" %% "slick-joda-mapper" % JodaSlickMapperVersion,
        "com.github.tototoshi" %% "scala-csv" % "1.3.5",
        httpClient,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        mockitoCore % "test",
        "com.googlecode.flyway" % "flyway-core" % "2.3.1",
        "org.postgresql" % "postgresql" % "42.2.27",
        "net.postgis" % "postgis-geometry" % "2021.1.0",
        "net.postgis" % "postgis-jdbc" % "2021.1.0" // dep postgresql, and from 2.5.0 and up: postgis-geometry
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf"
    )
  ) dependsOn geoJar

  val Digiroad2ViiteName = "digiroad2-viite"
  lazy val viiteJar = Project (
    Digiroad2ViiteName,
    file(Digiroad2ViiteName),
    settings = Defaults.coreDefaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2ViiteName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      parallelExecution in Test := false,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      testOptions in Test += TestOutputOptions,
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
        httpClient,
        "org.scalatra" %% "scalatra-swagger"  % ScalatraVersion,
        "com.github.nscala-time" %% "nscala-time" % "2.32.0",
        "software.amazon.awssdk" % "s3" % AwsSdkVersion,
        "software.amazon.awssdk" % "sso" % AwsSdkVersion
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf"
    )
  ) dependsOn(geoJar, DBJar % "compile->compile;test->test")

  val viiteCommonApiProjectName = "viite-api-common"
  lazy val commonApiJar = Project (
    viiteCommonApiProjectName,
    file(viiteCommonApiProjectName),
    settings = Defaults.coreDefaultSettings ++ Seq(
      organization := Organization,
      name := viiteCommonApiProjectName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      //      parallelExecution in Test := false,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      testOptions in Test += TestOutputOptions,
      libraryDependencies ++= Seq(
        akkaActor,
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
        "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "provided;test" artifacts Artifact("javax.servlet", "jar", "jar")
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf"
    )
  ) dependsOn(geoJar, DBJar, viiteJar)

  val Digiroad2ViiteApiName = "digiroad2-api-viite"
  lazy val viiteApiJar = Project (
    Digiroad2ViiteApiName,
    file(Digiroad2ViiteApiName),
    settings = Defaults.coreDefaultSettings ++ Seq(
      organization := Organization,
      name := Digiroad2ViiteApiName,
      version := Version,
      scalaVersion := ScalaVersion,
      resolvers += Classpaths.typesafeReleases,
      scalacOptions ++= Seq("-unchecked", "-feature"),
      //      parallelExecution in Test := false,
      testOptions in Test ++= (
        if (System.getProperty("digiroad2.nodatabase", "false") == "true") Seq(Tests.Argument("-l"), Tests.Argument("db")) else Seq()),
      testOptions in Test += TestOutputOptions,
      libraryDependencies ++= Seq(
        "org.scalatra" %% "scalatra" % ScalatraVersion,
        "org.scalatra" %% "scalatra-json" % ScalatraVersion,
        jsonJackson, jsonNative,
        "org.scala-lang.modules"   %% "scala-parser-combinators" % "1.1.0",
        "org.scalatest" % "scalatest_2.11" % ScalaTestVersion % "test",
        "org.scalatra" %% "scalatra-scalatest" % ScalatraVersion % "test",
        "org.scalatra" %% "scalatra-auth" % ScalatraVersion,
        mockitoCore    % "test",
        akkaTestkit    % "test",
        logbackClassic % "runtime",
        "commons-io" % "commons-io" % CommonsIOVersion,
        "com.newrelic.agent.java" % "newrelic-api" % NewRelicApiVersion,
        httpClient,
        "org.scalatra" %% "scalatra-swagger"  % ScalatraVersion
      ),
      unmanagedResourceDirectories in Compile += baseDirectory.value / ".." / "conf"
    )
  ) dependsOn(geoJar, DBJar, viiteJar % "test->test", commonApiJar % "compile->compile;test->test")

  lazy val warProject = Project (
    Digiroad2Name,
    file("."),
    settings = Defaults.coreDefaultSettings
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
      testOptions in Test += TestOutputOptions,
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
        httpClient,
        "org.eclipse.jetty" % "jetty-webapp" % JettyVersion % "container;compile",
        "org.eclipse.jetty" % "jetty-servlets" % JettyVersion % "container;compile",
        "org.eclipse.jetty" % "jetty-proxy" % JettyVersion % "container;compile",
        "org.eclipse.jetty.orbit" % "javax.servlet" % "3.0.0.v201112011016" % "container;provided;test" artifacts (Artifact("javax.servlet", "jar", "jar"))
      )
    )
  ) dependsOn(geoJar, DBJar, viiteJar, commonApiJar, viiteApiJar) aggregate
    (geoJar, DBJar, viiteJar, commonApiJar, viiteApiJar)

  lazy val gatling = project.in(file("digiroad2-gatling"))
    .enablePlugins(GatlingPlugin)
    .settings(scalaVersion := ScalaVersion)
    .settings(libraryDependencies ++= Seq(
      "io.gatling.highcharts" % "gatling-charts-highcharts" % "3.9.3" % "test",
      "io.gatling" % "gatling-test-framework" % "3.9.3" % "test"))

  val assemblySettings: Seq[Def.Setting[_]] = sbtassembly.Plugin.assemblySettings ++ Seq(
    mainClass in assembly := Some("fi.liikennevirasto.digiroad2.ProductionServer"),
    test in assembly := {},
    mergeStrategy in assembly <<= (mergeStrategy in assembly) { old =>
    {
      case x if x.endsWith("about.html") => MergeStrategy.discard
      case x if x.endsWith("env.properties") => MergeStrategy.discard
      case x if x.endsWith("mime.types") => MergeStrategy.last
      case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.discard
      case PathList("META-INF", "maven", "com.fasterxml.jackson.core", "jackson-core", _*) => MergeStrategy.discard
      case x if x.endsWith("module-info.class") => MergeStrategy.discard // for logback, and slf4j-api
      case x => old(x)
    } }
  )
}
