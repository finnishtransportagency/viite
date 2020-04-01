package fi.liikennevirasto.viite.util

import fi.liikennevirasto.digiroad2.util.ViiteProperties
import org.apache.commons.codec.binary.Base64

class ViiteTierekisteriAuthPropertyReader {

  private def getUsername: String = {
    val loadedKeyString = ViiteProperties.viitetierekisteriUsername
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing TR username")
    loadedKeyString
  }

  private def getPassword: String = {
    val loadedKeyString = ViiteProperties.viitetierekisteriPassword
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing TR Password")
    loadedKeyString
  }

  def getAuthInBase64: String = {
    Base64.encodeBase64String((getUsername + ":" + getPassword).getBytes)
  }
}
