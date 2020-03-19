package fi.liikennevirasto.digiroad2.util

import java.util.Properties

class ViiteProperties {
  private lazy val bonecpPropertiesFromFile: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/bonecp.properties"))
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
  private lazy val keysProperties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/keys.properties"))
    props
  }
  private lazy val revisionProperties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/revision.properties"))
    props
  }

  lazy val userProvider: String = dr2Properties.getProperty("digiroad2.userProvider")
  lazy val municipalityProvider: String = dr2Properties.getProperty("digiroad2.municipalityProvider")
  lazy val eventBus: String = dr2Properties.getProperty("digiroad2.eventBus")
  lazy val useVVHGeometry: String = dr2Properties.getProperty("digiroad2.useVVHGeometry")
  lazy val vvhServiceHost: String = dr2Properties.getProperty("digiroad2.VVHServiceHost")
  lazy val vvhRestApiEndPoint: String = dr2Properties.getProperty("digiroad2.VVHRestApiEndPoint")
  lazy val vvhRoadlinkFrozen: Boolean = dr2Properties.getProperty("digiroad2.VVHRoadlink.frozen", "false").toBoolean
  lazy val vkmUrl: String = dr2Properties.getProperty("digiroad2.VKMUrl")
  lazy val tierekisteriViiteRestApiEndPoint: String = dr2Properties.getProperty("digiroad2.tierekisteriViiteRestApiEndPoint")
  lazy val tierekisteriEnabled: Boolean = dr2Properties.getProperty("digiroad2.tierekisteri.enabled", "false").toBoolean
  lazy val cacheDirectory: String = dr2Properties.getProperty("digiroad2.cache.directory")
  lazy val importTimeStamp: String = dr2Properties.getProperty("digiroad2.viite.importTimeStamp")
  lazy val httpProxySet: Boolean = dr2Properties.getProperty("digiroad2.http.proxySet", "false").toBoolean
  lazy val httpProxyHost: String = dr2Properties.getProperty("digiroad2.http.proxyHost")
  lazy val httpNonProxyHosts: String = dr2Properties.getProperty("digiroad2.http.nonProxyHosts", "")
  lazy val importOnlyCurrent: Boolean = dr2Properties.getProperty("digiroad2.importOnlyCurrent", "false").toBoolean
  lazy val authenticationTestMode: Boolean = dr2Properties.getProperty("digiroad2.authenticationTestMode", "false").toBoolean
  lazy val bonecpJdbcUrl: String = bonecpPropertiesFromFile.getProperty("bonecp.jdbcUrl")
  lazy val bonecpUsername: String = bonecpPropertiesFromFile.getProperty("bonecp.username")
  lazy val bonecpPassword: String = bonecpPropertiesFromFile.getProperty("bonecp.password")
  lazy val authenticationBasicUsername: String = authenticationProperties.getProperty("authentication.basic.username")
  lazy val authenticationBasicPassword: String = authenticationProperties.getProperty("authentication.basic.password")
  lazy val authenticationServiceRoadBasicUsername: String = authenticationProperties.getProperty("authentication.serviceRoad.basic.username")
  lazy val authenticationServiceRoadBasicPassword: String = authenticationProperties.getProperty("authentication.serviceRoad.basic.password")
  lazy val authenticationMunicipalityBasicUsername: String = authenticationProperties.getProperty("authentication.municipality.basic.username")
  lazy val authenticationMunicipalityBasicPassword: String = authenticationProperties.getProperty("authentication.municipality.basic.password")
  lazy val viitetierekisteriUsername: String = authenticationProperties.getProperty("viitetierekisteri.username")
  lazy val viitetierekisteriPassword: String = authenticationProperties.getProperty("viitetierekisteri.password")
  lazy val revision: String = revisionProperties.getProperty("digiroad2.revision")
  lazy val latestDeploy: String = revisionProperties.getProperty("digiroad2.latestDeploy")
  lazy val env: String = System.getProperty("env")

  lazy val bonecpProperties: Properties = {
    val props = new Properties()
    try {
      props.setProperty("bonecp.jdbcUrl", ViiteProperties.bonecpJdbcUrl)
      props.setProperty("bonecp.username", ViiteProperties.bonecpUsername)
      props.setProperty("bonecp.password", ViiteProperties.bonecpPassword)
    } catch {
      case e: Exception => throw new RuntimeException("Can't load local.properties for env: " + System.getProperty("env"), e)
    }
    props
  }
}

object ViiteProperties extends ViiteProperties {
  new ViiteProperties
}
