package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.service.RoadLinkType.NormalRoadLinkType
import fi.liikennevirasto.viite.dao.{CalibrationPoint, RoadName}
import fi.liikennevirasto.viite.model.{Anomaly, RoadAddressLink}
import fi.liikennevirasto.viite.{RoadAddressService, RoadNameService, RoadType}
import org.apache.commons.codec.binary.Base64
import org.joda.time.DateTime
import org.json4s.{DefaultFormats, Formats}
import org.mockito.Matchers
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Tag}
import org.scalatra.test.scalatest.ScalatraSuite


class ViiteIntegrationApiSpec extends FunSuite with ScalatraSuite with BeforeAndAfter{
  protected implicit val jsonFormats: Formats = DefaultFormats

  val mockRoadAddressService = MockitoSugar.mock[RoadAddressService]
  when(mockRoadAddressService.getRoadAddressesLinkByMunicipality(235)).thenReturn(Seq())

  val mockRoadNameService = MockitoSugar.mock[RoadNameService]

  private val integrationApi = new ViiteIntegrationApi(mockRoadAddressService, mockRoadNameService)
  addServlet(integrationApi, "/*")

  def getWithBasicUserAuth[A](uri: String, username: String, password: String)(f: => A): A = {
    val credentials = username + ":" + password
    val encodedCredentials = Base64.encodeBase64URLSafeString(credentials.getBytes)
    val authorizationToken = "Basic " + encodedCredentials + "="
    get(uri, Seq.empty, Map("Authorization" -> authorizationToken))(f)
  }

  before {
    integrationApi.clearCache()
  }
  after {
    integrationApi.clearCache()
  }

  test("Should require correct authentication", Tag("db")) {
    get("/road_address") {
      status should equal(401)
    }
    getWithBasicUserAuth("/road_address", "nonexisting", "incorrect") {
      status should equal(401)
    }
  }

  test("Get road address requires municipality number") {
    getWithBasicUserAuth("/road_address", "kalpa", "kalpa") {
      status should equal(400)
    }
    getWithBasicUserAuth("/road_address?municipality=235", "kalpa", "kalpa") {
      status should equal(200)
    }
  }

  test("encode road address") {
    val geometry = Seq(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.5), Point(4.0, 4.0, 1.5))
    val roadAdressLink = RoadAddressLink(63298, 5171208, geometry, GeometryUtils.geometryLength(geometry), Municipality,
      UnknownLinkType, NormalRoadLinkType, InUse, NormalLinkInterface, RoadType.MunicipalityStreetRoad, Some("Vt5"),
      None, BigInt(0), None, None, Map("linkId" -> 5171208, "segmentId" -> 63298), 5, 205, 1, 0, 0, 0, 6, "2015-01-01",
      "2016-01-01", 0.0, 0.0, SideCode.TowardsDigitizing, Some(CalibrationPoint(120, 1, 2)), None, Anomaly.None, 0, floating = false)
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

  test("geometryWKTForLinearAssets provides proper geometry") {
    val (header, returnTxt) =
      integrationApi.geometryWKT(Seq(Point(0.0, 0.0, 0.0), Point(1.0, 0.0, 0.5), Point(4.0, 4.0, 1.5)), 0L, 6L)
    header should be ("geometryWKT")
    returnTxt should be ("LINESTRING ZM (0.000 0.000 0.000 0.000, 1.000 0.000 0.500 1.000, 4.000 4.000 1.500 6.000)")
  }

  def date(year: Int, month: Int, day: Int): Option[DateTime] = {
    Option(new DateTime(year, month, day, 0, 0, 0))
  }

  test("Missing muutospvm parameter should return error") {
    getWithBasicUserAuth("/tienimi/paivitetyt", "kalpa", "kalpa") {
      status should equal(400)
    }
  }

  test("Empty muutospvm parameter should return error") {
    getWithBasicUserAuth("/tienimi/paivitetyt?muutospvm=", "kalpa", "kalpa") {
      status should equal(400)
    }
  }

  test("Incorrect muutospvm parameter should return error") {
    getWithBasicUserAuth("/tienimi/paivitetyt?muutospvm=abc", "kalpa", "kalpa") {
      status should equal(400)
    }
  }

  test("Incorrect date format in muutospvm parameter should return error") {
    getWithBasicUserAuth("/tienimi/paivitetyt?muutospvm=1.1.2018", "kalpa", "kalpa") {
      status should equal(400)
    }
  }

  test("No updated road names, Content-Type should be application/json and response should be empty array") {
    when(mockRoadNameService.getUpdatedRoadNames(Matchers.any())).thenReturn(Right(Seq()))
    getWithBasicUserAuth("/tienimi/paivitetyt?muutospvm=9999-01-01", "kalpa", "kalpa") {
      status should equal(200)
      response.getHeader("Content-Type") should equal("application/json; charset=UTF-8")
      response.body should equal("[]")
    }
  }

  test("One update (new road), response should contain one road name") {
    when(mockRoadNameService.getUpdatedRoadNames(Matchers.any())).thenReturn(
      Right(Seq(
        RoadName(1, 2, "MYROAD", date(2018, 2, 2), None, date(2018, 1, 1), None, "MOCK")
      ))
    )
    getWithBasicUserAuth("/tienimi/paivitetyt?muutospvm=2018-01-01", "kalpa", "kalpa") {
      status should equal(200)
      response.body should equal(
        "[{\"tie\":2,\"tienimet\":[{\"muutospvm\":\"2018-01-01\",\"tienimi\":\"MYROAD\",\"voimassaolo_alku\":\"2018-02-02\",\"voimassaolo_loppu\":null}]}]"
      )
    }
  }

  test("Road name change") {
    when(mockRoadNameService.getUpdatedRoadNames(Matchers.any())).thenReturn(
      Right(Seq(
        RoadName(3, 2, "MY ROAD", date(2018, 2, 2), None, date(2018, 1, 1), None, "MOCK"),
        RoadName(2, 2, "THEROAD", date(2000, 2, 2), date(2018, 2, 2), date(2018, 1, 1), None, "MOCK"),
        RoadName(1, 2, "OLDROAD", date(1900, 2, 2), date(2000, 2, 2), date(1900, 1, 1), None, "MOCK")
      ))
    )
    getWithBasicUserAuth("/tienimi/paivitetyt?muutospvm=2018-01-01", "kalpa", "kalpa") {
      status should equal(200)
      response.body should equal(
        "[{\"tie\":2,\"tienimet\":[" +
          "{\"muutospvm\":\"2018-01-01\",\"tienimi\":\"MY ROAD\",\"voimassaolo_alku\":\"2018-02-02\",\"voimassaolo_loppu\":null}," +
          "{\"muutospvm\":\"2018-01-01\",\"tienimi\":\"THEROAD\",\"voimassaolo_alku\":\"2000-02-02\",\"voimassaolo_loppu\":\"2018-02-02\"}," +
          "{\"muutospvm\":\"1900-01-01\",\"tienimi\":\"OLDROAD\",\"voimassaolo_alku\":\"1900-02-02\",\"voimassaolo_loppu\":\"2000-02-02\"}" +
          "]}]"
      )
    }
  }

  test("Road name change for two roads") {
    when(mockRoadNameService.getUpdatedRoadNames(Matchers.any())).thenReturn(
      Right(Seq(
        RoadName(4, 3, "ANOTHER ROAD", date(2017, 12, 12), None, date(2017, 12, 1), None, "MOCK"),
        RoadName(3, 2, "MY ROAD", date(2018, 2, 2), None, date(2017, 12, 1), None, "MOCK"),
        RoadName(2, 2, "THEROAD", date(2000, 2, 2), date(2018, 2, 2), date(2017, 12, 1), None, "MOCK"),
        RoadName(1, 2, "OLDROAD", date(1900, 2, 2), date(2000, 2, 2), date(1900, 1, 1), None, "MOCK")
      ))
    )
    getWithBasicUserAuth("/tienimi/paivitetyt?muutospvm=2017-12-01", "kalpa", "kalpa") {
      status should equal(200)
      response.body should equal(
        "[" +
          "{\"tie\":2,\"tienimet\":[" +
          "{\"muutospvm\":\"2017-12-01\",\"tienimi\":\"MY ROAD\",\"voimassaolo_alku\":\"2018-02-02\",\"voimassaolo_loppu\":null}," +
          "{\"muutospvm\":\"2017-12-01\",\"tienimi\":\"THEROAD\",\"voimassaolo_alku\":\"2000-02-02\",\"voimassaolo_loppu\":\"2018-02-02\"}," +
          "{\"muutospvm\":\"1900-01-01\",\"tienimi\":\"OLDROAD\",\"voimassaolo_alku\":\"1900-02-02\",\"voimassaolo_loppu\":\"2000-02-02\"}" +
          "]}," +
          "{\"tie\":3,\"tienimet\":[" +
          "{\"muutospvm\":\"2017-12-01\",\"tienimi\":\"ANOTHER ROAD\",\"voimassaolo_alku\":\"2017-12-12\",\"voimassaolo_loppu\":null}" +
          "]}" +
          "]"
      )
    }
  }
}
