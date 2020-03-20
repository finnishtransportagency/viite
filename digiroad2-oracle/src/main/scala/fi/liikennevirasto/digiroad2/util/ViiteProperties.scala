package fi.liikennevirasto.digiroad2.util

import java.util.Properties

class ViiteProperties {
  lazy val userProvider: String = scala.util.Properties.envOrElse("userProvider", null)
  lazy val municipalityProvider: String = scala.util.Properties.envOrElse("municipalityProvider", null)
  lazy val eventBus: String = scala.util.Properties.envOrElse("eventBus", null)
  lazy val vvhServiceHost: String = scala.util.Properties.envOrElse("vvhServiceHost", null)
  lazy val vvhRestApiEndPoint: String = scala.util.Properties.envOrElse("vvhRestApiEndPoint", null)
  lazy val vvhRoadlinkFrozen: Boolean = scala.util.Properties.envOrElse("vvhRoadlink.frozen", "false").toBoolean
  lazy val vkmUrl: String = scala.util.Properties.envOrElse("vkmUrl", null)
  lazy val tierekisteriViiteRestApiEndPoint: String = scala.util.Properties.envOrElse("tierekisteriViiteRestApiEndPoint", "http://localhost:8080/api/tierekisteri/")
  lazy val tierekisteriEnabled: Boolean = scala.util.Properties.envOrElse("tierekisteri.enabled", "false").toBoolean
  lazy val cacheDirectory: String = scala.util.Properties.envOrElse("cache.directory", null)
  lazy val importTimeStamp: String = scala.util.Properties.envOrElse("viite.importTimeStamp", null)
  lazy val httpProxySet: Boolean = scala.util.Properties.envOrElse("http.proxySet", "false").toBoolean
  lazy val httpProxyHost: String = scala.util.Properties.envOrElse("http.proxyHost", null)
  lazy val httpNonProxyHosts: String = scala.util.Properties.envOrElse("http.nonProxyHosts", "")
  lazy val importOnlyCurrent: Boolean = scala.util.Properties.envOrElse("importOnlyCurrent", "false").toBoolean
  lazy val authenticationTestMode: Boolean = scala.util.Properties.envOrElse("authenticationTestMode", "false").toBoolean
  lazy val bonecpJdbcUrl: String = scala.util.Properties.envOrElse("bonecp.jdbcUrl", null)
  lazy val bonecpUsername: String = scala.util.Properties.envOrElse("bonecp.username", null)
  lazy val bonecpPassword: String = scala.util.Properties.envOrElse("bonecp.password", null)
  lazy val importBonecpJdbcUrl: String = scala.util.Properties.envOrElse("import.bonecp.jdbcUrl", null)
  lazy val importBonecpUsername: String = scala.util.Properties.envOrElse("import.bonecp.username", null)
  lazy val importBonecpPassword: String = scala.util.Properties.envOrElse("import.bonecp.password", null)
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
  lazy val revision: String = scala.util.Properties.envOrElse("revision", null)
  lazy val latestDeploy: String = scala.util.Properties.envOrElse("latestDeploy", null)
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

  lazy val importBonecpProperties: Properties = {
    val props = new Properties()
    try {
      props.setProperty("bonecp.jdbcUrl", importBonecpJdbcUrl)
      props.setProperty("bonecp.username", importBonecpUsername)
      props.setProperty("bonecp.password", importBonecpPassword)
    } catch {
      case e: Exception => throw new RuntimeException("Can't load import bonecp properties for env: " + System.getProperty("env"), e)
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
    scala.util.Properties.envOrElse("authentication." + baseAuth + "basic.username", null)
  }

  def getAuthenticationBasicPassword(baseAuth: String = ""): String = {
    scala.util.Properties.envOrElse("authentication." + baseAuth + "basic.password", null)
  }
}

class ViitePropertiesFromFiles extends ViiteProperties {
  private lazy val bonecpPropertiesFromFile: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/bonecp.properties"))
    props
  }
  private lazy val importBonecpPropertiesFromFile: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/import.bonecp.properties"))
    props
  }
  private lazy val conversionBonecpPropertiesFromFile: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/conversion.bonecp.properties"))
    props
  }
  private lazy val dr2Properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }
  private lazy val authenticationProperties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/authentication.properties"))
    props
  }
  private lazy val revisionProperties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/revision.properties"))
    props
  }

  override lazy val userProvider: String = dr2Properties.getProperty("digiroad2.userProvider")
  override lazy val municipalityProvider: String = dr2Properties.getProperty("digiroad2.municipalityProvider")
  override lazy val eventBus: String = dr2Properties.getProperty("digiroad2.eventBus")
  override lazy val vvhServiceHost: String = dr2Properties.getProperty("digiroad2.VVHServiceHost")
  override lazy val vvhRestApiEndPoint: String = dr2Properties.getProperty("digiroad2.VVHRestApiEndPoint")
  override lazy val vvhRoadlinkFrozen: Boolean = dr2Properties.getProperty("digiroad2.VVHRoadlink.frozen", "false").toBoolean
  override lazy val vkmUrl: String = dr2Properties.getProperty("digiroad2.VKMUrl")
  override lazy val tierekisteriViiteRestApiEndPoint: String = dr2Properties.getProperty("digiroad2.tierekisteriViiteRestApiEndPoint", "http://localhost:8080/api/tierekisteri/")
  override lazy val tierekisteriEnabled: Boolean = dr2Properties.getProperty("digiroad2.tierekisteri.enabled", "false").toBoolean
  override lazy val cacheDirectory: String = dr2Properties.getProperty("digiroad2.cache.directory")
  override lazy val importTimeStamp: String = dr2Properties.getProperty("digiroad2.viite.importTimeStamp")
  override lazy val httpProxySet: Boolean = dr2Properties.getProperty("digiroad2.http.proxySet", "false").toBoolean
  override lazy val httpProxyHost: String = dr2Properties.getProperty("digiroad2.http.proxyHost")
  override lazy val httpNonProxyHosts: String = dr2Properties.getProperty("digiroad2.http.nonProxyHosts", "")
  override lazy val importOnlyCurrent: Boolean = dr2Properties.getProperty("digiroad2.importOnlyCurrent", "false").toBoolean
  override lazy val authenticationTestMode: Boolean = dr2Properties.getProperty("digiroad2.authenticationTestMode", "false").toBoolean
  override lazy val bonecpJdbcUrl: String = bonecpPropertiesFromFile.getProperty("bonecp.jdbcUrl")
  override lazy val bonecpUsername: String = bonecpPropertiesFromFile.getProperty("bonecp.username")
  override lazy val bonecpPassword: String = bonecpPropertiesFromFile.getProperty("bonecp.password")
  override lazy val importBonecpJdbcUrl: String = importBonecpPropertiesFromFile.getProperty("bonecp.jdbcUrl")
  override lazy val importBonecpUsername: String = importBonecpPropertiesFromFile.getProperty("bonecp.username")
  override lazy val importBonecpPassword: String = importBonecpPropertiesFromFile.getProperty("bonecp.password")
  override lazy val conversionBonecpJdbcUrl: String = conversionBonecpPropertiesFromFile.getProperty("bonecp.jdbcUrl")
  override lazy val conversionBonecpUsername: String = conversionBonecpPropertiesFromFile.getProperty("bonecp.username")
  override lazy val conversionBonecpPassword: String = conversionBonecpPropertiesFromFile.getProperty("bonecp.password")
  override lazy val authenticationBasicUsername: String = authenticationProperties.getProperty("authentication.basic.username")
  override lazy val authenticationBasicPassword: String = authenticationProperties.getProperty("authentication.basic.password")
  override lazy val authenticationServiceRoadBasicUsername: String = authenticationProperties.getProperty("authentication.serviceRoad.basic.username")
  override lazy val authenticationServiceRoadBasicPassword: String = authenticationProperties.getProperty("authentication.serviceRoad.basic.password")
  override lazy val authenticationMunicipalityBasicUsername: String = authenticationProperties.getProperty("authentication.municipality.basic.username")
  override lazy val authenticationMunicipalityBasicPassword: String = authenticationProperties.getProperty("authentication.municipality.basic.password")
  override lazy val viitetierekisteriUsername: String = authenticationProperties.getProperty("viitetierekisteri.username")
  override lazy val viitetierekisteriPassword: String = authenticationProperties.getProperty("viitetierekisteri.password")
  override lazy val revision: String = revisionProperties.getProperty("digiroad2.revision")
  override lazy val latestDeploy: String = revisionProperties.getProperty("digiroad2.latestDeploy")
  override lazy val env: String = System.getProperty("env")

  override lazy val bonecpProperties: Properties = {
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

  override lazy val importBonecpProperties: Properties = {
    val props = new Properties()
    try {
      props.setProperty("bonecp.jdbcUrl", importBonecpJdbcUrl)
      props.setProperty("bonecp.username", importBonecpUsername)
      props.setProperty("bonecp.password", importBonecpPassword)
    } catch {
      case e: Exception => throw new RuntimeException("Can't load import bonecp properties for env: " + System.getProperty("env"), e)
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
      case e: Exception => throw new RuntimeException("Can't load conversion bonecp properties for env: " + System.getProperty("env"), e)
    }
    props
  }

  override def getAuthenticationBasicUsername(baseAuth: String = ""): String = {
    authenticationProperties.getProperty("authentication." + baseAuth + "basic.username")
  }

  override def getAuthenticationBasicPassword(baseAuth: String = ""): String = {
    authenticationProperties.getProperty("authentication." + baseAuth + "basic.password")
  }
}

/**
  * ViiteProperties will get the properties from the environment variables by default.
  * If bonecp.properties is found in classpath, then all the properties are read from the property files.
  */
object ViiteProperties extends ViiteProperties {
  if (getClass.getResource("/bonecp.properties").getFile.isEmpty) {
    new ViiteProperties
  } else {
    new ViitePropertiesFromFiles
  }
}
