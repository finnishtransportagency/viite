package fi.vaylavirasto.viite.model

import fi.vaylavirasto.viite.util.ViiteException
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ArealRoadMaintainerSpec extends AnyFunSuite with Matchers {

  test("Test ArealRoadMaintainer: Cannot create a random ArealRoadMaintainer. Or even one with a proper name. Creation throws an exception.") {
    intercept[Exception](ArealRoadMaintainer("asdf"))    shouldBe a[ViiteException]
    intercept[Exception](ArealRoadMaintainer("Uusimaa")) shouldBe a[ViiteException]
    intercept[Exception](ArealRoadMaintainer("1"))       shouldBe a[ViiteException]
  }


  //////// ArealRoadMaintainer/EVK tests ////////

    test("Test ArealRoadMaintainer/EVK: test basic ArealRoadMaintainer functionality working") {

      val EVK = ArealRoadMaintainer.getEVK("Pohjanmaa")

      //Testing field access, and values
      EVK.typeName  shouldBe "EVK"
      EVK.number    shouldBe 8
      EVK.name      shouldBe "Pohjanmaa"
      EVK.shortName shouldBe "POHJ"

      // testing print functionality
      EVK.toString        shouldBe "EVK 8 Pohjanmaa"
      EVK.id              shouldBe "EVK8"
      EVK.id              shouldBe EVK.typeName+EVK.number
      EVK.toStringVerbose shouldBe "EVK 8 Pohjanmaa"
      EVK.toStringShort   shouldBe "POHJ"
      EVK.toStringAll     shouldBe "EVK 8 Pohjanmaa (POHJ)"
  }

  test("Test ArealRoadMaintainer/EVK: getEVK by id numbers.") {
    intercept[Exception] (ArealRoadMaintainer.getEVK( 0)) shouldBe a[ViiteException] // No such EVK number -> not found
                          ArealRoadMaintainer.getEVK( 1)                             // Proper EVK number  -> found
                          ArealRoadMaintainer.getEVK(10)                             // Proper EVK number  -> found
    intercept[Exception] (ArealRoadMaintainer.getEVK(11)) shouldBe a[ViiteException] // No such EVK number -> not found
    intercept[Exception] (ArealRoadMaintainer.getEVK(14)) shouldBe a[ViiteException] // *ELY*-only number  -> not found
  }

  test("Test ArealRoadMaintainer/EVK: getEVK by string.") {
    //gets succeed with values corresponding to predefined EVK ARMs
    val predefEVK1 = ArealRoadMaintainer.getEVK("EVK3")    // Get a pre-defined EVK with existing EVK db name
    val predefEVK3 = ArealRoadMaintainer.getEVK("Uusimaa") // Get a pre-defined EVK with existing EVK name
    val predefEVK4 = ArealRoadMaintainer.getEVK("SISS")    // Get a pre-defined EVK with existing EVK shortName

    // gets fail with non-EVK values, even if proper ARMs, but not EVKs
    val noELY1 = intercept[Exception] (ArealRoadMaintainer.getEVK("ELY3")     )  shouldBe a[ViiteException]  // Try getting a pre-defined EVK with an *ELY* db name
    val noELY3 = intercept[Exception] (ArealRoadMaintainer.getEVK("Pirkanmaa"))  shouldBe a[ViiteException]  // Try getting a pre-defined EVK with an *ELY*-only name
    val noELY2 = intercept[Exception] (ArealRoadMaintainer.getEVK("LAP")      )  shouldBe a[ViiteException]  // Try getting a pre-defined EVK with an *ELY* short name
  }

  test("Test ArealRoadMaintainer/EVK: getEVK existence.") {
    case class EVKclone(val name: String, val number: Int, val shortName: String) extends EVK // class to test EVK ArealRoadMaintainer functionality with

    val testEVK = ArealRoadMaintainer.getEVK(1)
    val testEVKClone = EVKclone("Uusimaa", 1, "UUSI") // A basically proper EVK, but this is not any of our pre-defined instances

    ArealRoadMaintainer.existsEVK(testEVK)      shouldBe true // EVK 1 is found.
    ArealRoadMaintainer.existsEVK(testEVKClone) shouldBe false // ArealRoadMaintainer does not recognize randomly created EVK clones.
  }


  //////// ArealRoadMaintainer/ELY tests ////////

  test("Test ArealRoadMaintainer/ELY: test basic ArealRoadMaintainer functionality working with ELYs") {

    val ELY = ArealRoadMaintainer.getELY("Pirkanmaa")

    //Testing field access, and values
    ELY.typeName  shouldBe "ELY"
    ELY.number    shouldBe 4
    ELY.name      shouldBe "Pirkanmaa"
    ELY.shortName shouldBe "PIR"

    // testing print functionality

    ELY.id              shouldBe "ELY4"
    ELY.id              shouldBe ELY.typeName+ELY.number

    ELY.toString        shouldBe "ELY 4 Pirkanmaa"
    ELY.toStringVerbose shouldBe "ELY 4 Pirkanmaa"
    ELY.toStringShort   shouldBe "PIR"
    ELY.toStringAll     shouldBe "ELY 4 Pirkanmaa (PIR)"
  }

  test("Test ArealRoadMaintainer/ELY: getELY by id numbers.") {
    intercept[Exception] (ArealRoadMaintainer.getELY( 0)) shouldBe a[ViiteException] // No such ELY number -> not found
                          ArealRoadMaintainer.getELY( 1)                             // Proper  ELY number -> found
                          ArealRoadMaintainer.getELY(14)                             // Proper  ELY number -> found
    intercept[Exception] (ArealRoadMaintainer.getELY(15)) shouldBe a[ViiteException] // No such ELY number -> not found
    intercept[Exception] (ArealRoadMaintainer.getELY( 5)) shouldBe a[ViiteException] // *EVK*-only number  -> not found
  }

  test("Test ArealRoadMaintainer/ELY: getELY by string.") {
    //gets succeed with values corresponding to predefined ELY ARMs
    val predefELY1 = ArealRoadMaintainer.getELY("ELY3")    // Get a pre-defined ELY with existing ELY dbName
    val predefELY3 = ArealRoadMaintainer.getELY("Uusimaa") // Get a pre-defined ELY with existing ELY name
    val predefELY4 = ArealRoadMaintainer.getELY("VAR")     // Get a pre-defined ELY with existing ELY shortName

    // gets fail with non-ELY values, even if proper ARMs, but not ELYs
    val noELY1 = intercept[Exception] (ArealRoadMaintainer.getELY("EVK3")     )  shouldBe a[ViiteException]  // Try getting a pre-defined ELY with an *EVK* db name
    val noELY3 = intercept[Exception] (ArealRoadMaintainer.getELY("Pohjanmaa"))  shouldBe a[ViiteException]  // Try getting a pre-defined ELY with an *EVK*-only name
    val noELY2 = intercept[Exception] (ArealRoadMaintainer.getELY("LAPP")     )  shouldBe a[ViiteException]  // Try getting a pre-defined ELY with an *EVK* short name
  }

  test("Test ArealRoadMaintainer/ELY: getELY existence.") {
    val testELY = ArealRoadMaintainer.getELY(1)
    case class ELYclone(val name: String, val number: Int, val shortName: String) extends ELY // class to test ELY ArealRoadMaintainer functionality with
    val testELYClone = ELYclone("Uusimaa", 1, "UUD") // A basically proper ELY, but this is not any of our pre-defined instances

    ArealRoadMaintainer.existsELY(testELY)      shouldBe true  // ELY 1 is found within ArealRoadMaintainer.
    ArealRoadMaintainer.existsELY(testELYClone) shouldBe false // ArealRoadMaintainer does not recognize randomly created ELY clones.
  }

}
