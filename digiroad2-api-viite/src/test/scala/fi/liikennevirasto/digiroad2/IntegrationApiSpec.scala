package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.viite.dao.{CalibrationPoint, RoadName}
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink}
import fi.liikennevirasto.viite.{RoadAddressService, RoadNameService, RoadType}
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import org.json4s.{DefaultFormats, Formats}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Tag}
import org.scalatra.test.scalatest.ScalatraSuite


class IntegrationApiSpec extends FunSuite with ScalatraSuite with BeforeAndAfter {
  protected implicit val jsonFormats: Formats = DefaultFormats

  val mockRoadAddressService = MockitoSugar.mock[RoadAddressService]
  when(mockRoadAddressService.getAllByMunicipality(235)).thenReturn(Seq())

  val mockRoadNameService = MockitoSugar.mock[RoadNameService]

  private val integrationApi = new IntegrationApi(mockRoadAddressService, mockRoadNameService, new ViiteSwagger)
  addServlet(integrationApi, "/*")

  def getWithBasicUserAuth[A](uri: String, username: String, password: String)(f: => A): A = {
    val credentials = username + ":" + password
    val encodedCredentials = Base64.encodeBase64URLSafeString(credentials.getBytes)
    val authorizationToken = "Basic " + encodedCredentials
    get(uri, Seq.empty, Map("Authorization" -> authorizationToken))(f)
  }

  before {
    integrationApi.clearCache()
  }
  after {
    integrationApi.clearCache()
  }

  test("Test get() and getWithBasicUserAuth() When using no credentials or wrong credentials Then return status code 401.", Tag("db")) {
    get("/road_address") {
      status should equal(401)
    }
    getWithBasicUserAuth("/road_address", "nonexisting", "incorrect") {
      status should equal(401)
    }
  }

  test("Test getWithBasicUserAuth() When asking for the road addresses by municipality but 1st not defining it and 2nd with it Then return status code 400 for the 1st and status code 200 for the 2nd.") {
    getWithBasicUserAuth("/road_address", "kalpa", "kalpa") {
      status should equal(400)
    }
    getWithBasicUserAuth("/road_address?municipality=235", "kalpa", "kalpa") {
      status should equal(200)
    }
  }

  test("Test integrationApi.roadAddressLinksToApi() When supliying a simple Road Address Link Then return a Sequence of a String to Value mappings that represent the Road Address Link.") {
    val geometry = Seq(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.5), Point(4.0, 4.0, 1.5))
    // This roadAddressLink has linearLocationId equal to zero, just to compile.
    val roadAdressLink = RoadAddressLink(63298, 0, 5171208, geometry, GeometryUtils.geometryLength(geometry), Municipality,
      UnknownLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad, Some("Vt5"),
      None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 5, 205, 1, 0, 0, 0, 6, "2015-01-01",
      "2016-01-01", 0.0, 0.0, SideCode.TowardsDigitizing, Some(CalibrationPoint(120, 1, 2)), None, Anomaly.None, 0)
    integrationApi.roadAddressLinksToApi(Seq(roadAdressLink)) should be(Seq(Map(
      "muokattu_viimeksi" -> "",
      "geometryWKT" -> "LINESTRING ZM (0.000 0.000 0.000 0.000, 1.000 0.000 0.500 1.000, 4.000 4.000 1.500 6.000)",
      "id" -> 63298,
      "link_id" -> 5171208,
      "link_source" -> 1,
      "road_number" -> 5,
      "road_part_number" -> 205,
      "track_code" -> 1,
      "side_code" -> 2,
      "start_addr_m" -> 0,
      "end_addr_m" -> 6,
      "ely_code" -> 0,
      "road_type" -> 3,
      "discontinuity" -> 0,
      "start_date" -> "2015-01-01",
      "end_date" -> "2016-01-01",
      "calibration_points" -> Map("start" -> Some(Map("link_id" -> 120, "address_m_value" -> 2, "segment_m_value" -> 1.0)), "end" -> None)
    )))
  }

  test("Test integrationApi.geometryWKT() When using a 3D sequence of points and a start and end measures that fit in the geometry Then return a proper geometry in LineString format.") {
    val (header, returnTxt) =
      integrationApi.geometryWKT(Seq(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.5), Point(4.0, 4.0, 1.5)), 0L, 6L)
    header should be ("geometryWKT")
    returnTxt should be ("LINESTRING ZM (0.000 0.000 0.000 0.000, 1.000 0.000 0.500 1.000, 4.000 4.000 1.500 6.000)")
  }

  def date(year: Int, month: Int, day: Int): Option[DateTime] = {
    Option(new DateTime(year, month, day, 0, 0, 0))
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames but missing the Since parameter Then returns status code 400") {
    getWithBasicUserAuth("/roadnames/changes", "kalpa", "kalpa") {
      status should equal(400)
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames including the Since parameter but giving it no value Then returns status code 400") {
    getWithBasicUserAuth("/roadnames/changes?since=", "kalpa", "kalpa") {
      status should equal(400)
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames including the Since parameter but giving it a incorrect value Then returns status code 400") {
    getWithBasicUserAuth("/roadnames/changes?since=abc", "kalpa", "kalpa") {
      status should equal(400)
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames including the Since parameter but giving it a incorrect date Then returns status code 400") {
    getWithBasicUserAuth("/roadnames/changes?since=1.1.2018", "kalpa", "kalpa") {
      status should equal(400)
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames correctly including the Since but with no data to return Then returns status code 200 and a empty array as the response body.") {
    when(mockRoadNameService.getUpdatedRoadNames(any[DateTime], any[Option[DateTime]])).thenReturn(Right(Seq()))
    getWithBasicUserAuth("/roadnames/changes?since=9999-01-01", "kalpa", "kalpa") {
      status should equal(200)
      response.getHeader("Content-Type") should equal("application/json;charset=utf-8")
      response.body should equal("[]")
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames correctly including the Since but with 1 update to 1 road Then returns status code 200 and a filled array with the info for the road.") {
    when(mockRoadNameService.getUpdatedRoadNames(any[DateTime], any[Option[DateTime]])).thenReturn(
      Right(Seq(
        RoadName(1, 2, "MYROAD", date(2018, 2, 2), None, date(2018, 1, 1), None, "MOCK")
      ))
    )
    getWithBasicUserAuth("/roadnames/changes?since=2018-01-01", "kalpa", "kalpa") {
      status should equal(200)
      response.body should equal(
        "[{\"road_number\":2,\"names\":[{\"change_date\":\"" + DateTime.parse("2018-01-01").toString + "\",\"road_name\":\"MYROAD\",\"start_date\":\"" + DateTime.parse("2018-02-02").toString + "\",\"end_date\":null}]}]"
      )
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames correctly including the Since with many updates to return to 1 road Then returns status code 200 and a filled array with the info for the updates.") {
    when(mockRoadNameService.getUpdatedRoadNames(any[DateTime], any[Option[DateTime]])).thenReturn(
      Right(Seq(
        RoadName(3, 2, "MY ROAD", date(2018, 2, 2), None, date(2018, 1, 1), None, "MOCK"),
        RoadName(2, 2, "THEROAD", date(2000, 2, 2), date(2018, 2, 2), date(2018, 1, 1), None, "MOCK"),
        RoadName(1, 2, "OLDROAD", date(1900, 2, 2), date(2000, 2, 2), date(1900, 1, 1), None, "MOCK")
      ))
    )
    getWithBasicUserAuth("/roadnames/changes?since=2018-01-01", "kalpa", "kalpa") {
      status should equal(200)
      response.body should equal(
        "[{\"road_number\":2,\"names\":[" +
          "{\"change_date\":\"" + DateTime.parse("2018-01-01").toString + "\",\"road_name\":\"MY ROAD\",\"start_date\":\"" + DateTime.parse("2018-02-02").toString + "\",\"end_date\":null}," +
          "{\"change_date\":\"" + DateTime.parse("2018-01-01").toString + "\",\"road_name\":\"THEROAD\",\"start_date\":\"" + DateTime.parse("2000-02-02").toString + "\",\"end_date\":\"" + DateTime.parse("2018-02-02").toString + "\"}," +
          "{\"change_date\":\"" + DateTime.parse("1900-01-01").toString + "\",\"road_name\":\"OLDROAD\",\"start_date\":\"" + DateTime.parse("1900-02-02").toString + "\",\"end_date\":\"" + DateTime.parse("2000-02-02").toString + "\"}" +
          "]}]"
      )
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames correctly including the Since with many updates to return to 2 roads Then returns status code 200 and a filled array with the info for the updates.") {
    when(mockRoadNameService.getUpdatedRoadNames(any[DateTime], any[Option[DateTime]])).thenReturn(
      Right(Seq(
        RoadName(4, 3, "ANOTHER ROAD", date(2017, 12, 12), None, date(2017, 12, 1), None, "MOCK"),
        RoadName(3, 2, "MY ROAD", date(2018, 2, 2), None, date(2017, 12, 1), None, "MOCK"),
        RoadName(2, 2, "THEROAD", date(2000, 2, 2), date(2018, 2, 2), date(2017, 12, 1), None, "MOCK"),
        RoadName(1, 2, "OLDROAD", date(1900, 2, 2), date(2000, 2, 2), date(1900, 1, 1), None, "MOCK")
      ))
    )
    getWithBasicUserAuth("/roadnames/changes?since=2017-12-01", "kalpa", "kalpa") {
      status should equal(200)
      response.body should equal(
        "[" +
          "{\"road_number\":2,\"names\":[" +
          "{\"change_date\":\"" + DateTime.parse("2017-12-01").toString + "\",\"road_name\":\"MY ROAD\",\"start_date\":\"" + DateTime.parse("2018-02-02").toString + "\",\"end_date\":null}," +
          "{\"change_date\":\"" + DateTime.parse("2017-12-01").toString + "\",\"road_name\":\"THEROAD\",\"start_date\":\"" + DateTime.parse("2000-02-02").toString + "\",\"end_date\":\"" + DateTime.parse("2018-02-02").toString + "\"}," +
          "{\"change_date\":\"" + DateTime.parse("1900-01-01").toString + "\",\"road_name\":\"OLDROAD\",\"start_date\":\"" + DateTime.parse("1900-02-02").toString + "\",\"end_date\":\"" + DateTime.parse("2000-02-02").toString + "\"}" +
          "]}," +
          "{\"road_number\":3,\"names\":[" +
          "{\"change_date\":\"" + DateTime.parse("2017-12-01").toString + "\",\"road_name\":\"ANOTHER ROAD\",\"start_date\":\"" + DateTime.parse("2017-12-12").toString + "\",\"end_date\":null}" +
          "]}" +
          "]"
      )
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames correctly including the Since with no updates to any road Then returns status code 200 and the returning array should be empty.") {
    when(mockRoadNameService.getUpdatedRoadNames(any[DateTime], any[Option[DateTime]])).thenReturn(Right(Seq()))
    getWithBasicUserAuth("/roadnames/changes?since=9999-01-01&until=9999-01-01", "kalpa", "kalpa") {
      status should equal(200)
      response.getHeader("Content-Type") should equal("application/json;charset=utf-8")
      response.body should equal("[]")
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames correctly including the Since with 1 change to a single road Then returns status code 200 and the returning array should contain that road info.") {
    when(mockRoadNameService.getUpdatedRoadNames(any[DateTime], any[Option[DateTime]])).thenReturn(
      Right(Seq(
        RoadName(1, 2, "MYROAD", date(2018, 2, 2), None, date(2018, 1, 1), None, "MOCK")
      ))
    )
    getWithBasicUserAuth("/roadnames/changes?since=2018-01-01&until=2018-01-03", "kalpa", "kalpa") {
      status should equal(200)
      response.body should equal(
        "[{\"road_number\":2,\"names\":[{\"change_date\":\"" + DateTime.parse("2018-01-01").toString + "\",\"road_name\":\"MYROAD\",\"start_date\":\"" + DateTime.parse("2018-02-02").toString + "\",\"end_date\":null}]}]"
      )
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames correctly including the Since with some changes to a single road Then returns status code 200 and the returning array should contain the multiple road info.") {
    when(mockRoadNameService.getUpdatedRoadNames(any[DateTime], any[Option[DateTime]])).thenReturn(
      Right(Seq(
        RoadName(3, 2, "MY ROAD", date(2018, 2, 2), None, date(2018, 1, 1), None, "MOCK"),
        RoadName(2, 2, "THEROAD", date(2000, 2, 2), date(2018, 2, 2), date(2018, 1, 1), None, "MOCK"),
        RoadName(1, 2, "OLDROAD", date(1900, 2, 2), date(2000, 2, 2), date(1900, 1, 1), None, "MOCK")
      ))
    )
    getWithBasicUserAuth("/roadnames/changes?since=2018-01-01&until=2018-01-03", "kalpa", "kalpa") {
      status should equal(200)
      response.body should equal(
        "[{\"road_number\":2,\"names\":[" +
          "{\"change_date\":\"" + DateTime.parse("2018-01-01").toString + "\",\"road_name\":\"MY ROAD\",\"start_date\":\"" + DateTime.parse("2018-02-02").toString + "\",\"end_date\":null}," +
          "{\"change_date\":\"" + DateTime.parse("2018-01-01").toString + "\",\"road_name\":\"THEROAD\",\"start_date\":\"" + DateTime.parse("2000-02-02").toString + "\",\"end_date\":\"" + DateTime.parse("2018-02-02").toString + "\"}," +
          "{\"change_date\":\"" + DateTime.parse("1900-01-01").toString + "\",\"road_name\":\"OLDROAD\",\"start_date\":\"" + DateTime.parse("1900-02-02").toString + "\",\"end_date\":\"" + DateTime.parse("2000-02-02").toString + "\"}" +
          "]}]"
      )
    }
  }

  test("Test getWithBasicUserAuth() When asking for changes in the roadnames correctly including the Since with some changes to multiple roads Then returns status code 200 and the returning array should contain the multiple road info.") {
    when(mockRoadNameService.getUpdatedRoadNames(any[DateTime], any[Option[DateTime]])).thenReturn(
      Right(Seq(
        RoadName(4, 3, "ANOTHER ROAD", date(2017, 12, 12), None, date(2017, 12, 1), None, "MOCK"),
        RoadName(3, 2, "MY ROAD", date(2018, 2, 2), None, date(2017, 12, 1), None, "MOCK"),
        RoadName(2, 2, "THEROAD", date(2000, 2, 2), date(2018, 2, 2), date(2017, 12, 1), None, "MOCK"),
        RoadName(1, 2, "OLDROAD", date(1900, 2, 2), date(2000, 2, 2), date(1900, 1, 1), None, "MOCK")
      ))
    )
    getWithBasicUserAuth("/roadnames/changes?since=2017-12-01&until=2018-12-03", "kalpa", "kalpa") {
      status should equal(200)
      response.body should equal(
        "[" +
          "{\"road_number\":2,\"names\":[" +
          "{\"change_date\":\"" + DateTime.parse("2017-12-01").toString + "\",\"road_name\":\"MY ROAD\",\"start_date\":\"" + DateTime.parse("2018-02-02").toString + "\",\"end_date\":null}," +
          "{\"change_date\":\"" + DateTime.parse("2017-12-01").toString + "\",\"road_name\":\"THEROAD\",\"start_date\":\"" + DateTime.parse("2000-02-02").toString + "\",\"end_date\":\"" + DateTime.parse("2018-02-02").toString + "\"}," +
          "{\"change_date\":\"" + DateTime.parse("1900-01-01").toString + "\",\"road_name\":\"OLDROAD\",\"start_date\":\"" + DateTime.parse("1900-02-02").toString + "\",\"end_date\":\"" + DateTime.parse("2000-02-02").toString + "\"}" +
          "]}," +
          "{\"road_number\":3,\"names\":[" +
          "{\"change_date\":\"" + DateTime.parse("2017-12-01").toString + "\",\"road_name\":\"ANOTHER ROAD\",\"start_date\":\"" + DateTime.parse("2017-12-12").toString + "\",\"end_date\":null}" +
          "]}" +
          "]"
      )
    }
  }
}
