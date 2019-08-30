package fi.liikennevirasto.digiroad2.client

import java.util.Properties

import org.scalatest.{FunSuite, Matchers}

class VVHClientSpec extends FunSuite with Matchers {
  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }

}

