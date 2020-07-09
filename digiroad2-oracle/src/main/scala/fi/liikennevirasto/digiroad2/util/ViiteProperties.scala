package fi.liikennevirasto.digiroad2.util

import java.util.Properties

class ViiteProperties {

  private lazy val revisionProperties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/revision.properties"))
    props
  }

  lazy val userProvider: String = scala.util.Properties.envOrElse("userProvider", null)
  lazy val municipalityProvider: String = scala.util.Properties.envOrElse("municipalityProvider", null)
  lazy val eventBus: String = scala.util.Properties.envOrElse("eventBus", null)
  lazy val useVVHGeometry: String = scala.util.Properties.envOrElse("useVVHGeometry", null)
  lazy val vvhServiceHost: String = scala.util.Properties.envOrElse("vvhServiceHost", null)
  lazy val vvhRestApiEndPoint: String = scala.util.Properties.envOrElse("vvhRestApiEndPoint", null)
  lazy val vvhRoadlinkFrozen: Boolean = scala.util.Properties.envOrElse("vvhRoadlink.frozen", "false").toBoolean
  lazy val vkmUrl: String = scala.util.Properties.envOrElse("vkmUrl", null)
  lazy val tierekisteriViiteRestApiEndPoint: String = scala.util.Properties.envOrElse("tierekisteriViiteRestApiEndPoint", "http://localhost:8080/api/tierekisteri/")
  lazy val tierekisteriEnabled: Boolean = scala.util.Properties.envOrElse("tierekisteri.enabled", "false").toBoolean
  lazy val cacheDirectory: String = scala.util.Properties.envOrElse("cache.directory", null)
  lazy val httpProxySet: Boolean = scala.util.Properties.envOrElse("http.proxySet", "false").toBoolean
  lazy val httpProxyHost: String = scala.util.Properties.envOrElse("http.proxyHost", null)
  lazy val httpNonProxyHosts: String = scala.util.Properties.envOrElse("http.nonProxyHosts", "")
  lazy val importOnlyCurrent: Boolean = scala.util.Properties.envOrElse("importOnlyCurrent", "false").toBoolean
  lazy val authenticationTestMode: Boolean = scala.util.Properties.envOrElse("authenticationTestMode", "false").toBoolean
  lazy val authenticationTestUser: String = scala.util.Properties.envOrElse("authenticationTestUser", null)
  lazy val bonecpJdbcUrl: String = scala.util.Properties.envOrElse("bonecp.jdbcUrl", null)
  lazy val bonecpUsername: String = scala.util.Properties.envOrElse("bonecp.username", null)
  lazy val bonecpPassword: String = scala.util.Properties.envOrElse("bonecp.password", null)
  lazy val conversionBonecpJdbcUrl: String = scala.util.Properties.envOrElse("conversion.bonecp.jdbcUrl", null)
  lazy val conversionBonecpUsername: String = scala.util.Properties.envOrElse("conversion.bonecp.username", null)
  lazy val conversionBonecpPassword: String = scala.util.Properties.envOrElse("conversion.bonecp.password", null)
  lazy val authenticationBasicUsername: String = scala.util.Properties.envOrElse("authentication.basic.username", null)
  lazy val authenticationBasicPassword: String = scala.util.Properties.envOrElse("authentication.basic.password", null)
  lazy val authenticationServiceRoadBasicUsername: String = scala.util.Properties.envOrElse("authentication.serviceRoad.basic.username", null)
  lazy val authenticationServiceRoadBasicPassword: String = scala.util.Properties.envOrElse("authentication.serviceRoad.basic.password", null)
  lazy val authenticationMunicipalityBasicUsername: String = scala.util.Properties.envOrElse("authentication.municipality.basic.username", null)
  lazy val authenticationMunicipalityBasicPassword: String = scala.util.Properties.envOrElse("authentication.municipality.basic.password", null)
  lazy val viitetierekisteriUsername: String = scala.util.Properties.envOrElse("viiteTierekisteri.username", null)
  lazy val viitetierekisteriPassword: String = scala.util.Properties.envOrElse("viiteTierekisteri.password", null)
  lazy val latestDeploy: String = revisionProperties.getProperty("digiroad2.latestDeploy")
  lazy val env: String = System.getProperty("env")

  lazy val bonecpProperties: Properties = {
    val props = new Properties()
    try {
      props.setProperty("bonecp.jdbcUrl", bonecpJdbcUrl)
      props.setProperty("bonecp.username", bonecpUsername)
      props.setProperty("bonecp.password", bonecpPassword)
    } catch {
      case e: Exception => throw new RuntimeException("Can't load bonecp properties for env: " + System.getProperty("env"), e)
    }
    props
  }

  lazy val conversionBonecpProperties: Properties = {
    val props = new Properties()
    try {
      props.setProperty("bonecp.jdbcUrl", conversionBonecpJdbcUrl)
      props.setProperty("bonecp.username", conversionBonecpUsername)
      props.setProperty("bonecp.password", conversionBonecpPassword)
    } catch {
      case e: Exception => throw new RuntimeException("Can't load conversion bonecp properties for env: " + System.getProperty("env"), e)
    }
    props
  }

  def getAuthenticationBasicUsername(baseAuth: String = ""): String = {
    scala.util.Properties.envOrElse("authentication." + baseAuth + (if (baseAuth.isEmpty) "" else ".") + "basic.username", null)
  }

  def getAuthenticationBasicPassword(baseAuth: String = ""): String = {
    scala.util.Properties.envOrElse("authentication." + baseAuth + (if (baseAuth.isEmpty) "" else ".") + "basic.password", null)
  }

}

object ViiteProperties extends ViiteProperties {
  new ViiteProperties
}
