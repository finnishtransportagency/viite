package fi.liikennevirasto.digiroad2.util

import java.util.Properties

import org.slf4j.LoggerFactory

trait ViiteProperties {
  val userProvider: String
  val eventBus: String
  val rasterServiceURL: String
  val rasterServiceApiKey: String
  val kgvRoadlinkFrozen : Boolean
  val kgvEndpoint       : String
  val kgvApiKey: String
  val vkmUrl: String
  val vkmApiKey: String
  val vkmApiKeyDev: String
  val importOnlyCurrent: Boolean
  val authenticationTestMode: Boolean
  val authenticationTestUser: String
  val bonecpJdbcUrl: String
  val bonecpUsername: String
  val bonecpPassword: String
  val conversionBonecpJdbcUrl: String
  val conversionBonecpUsername: String
  val conversionBonecpPassword: String
  val latestDeploy: String
  val env: String
  val apiS3BucketName: String
  val dynamicLinkNetworkS3BucketName: String
  val awsConnectionEnabled: Boolean
  val apiS3ObjectTTLSeconds: String

  val bonecpProperties: Properties
  val conversionBonecpProperties: Properties

  def getAuthenticationBasicUsername(baseAuth: String = ""): String
  def getAuthenticationBasicPassword(baseAuth: String = ""): String
}

class ViitePropertiesFromEnv extends ViiteProperties {

  private lazy val revisionProperties: Properties = {
    val props = new Properties()
    val stream = getClass.getResourceAsStream("/revision.properties")
    if (stream != null) {
      props.load(stream)
    }
    props
  }

  val userProvider: String = scala.util.Properties.envOrElse("userProvider", null)
  val eventBus: String = scala.util.Properties.envOrElse("eventBus", null)
  val rasterServiceURL: String = scala.util.Properties.envOrElse("rasterServiceURL", null)
  val rasterServiceApiKey: String = scala.util.Properties.envOrElse("rasterServiceApiKey", null)
  val kgvRoadlinkFrozen : Boolean = scala.util.Properties.envOrElse("kgvRoadlink.frozen", "false").toBoolean
  val kgvEndpoint       : String  = scala.util.Properties.envOrElse("kgvEndpoint", null)
  val kgvApiKey: String = scala.util.Properties.envOrElse("kgvApiKey", null)
  val vkmUrl: String = scala.util.Properties.envOrElse("vkmUrl", null)
  val vkmApiKey: String = scala.util.Properties.envOrElse("vkmApiKey", null)
  val vkmApiKeyDev: String = scala.util.Properties.envOrElse("vkmApiKeyDev", null)
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
  val latestDeploy: String = revisionProperties.getProperty("latestDeploy", "-")
  val env: String = scala.util.Properties.envOrElse("env", "Unknown")
  val apiS3BucketName: String = scala.util.Properties.envOrElse("apiS3BucketName", null)
  val dynamicLinkNetworkS3BucketName: String = scala.util.Properties.envOrElse("dynamicLinkNetworkS3BucketName", null)
  val awsConnectionEnabled: Boolean = scala.util.Properties.envOrElse("awsConnectionEnabled", "true").toBoolean
  val apiS3ObjectTTLSeconds: String = scala.util.Properties.envOrElse("apiS3ObjectTTLSeconds", null)

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
    val stream = getClass.getResourceAsStream("/revision.properties")
    if (stream != null) {
      props.load(stream)
    }
    props
  }

  override val userProvider: String = envProps.getProperty("userProvider")
  override val eventBus: String = envProps.getProperty("eventBus")
  override val rasterServiceURL: String = scala.util.Properties.envOrElse("rasterServiceURL", envProps.getProperty("rasterServiceURL"))
  override val rasterServiceApiKey: String = scala.util.Properties.envOrElse("rasterServiceApiKey", envProps.getProperty("rasterServiceApiKey"))
  override val kgvRoadlinkFrozen : Boolean = envProps.getProperty("kgvRoadlink.frozen", "false").toBoolean
  override val kgvEndpoint       : String  = envProps.getProperty("kgvEndpoint", null)
  override val kgvApiKey: String = scala.util.Properties.envOrElse("kgvApiKey", envProps.getProperty("kgvApiKey"))
  override val vkmUrl: String = scala.util.Properties.envOrElse("vkmUrl", envProps.getProperty("vkmUrl"))
  override val vkmApiKey: String = scala.util.Properties.envOrElse("vkmApiKey", envProps.getProperty("vkmApiKey"))
  override val vkmApiKeyDev: String = scala.util.Properties.envOrElse("vkmApiKeyDev", envProps.getProperty("vkmApiKeyDev"))
  override val importOnlyCurrent: Boolean = envProps.getProperty("importOnlyCurrent", "false").toBoolean
  override val authenticationTestMode: Boolean = envProps.getProperty("authenticationTestMode", "false").toBoolean
  override val authenticationTestUser: String = envProps.getProperty("authenticationTestUser")
  override val bonecpJdbcUrl: String = scala.util.Properties.envOrElse("bonecpJdbcUrl", envProps.getProperty("bonecp.jdbcUrl"))
  override val bonecpUsername: String = scala.util.Properties.envOrElse("bonecpUsername", envProps.getProperty("bonecp.username"))
  override val bonecpPassword: String = scala.util.Properties.envOrElse("bonecpPassword", envProps.getProperty("bonecp.password"))
  override val conversionBonecpJdbcUrl: String = scala.util.Properties.envOrElse("conversionBonecpJdbcUrl", envProps.getProperty("conversion.bonecp.jdbcUrl"))
  override val conversionBonecpUsername: String = scala.util.Properties.envOrElse("conversionBonecpUsername", envProps.getProperty("conversion.bonecp.username"))
  override val conversionBonecpPassword: String = scala.util.Properties.envOrElse("conversionBonecpPassword", envProps.getProperty("conversion.bonecp.password"))
  override val latestDeploy: String = revisionProperties.getProperty("latestDeploy", "-")
  override val env: String = envProps.getProperty("env")
  override val apiS3BucketName: String = scala.util.Properties.envOrElse("apiS3BucketName", envProps.getProperty("apiS3BucketName"))
  override val dynamicLinkNetworkS3BucketName: String = scala.util.Properties.envOrElse("dynamicLinkNetworkS3BucketName", envProps.getProperty("dynamicLinkNetworkS3BucketName"))
  override val awsConnectionEnabled: Boolean = envProps.getProperty("awsConnectionEnabled", "true").toBoolean
  override val apiS3ObjectTTLSeconds: String = scala.util.Properties.envOrElse("apiS3ObjectTTLSeconds", envProps.getProperty("apiS3ObjectTTLSeconds"))

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
  * If env.properties is found in classpath, then the properties are read from that property file.
  */
object ViiteProperties {
  private val logger = LoggerFactory.getLogger(getClass)
  lazy val properties: ViiteProperties = {
    if (getClass.getResource("/env.properties") == null) {
      new ViitePropertiesFromEnv
    } else {
      logger.info("Reading properties from file 'env.properties'.")
      new ViitePropertiesFromFile
    }
  }

  lazy val userProvider: String = properties.userProvider
  lazy val eventBus: String = properties.eventBus
  lazy val rasterServiceURL: String = properties.rasterServiceURL
  lazy val rasterServiceApiKey: String = properties.rasterServiceApiKey
  lazy val kgvRoadlinkFrozen : Boolean = properties.kgvRoadlinkFrozen
  lazy val kgvApiKey: String  = properties.kgvApiKey
  lazy val kgvEndpoint: String = properties.kgvEndpoint
  lazy val vkmUrl: String = properties.vkmUrl
  lazy val vkmApiKey: String = properties.vkmApiKey
  lazy val vkmApiKeyDev: String = properties.vkmApiKeyDev
  lazy val importOnlyCurrent: Boolean = properties.importOnlyCurrent
  lazy val authenticationTestMode: Boolean = properties.authenticationTestMode
  lazy val authenticationTestUser: String = properties.authenticationTestUser
  lazy val bonecpJdbcUrl: String = properties.bonecpJdbcUrl
  lazy val bonecpUsername: String = properties.bonecpUsername
  lazy val bonecpPassword: String = properties.bonecpPassword
  lazy val conversionBonecpJdbcUrl: String = properties.conversionBonecpJdbcUrl
  lazy val conversionBonecpUsername: String = properties.conversionBonecpUsername
  lazy val conversionBonecpPassword: String = properties.conversionBonecpPassword
  lazy val latestDeploy: String = properties.latestDeploy
  lazy val env: String = properties.env
  lazy val bonecpProperties: Properties = properties.bonecpProperties
  lazy val conversionBonecpProperties: Properties = properties.conversionBonecpProperties
  lazy val apiS3BucketName: String = properties.apiS3BucketName
  lazy val dynamicLinkNetworkS3BucketName: String = properties.dynamicLinkNetworkS3BucketName
  lazy val awsConnectionEnabled: Boolean = properties.awsConnectionEnabled
  lazy val apiS3ObjectTTLSeconds: String = properties.apiS3ObjectTTLSeconds

  def getAuthenticationBasicUsername(baseAuth: String = ""): String = properties.getAuthenticationBasicUsername(baseAuth)
  def getAuthenticationBasicPassword(baseAuth: String = ""): String = properties.getAuthenticationBasicPassword(baseAuth)
}
