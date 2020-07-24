package fi.liikennevirasto.viite.util

/**
  * Created by alapeijario on 29.5.2017.
  */
  import org.scalatest.{FunSuite, Matchers}

  class ViiteTierekisteriAuthPropertyReaderSpec extends FunSuite with Matchers {
    val reader = new ViiteTierekisteriAuthPropertyReader

    test("Test reader.getAuthInBase64 When asking for the Basic64 authentication string for the TR client Then return said string.") {
      val authenticate = reader.getAuthInBase64
      authenticate should be ("dHJyZXN0dmlpdGU6dHJyZXN0dmlpdGU=")
    }
  }

