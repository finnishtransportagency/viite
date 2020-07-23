package fi.liikennevirasto.digiroad2.util

import java.util.Properties

import org.slf4j.LoggerFactory

trait ViiteProperties {
  val userProvider: String
  val municipalityProvider: String
  val eventBus: String
  val useVVHGeometry: String
  val vvhServiceHost: String
  val vvhRestApiEndPoint: String
  val vvhRoadlinkFrozen: Boolean
  val vkmUrl: String
  val tierekisteriViiteRestApiEndPoint: String
  val tierekisteriEnabled: Boolean
  val cacheDirectory: String
  val httpProxySet: Boolean
  val httpProxyHost: String
  val httpNonProxyHosts: String
  val importOnlyCurrent: Boolean
  val authenticationTestMode: Boolean
  val authenticationTestUser: String
  val bonecpJdbcUrl: String
  val bonecpUsername: String
  val bonecpPassword: String
  val conversionBonecpJdbcUrl: String
  val conversionBonecpUsername: String
  val conversionBonecpPassword: String
  val authenticationBasicUsername: String
  val authenticationBasicPassword: String
  val authenticationServiceRoadBasicUsername: String
  val authenticationServiceRoadBasicPassword: String
  val authenticationMunicipalityBasicUsername: String
  val authenticationMunicipalityBasicPassword: String
  val viitetierekisteriUsername: String
  val viitetierekisteriPassword: String
  val latestDeploy: String
  val env: String

  val bonecpProperties: Properties
  val conversionBonecpProperties: Properties

  def getAuthenticationBasicUsername(baseAuth: String = ""): String
  def getAuthenticationBasicPassword(baseAuth: String = ""): String
}

class ViitePropertiesFromEnv extends ViiteProperties {

  private lazy val revisionProperties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/revision.properties"))
    props
  }

  val userProvider: String = scala.util.Properties.envOrElse("userProvider", null)
  val municipalityProvider: String = scala.util.Properties.envOrElse("municipalityProvider", null)
  val eventBus: String = scala.util.Properties.envOrElse("eventBus", null)
  val useVVHGeometry: String = scala.util.Properties.envOrElse("useVVHGeometry", null)
  val vvhServiceHost: String = scala.util.Properties.envOrElse("vvhServiceHost", null)
  val vvhRestApiEndPoint: String = scala.util.Properties.envOrElse("vvhRestApiEndPoint", null)
  val vvhRoadlinkFrozen: Boolean = scala.util.Properties.envOrElse("vvhRoadlink.frozen", "false").toBoolean
  val vkmUrl: String = scala.util.Properties.envOrElse("vkmUrl", null)
  val tierekisteriViiteRestApiEndPoint: String = scala.util.Properties.envOrElse("tierekisteriViiteRestApiEndPoint", "http://localhost:8080/api/tierekisteri/")
  val tierekisteriEnabled: Boolean = scala.util.Properties.envOrElse("tierekisteri.enabled", "false").toBoolean
  val cacheDirectory: String = scala.util.Properties.envOrElse("cache.directory", null)
  val httpProxySet: Boolean = scala.util.Properties.envOrElse("http.proxySet", "false").toBoolean
  val httpProxyHost: String = scala.util.Properties.envOrElse("http.proxyHost", null)
  val httpNonProxyHosts: String = scala.util.Properties.envOrElse("http.nonProxyHosts", "")
  val importOnlyCurrent: Boolean = scala.util.Properties.envOrElse("importOnlyCurrent", "false").toBoolean
  val authenticationTestMode: Boolean = scala.util.Properties.envOrElse("authenticationTestMode", "false").toBoolean
  val authenticationTestUser: String = scala.util.Properties.envOrElse("authenticationTestUser", null)
  val bonecpJdbcUrl: String = scala.util.Properties.envOrElse("bonecp.jdbcUrl", null)
  val bonecpUsername: String = scala.util.Properties.envOrElse("bonecp.username", null)
  val bonecpPassword: String = scala.util.Properties.envOrElse("bonecp.password", null)
  val conversionBonecpJdbcUrl: String = scala.util.Properties.envOrElse("conversion.bonecp.jdbcUrl", null)
  val conversionBonecpUsername: String = scala.util.Properties.envOrElse("conversion.bonecp.username", null)
  val conversionBonecpPassword: String = scala.util.Properties.envOrElse("conversion.bonecp.password", null)
  val authenticationBasicUsername: String = scala.util.Properties.envOrElse("authentication.basic.username", null)
  val authenticationBasicPassword: String = scala.util.Properties.envOrElse("authentication.basic.password", null)
  val authenticationServiceRoadBasicUsername: String = scala.util.Properties.envOrElse("authentication.serviceRoad.basic.username", null)
  val authenticationServiceRoadBasicPassword: String = scala.util.Properties.envOrElse("authentication.serviceRoad.basic.password", null)
  val authenticationMunicipalityBasicUsername: String = scala.util.Properties.envOrElse("authentication.municipality.basic.username", null)
  val authenticationMunicipalityBasicPassword: String = scala.util.Properties.envOrElse("authentication.municipality.basic.password", null)
  val viitetierekisteriUsername: String = scala.util.Properties.envOrElse("viiteTierekisteri.username", null)
  val viitetierekisteriPassword: String = scala.util.Properties.envOrElse("viiteTierekisteri.password", null)
  val latestDeploy: String = revisionProperties.getProperty("latestDeploy")
  val env: String = scala.util.Properties.envOrElse("env", "Unknown")

  lazy val bonecpProperties: Properties = {
    val props = new Properties()
    try {
      props.setProperty("bonecp.jdbcUrl", bonecpJdbcUrl)
      props.setProperty("bonecp.username", bonecpUsername)
      props.setProperty("bonecp.password", bonecpPassword)
    } catch {
      case e: Exception => throw new RuntimeException("Can't load bonecp properties for env: " + env, e)
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
      case e: Exception => throw new RuntimeException("Can't load conversion bonecp properties for env: " + env, e)
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

class ViitePropertiesFromFile extends ViiteProperties {

  private lazy val envProps: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/env.properties"))
    props
  }

  private lazy val revisionProperties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/revision.properties"))
    props
  }

  override val userProvider: String = envProps.getProperty("digiroad2.userProvider")
  override val municipalityProvider: String = envProps.getProperty("digiroad2.municipalityProvider")
  override val eventBus: String = envProps.getProperty("digiroad2.eventBus")
  override val useVVHGeometry: String = envProps.getProperty("digiroad2.useVVHGeometry")
  override val vvhServiceHost: String = envProps.getProperty("digiroad2.VVHServiceHost")
  override val vvhRestApiEndPoint: String = envProps.getProperty("digiroad2.VVHRestApiEndPoint")
  override val vvhRoadlinkFrozen: Boolean = envProps.getProperty("digiroad2.VVHRoadlink.frozen", "false").toBoolean
  override val vkmUrl: String = envProps.getProperty("digiroad2.VKMUrl")
  override val tierekisteriViiteRestApiEndPoint: String = envProps.getProperty("digiroad2.tierekisteriViiteRestApiEndPoint", "http://localhost:8080/api/tierekisteri/")
  override val tierekisteriEnabled: Boolean = envProps.getProperty("digiroad2.tierekisteri.enabled", "false").toBoolean
  override val cacheDirectory: String = envProps.getProperty("digiroad2.cache.directory")
  override val httpProxySet: Boolean = envProps.getProperty("digiroad2.http.proxySet", "false").toBoolean
  override val httpProxyHost: String = envProps.getProperty("digiroad2.http.proxyHost")
  override val httpNonProxyHosts: String = envProps.getProperty("digiroad2.http.nonProxyHosts", "")
  override val importOnlyCurrent: Boolean = envProps.getProperty("digiroad2.importOnlyCurrent", "false").toBoolean
  override val authenticationTestMode: Boolean = envProps.getProperty("digiroad2.authenticationTestMode", "false").toBoolean
  override val authenticationTestUser: String = envProps.getProperty("digiroad2.authenticationTestUser")
  override val bonecpJdbcUrl: String = envProps.getProperty("bonecp.jdbcUrl")
  override val bonecpUsername: String = envProps.getProperty("bonecp.username")
  override val bonecpPassword: String = envProps.getProperty("bonecp.password")
  override val conversionBonecpJdbcUrl: String = envProps.getProperty("bonecp.jdbcUrl")
  override val conversionBonecpUsername: String = envProps.getProperty("bonecp.username")
  override val conversionBonecpPassword: String = envProps.getProperty("bonecp.password")
  override val authenticationBasicUsername: String = envProps.getProperty("authentication.basic.username")
  override val authenticationBasicPassword: String = envProps.getProperty("authentication.basic.password")
  override val authenticationServiceRoadBasicUsername: String = envProps.getProperty("authentication.serviceRoad.basic.username")
  override val authenticationServiceRoadBasicPassword: String = envProps.getProperty("authentication.serviceRoad.basic.password")
  override val authenticationMunicipalityBasicUsername: String = envProps.getProperty("authentication.municipality.basic.username")
  override val authenticationMunicipalityBasicPassword: String = envProps.getProperty("authentication.municipality.basic.password")
  override val viitetierekisteriUsername: String = envProps.getProperty("viitetierekisteri.username")
  override val viitetierekisteriPassword: String = envProps.getProperty("viitetierekisteri.password")
  override val latestDeploy: String = revisionProperties.getProperty("latestDeploy")
  override val env: String = envProps.getProperty("env")

  override lazy val bonecpProperties: Properties = {
    val props = new Properties()
    try {
      props.setProperty("bonecp.jdbcUrl", bonecpJdbcUrl)
      props.setProperty("bonecp.username", bonecpUsername)
      props.setProperty("bonecp.password", bonecpPassword)
    } catch {
      case e: Exception => throw new RuntimeException("Can't load bonecp properties for env: " + env, e)
    }
    props
  }

  override lazy val conversionBonecpProperties: Properties = {
    val props = new Properties()
    try {
      props.setProperty("bonecp.jdbcUrl", conversionBonecpJdbcUrl)
      props.setProperty("bonecp.username", conversionBonecpUsername)
      props.setProperty("bonecp.password", conversionBonecpPassword)
    } catch {
      case e: Exception => throw new RuntimeException("Can't load conversion bonecp properties for env: " + env, e)
    }
    props
  }

  override def getAuthenticationBasicUsername(baseAuth: String = ""): String = {
    envProps.getProperty("authentication." + baseAuth + (if (baseAuth.isEmpty) "" else ".") + "basic.username")
  }

  override def getAuthenticationBasicPassword(baseAuth: String = ""): String = {
    envProps.getProperty("authentication." + baseAuth + (if (baseAuth.isEmpty) "" else ".") + "basic.password")
  }
}

/**
  * ViiteProperties will get the properties from the environment variables by default.
  * If bonecp.properties is found in classpath, then all the properties are read from the property files.
  */
object ViiteProperties {
  private val logger = LoggerFactory.getLogger(getClass)
  lazy val properties: ViiteProperties = {
    if (getClass.getResource("/env.properties").getFile.isEmpty) {
      new ViitePropertiesFromEnv
    } else {
      logger.info("Reading properties from file 'env.properties'.")
      new ViitePropertiesFromFile
    }
  }

  lazy val userProvider: String = properties.userProvider
  lazy val municipalityProvider: String = properties.municipalityProvider
  lazy val eventBus: String = properties.eventBus
  lazy val useVVHGeometry: String = properties.useVVHGeometry
  lazy val vvhServiceHost: String = properties.vvhServiceHost
  lazy val vvhRestApiEndPoint: String = properties.vvhRestApiEndPoint
  lazy val vvhRoadlinkFrozen: Boolean = properties.vvhRoadlinkFrozen
  lazy val vkmUrl: String = properties.vkmUrl
  lazy val tierekisteriViiteRestApiEndPoint: String = properties.tierekisteriViiteRestApiEndPoint
  lazy val tierekisteriEnabled: Boolean = properties.tierekisteriEnabled
  lazy val cacheDirectory: String = properties.cacheDirectory
  lazy val httpProxySet: Boolean = properties.httpProxySet
  lazy val httpProxyHost: String = properties.httpProxyHost
  lazy val httpNonProxyHosts: String = properties.httpNonProxyHosts
  lazy val importOnlyCurrent: Boolean = properties.importOnlyCurrent
  lazy val authenticationTestMode: Boolean = properties.authenticationTestMode
  lazy val authenticationTestUser: String = properties.authenticationTestUser
  lazy val bonecpJdbcUrl: String = properties.bonecpJdbcUrl
  lazy val bonecpUsername: String = properties.bonecpUsername
  lazy val bonecpPassword: String = properties.bonecpPassword
  lazy val conversionBonecpJdbcUrl: String = properties.conversionBonecpJdbcUrl
  lazy val conversionBonecpUsername: String = properties.conversionBonecpUsername
  lazy val conversionBonecpPassword: String = properties.conversionBonecpPassword
  lazy val authenticationBasicUsername: String = properties.authenticationBasicUsername
  lazy val authenticationBasicPassword: String = properties.authenticationBasicPassword
  lazy val authenticationServiceRoadBasicUsername: String = properties.authenticationServiceRoadBasicUsername
  lazy val authenticationServiceRoadBasicPassword: String = properties.authenticationServiceRoadBasicPassword
  lazy val authenticationMunicipalityBasicUsername: String = properties.authenticationMunicipalityBasicUsername
  lazy val authenticationMunicipalityBasicPassword: String = properties.authenticationMunicipalityBasicPassword
  lazy val viitetierekisteriUsername: String = properties.viitetierekisteriUsername
  lazy val viitetierekisteriPassword: String = properties.viitetierekisteriPassword
  lazy val latestDeploy: String = properties.latestDeploy
  lazy val env: String = properties.env

  lazy val bonecpProperties: Properties = properties.bonecpProperties
  lazy val conversionBonecpProperties: Properties = properties.conversionBonecpProperties

  def getAuthenticationBasicUsername(baseAuth: String = ""): String = properties.getAuthenticationBasicUsername(baseAuth)
  def getAuthenticationBasicPassword(baseAuth: String = ""): String = properties.getAuthenticationBasicPassword(baseAuth)
}
