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
  val vkmUrlDev: String
  val vkmApiKey: String
  val vkmApiKeyDev: String
  val importOnlyCurrent: Boolean
  val authenticationTestMode: Boolean
  val authenticationTestUser: String

  // Database properties
  val dbJdbcUrl: String
  val dbUsername: String
  val dbPassword: String
  val conversionDbJdbcUrl: String
  val conversionDbUsername: String
  val conversionDbPassword: String

  val latestDeploy: String
  val env: String
  val apiS3BucketName: String
  val dynamicLinkNetworkS3BucketName: String
  val awsConnectionEnabled: Boolean
  val apiS3ObjectTTLSeconds: String


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
  val vkmUrlDev: String = scala.util.Properties.envOrElse("vkmUrlDev", null)
  val vkmApiKey: String = scala.util.Properties.envOrElse("vkmApiKey", null)
  val vkmApiKeyDev: String = scala.util.Properties.envOrElse("vkmApiKeyDev", null)
  val importOnlyCurrent: Boolean = scala.util.Properties.envOrElse("importOnlyCurrent", "false").toBoolean
  val authenticationTestMode: Boolean = scala.util.Properties.envOrElse("authenticationTestMode", "false").toBoolean
  val authenticationTestUser: String = scala.util.Properties.envOrElse("authenticationTestUser", null)

  // Database connection properties with fallback mechanism
  // This code follows this priority order for retrieving each property:
  // 1. Modern property from environment (e.g., "db.jdbcUrl")
  // 2. Legacy property from environment (e.g., "bonecp.jdbcUrl")
  // 3. Null if neither is found
  //
  // DEPRECATION NOTICE:
  // The "bonecp" prefixed properties are legacy names from the BoneCP connection pool.
  // All services should transition to using the standardized "db.*" property names.
  //
  // TODO: After all environments (DEV/QA/PROD) have been updated to use the new
  // property naming convention, remove the legacy fallbacks to simplify this code.
  //
  val dbJdbcUrl: String = scala.util.Properties.envOrElse("db.jdbcUrl",
    scala.util.Properties.envOrElse("bonecp.jdbcUrl", null))
  val dbUsername: String = scala.util.Properties.envOrElse("db.username",
    scala.util.Properties.envOrElse("bonecp.username", null))
  val dbPassword: String = scala.util.Properties.envOrElse("db.password",
    scala.util.Properties.envOrElse("bonecp.password", null))
  val conversionDbJdbcUrl: String = scala.util.Properties.envOrElse("conversion.db.jdbcUrl",
    scala.util.Properties.envOrElse("conversion.bonecp.jdbcUrl", null))
  val conversionDbUsername: String = scala.util.Properties.envOrElse("conversion.db.username",
    scala.util.Properties.envOrElse("conversion.bonecp.username", null))
  val conversionDbPassword: String = scala.util.Properties.envOrElse("conversion.db.password",
    scala.util.Properties.envOrElse("conversion.bonecp.password", null))

  val latestDeploy: String = revisionProperties.getProperty("latestDeploy", "-")
  val env: String = scala.util.Properties.envOrElse("env", "Unknown")
  val apiS3BucketName: String = scala.util.Properties.envOrElse("apiS3BucketName", null)
  val dynamicLinkNetworkS3BucketName: String = scala.util.Properties.envOrElse("dynamicLinkNetworkS3BucketName", null)
  val awsConnectionEnabled: Boolean = scala.util.Properties.envOrElse("awsConnectionEnabled", "true").toBoolean
  val apiS3ObjectTTLSeconds: String = scala.util.Properties.envOrElse("apiS3ObjectTTLSeconds", null)

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
  override val vkmUrlDev: String = scala.util.Properties.envOrElse("vkmUrlDev", envProps.getProperty("vkmUrlDev"))
  override val vkmApiKey: String = scala.util.Properties.envOrElse("vkmApiKey", envProps.getProperty("vkmApiKey"))
  override val vkmApiKeyDev: String = scala.util.Properties.envOrElse("vkmApiKeyDev", envProps.getProperty("vkmApiKeyDev"))
  override val importOnlyCurrent: Boolean = envProps.getProperty("importOnlyCurrent", "false").toBoolean
  override val authenticationTestMode: Boolean = envProps.getProperty("authenticationTestMode", "false").toBoolean
  override val authenticationTestUser: String = envProps.getProperty("authenticationTestUser")
  // Property resolution with multiple fallback mechanisms.
  // The code follows this priority order for each property:
  // 1. Modern environment variable (e.g., "dbJdbcUrl")
  // 2. Legacy environment variable (e.g., "bonecpJdbcUrl")
  // 3. Modern property from config file (e.g., "db.jdbcUrl")
  // 4. Legacy property from config file (e.g., "bonecp.jdbcUrl")
  //
  // DEPRECATION NOTICE:
  // The "bonecp" prefixed properties are deprecated as part of the migration from
  // Slick to Scalike the BoneCp connection pool is not used anymore. All services should transition to using the
  // standardized "db.*" property names.
  //
  // TODO: After all environments (DEV/QA/PROD) have been updated to use the new
  // property names, we should remove the legacy fallbacks to simplify this code.
  override val dbJdbcUrl: String =
    scala.util.Properties.envOrElse("dbJdbcUrl",
      scala.util.Properties.envOrElse("bonecpJdbcUrl",
        envProps.getProperty("db.jdbcUrl", envProps.getProperty("bonecp.jdbcUrl"))))

  override val dbUsername: String =
    scala.util.Properties.envOrElse("dbUsername",
      scala.util.Properties.envOrElse("bonecpUsername",
        envProps.getProperty("db.username", envProps.getProperty("bonecp.username"))))

  override val dbPassword: String =
    scala.util.Properties.envOrElse("dbPassword",
      scala.util.Properties.envOrElse("bonecpPassword",
        envProps.getProperty("db.password", envProps.getProperty("bonecp.password"))))

  override val conversionDbJdbcUrl: String =
    scala.util.Properties.envOrElse("conversionDbJdbcUrl",
      scala.util.Properties.envOrElse("conversionBonecpJdbcUrl",
        envProps.getProperty("conversion.db.jdbcUrl", envProps.getProperty("conversion.bonecp.jdbcUrl"))))

  override val conversionDbUsername: String =
    scala.util.Properties.envOrElse("conversionDbUsername",
      scala.util.Properties.envOrElse("conversionBonecpUsername",
        envProps.getProperty("conversion.db.username", envProps.getProperty("conversion.bonecp.username"))))

  override val conversionDbPassword: String =
    scala.util.Properties.envOrElse("conversionDbPassword",
      scala.util.Properties.envOrElse("conversionBonecpPassword",
        envProps.getProperty("conversion.db.password", envProps.getProperty("conversion.bonecp.password"))))

  override val latestDeploy: String = revisionProperties.getProperty("latestDeploy", "-")
  override val env: String = envProps.getProperty("env")
  override val apiS3BucketName: String = scala.util.Properties.envOrElse("apiS3BucketName", envProps.getProperty("apiS3BucketName"))
  override val dynamicLinkNetworkS3BucketName: String = scala.util.Properties.envOrElse("dynamicLinkNetworkS3BucketName", envProps.getProperty("dynamicLinkNetworkS3BucketName"))
  override val awsConnectionEnabled: Boolean = envProps.getProperty("awsConnectionEnabled", "true").toBoolean
  override val apiS3ObjectTTLSeconds: String = scala.util.Properties.envOrElse("apiS3ObjectTTLSeconds", envProps.getProperty("apiS3ObjectTTLSeconds"))

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
  lazy val vkmUrlDev: String = properties.vkmUrlDev
  lazy val vkmApiKey: String = properties.vkmApiKey
  lazy val vkmApiKeyDev: String = properties.vkmApiKeyDev
  lazy val importOnlyCurrent: Boolean = properties.importOnlyCurrent
  lazy val authenticationTestMode: Boolean = properties.authenticationTestMode
  lazy val authenticationTestUser: String = properties.authenticationTestUser
  // Database properties
  lazy val dbJdbcUrl: String = properties.dbJdbcUrl
  lazy val dbUsername: String = properties.dbUsername
  lazy val dbPassword: String = properties.dbPassword
  lazy val conversionDbJdbcUrl: String = properties.conversionDbJdbcUrl
  lazy val conversionDbUsername: String = properties.conversionDbUsername
  lazy val conversionDbPassword: String = properties.conversionDbPassword

  // Legacy property names for backward compatibility during migration to Scalike
  // These properties are deprecated and will be removed after all services are updated.
  // New code should use the modern property names (db* and conversionDb*).
  @deprecated("Use dbJdbcUrl instead of bonecpJdbcUrl", "Migration to Scalike")
  lazy val bonecpJdbcUrl: String = dbJdbcUrl

  @deprecated("Use dbUsername instead of bonecpUsername", "Migration to Scalike")
  lazy val bonecpUsername: String = dbUsername

  @deprecated("Use dbPassword instead of bonecpPassword", "Migration to Scalike")
  lazy val bonecpPassword: String = dbPassword

  @deprecated("Use conversionDbJdbcUrl instead of boncecpConversionDbJdbcUrl", "Migration to Scalike")
  lazy val conversionBonecpJdbcUrl: String = conversionDbJdbcUrl

  @deprecated("Use conversionDbUsername instead of conversionBonecpUsername", "Migration to Scalike")
  lazy val conversionBonecpUsername: String = conversionDbUsername

  @deprecated("Use conversionDbPassword instead of conversionBonecpPassword", "Migration to Scalike")
  lazy val conversionBonecpPassword: String = conversionDbPassword

  lazy val latestDeploy: String = properties.latestDeploy
  lazy val env: String = properties.env
  lazy val apiS3BucketName: String = properties.apiS3BucketName
  lazy val dynamicLinkNetworkS3BucketName: String = properties.dynamicLinkNetworkS3BucketName
  lazy val awsConnectionEnabled: Boolean = properties.awsConnectionEnabled
  lazy val apiS3ObjectTTLSeconds: String = properties.apiS3ObjectTTLSeconds

  def getAuthenticationBasicUsername(baseAuth: String = ""): String = properties.getAuthenticationBasicUsername(baseAuth)
  def getAuthenticationBasicPassword(baseAuth: String = ""): String = properties.getAuthenticationBasicPassword(baseAuth)
}
