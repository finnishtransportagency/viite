import fi.vaylavirasto.viite.util.DateTimeFormatters.finnishDateFormatter
import fi.liikennevirasto.digiroad2.client.vkm.{VKMClient, TiekamuRoadLinkChange}
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import org.joda.time.DateTime
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class VKMClientSpec extends AnyFunSuite with Matchers {

  /**
    * Checks that VKM integration returns road link changes in a given 7-day window
    */
  test("Test getTiekamuRoadlinkChanges When calling VKM API with known date range Then should return some changes") {
    val endPoint = ViiteProperties.vkmUrl
    val apiKey = ""
    val client = new VKMClient(endPoint, apiKey)
    val previousDate = DateTime.parse("2022-01-01")
    val newDate = DateTime.parse("2023-01-01")
    val result = client.getTiekamuRoadlinkChanges(previousDate, newDate)
    result.size should be > 0
    result.head.getClass.getTypeName should be (classOf[TiekamuRoadLinkChange].getCanonicalName)
  }
}
