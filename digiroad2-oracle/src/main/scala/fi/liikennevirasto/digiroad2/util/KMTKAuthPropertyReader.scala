package fi.liikennevirasto.digiroad2.util

import java.util.Properties

import org.apache.commons.codec.binary.Base64

class KMTKAuthPropertyReader {

  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/keys.properties"))
    props
  }

  private def getUsername: String = {
    val loadedKeyString = properties.getProperty("kmtk.username")
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing KMTK username")
    loadedKeyString
  }

  private def getPassword: String = {
    val loadedKeyString = properties.getProperty("kmtk.password")
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing KMTK Password")
    loadedKeyString
  }

  def getAuthInBase64: String = {
    Base64.encodeBase64String((getUsername + ":" + getPassword).getBytes)
  }
}
