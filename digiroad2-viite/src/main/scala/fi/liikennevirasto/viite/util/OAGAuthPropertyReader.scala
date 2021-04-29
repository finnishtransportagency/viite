package fi.liikennevirasto.viite.util
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import org.apache.commons.codec.binary.Base64

class OAGAuthPropertyReader {

  private def getUsername: String = {
    val loadedKeyString = ViiteProperties.oagUsername
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing OAG username")
    loadedKeyString
  }

  private def getPassword: String = {
    val loadedKeyString = ViiteProperties.oagPassword
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing OAG Password")
    loadedKeyString
  }

  def getAuthInBase64: String = {
    Base64.encodeBase64String((getUsername + ":" + getPassword).getBytes)
  }

}
