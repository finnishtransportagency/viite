package fi.liikennevirasto.viite.util

import org.scalatest.{FunSuite, Matchers}

class OAGAuthPropertyReaderSpec extends FunSuite with Matchers {
  val reader = new OAGAuthPropertyReader

  test("Test reader.getAuthInBase64 When asking for the Basic64 authentication string for OAG Then return said string.") {
    val authenticate = reader.getAuthInBase64
    // when string below is decoded it will have values from env.properties presented as: <oag.username>:<oag.password>
    // "c3ZjX2Nsb3VkdmlpdGU6c3ZjX2Nsb3VkdmlpdGU=" == svc_cloudviite:svc_cloudviite
    authenticate should be ("c3ZjX2Nsb3VkdmlpdGU6c3ZjX2Nsb3VkdmlpdGU=")
  }
}
