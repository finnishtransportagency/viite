package fi.liikennevirasto.viite

import java.net.ConnectException
import java.util.Properties

import fi.liikennevirasto.viite.Dummies.dummyRoadwayChangeSection
import fi.liikennevirasto.viite.RoadType.PublicRoad
import fi.liikennevirasto.viite.dao.Discontinuity.Continuous
import fi.liikennevirasto.viite.dao._
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.methods.HttpGet
import org.apache.http.conn.{ConnectTimeoutException, HttpHostConnectException}
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.util.EntityUtils
import org.joda.time.DateTime
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization
import org.json4s.{DefaultFormats, StreamInput, StringInput}
import org.scalatest.{FunSuite, Matchers}

/**
  * Created by alapeijario on 17.5.2017.
  */
class ViiteTierekisteriClientSpec extends FunSuite with Matchers {

  val dr2properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }
  val defaultChangeInfo = RoadwayChangeInfo(AddressChangeType.apply(2),
    RoadwayChangeSection(None, None, None, None, None, None, None, None, None),
    RoadwayChangeSection(Option(403), Option(0), Option(8), Option(0), Option(8), Option(1001),
      Option(RoadType.PublicRoad), Option(Discontinuity.Continuous), Option(5)), Discontinuity.apply(1), RoadType.apply(1), false, 1)

  def getRestEndPoint: String = {
    val loadedKeyString = dr2properties.getProperty("digiroad2.tierekisteriViiteRestApiEndPoint", "http://localhost:8080/api/tierekisteri/")
    if (loadedKeyString == null)
      throw new IllegalArgumentException("Missing TierekisteriViiteRestApiEndPoint")
    loadedKeyString
  }

  private def testConnection: Boolean = {
    // If you get NPE here, you have not included main module (digiroad2) as a test dependency to digiroad2-viite
    val url = dr2properties.getProperty("digiroad2.tierekisteriViiteRestApiEndPoint", "http://localhost:8080/api/tierekisteri/")
    val request = new HttpGet(url)
    request.setConfig(RequestConfig.custom().setConnectTimeout(2500).build())
    val client = HttpClientBuilder.create().build()
    try {
      val response = client.execute(request)
      try {
        response.getStatusLine.getStatusCode >= 200
      } finally {
        response.close()
      }
    } catch {
      case e: HttpHostConnectException =>
        false
      case e: ConnectTimeoutException =>
        false
      case e: ConnectException =>
        false
    }
  }

  test("Test ViiteTierekisteriClient.sendJsonMessage() When sending a ChangeProject Id = 0 meaning the project will not be added to TR Then return statusCode 201 and \"Created\" as the reason.") {
    assume(testConnection)
    val message = ViiteTierekisteriClient.sendJsonMessage(ChangeProject(0, "Testproject", "TestUser", "2017-06-01", Seq {
      defaultChangeInfo // projectid 0 wont be added to TR
    }))
    message.projectId should be(0)
    message.status should be(201)
    message.reason should startWith("Created")
  }

  test("Test ViiteTierekisteriClient.getProjectStatus() When asking for the test project ID (0) Then return a positive response object from TR") {
    assume(testConnection)
    val response = ViiteTierekisteriClient.getProjectStatus(0)
    response == null should be(false)
  }

  test("Test ViiteTierekisteriClient.convertToChangeProject() When sending a default ProjectRoadwayChange Then check that project_id is replaced with tr_id attribute") {
    val change = ViiteTierekisteriClient.convertToChangeProject(List(ProjectRoadwayChange(100L, Some("testproject"), 1, "user", DateTime.now(), defaultChangeInfo, DateTime.now(), Some(2))))
    change.id should be(2)
  }
  test("Test extract[RoadwayChangeSection] When using a ChangeInfoRoadPart from a JSON string Then return the correctly formed RoadwayChangeSection object.") {
    val string = "{" +
      "\"tie\": 1," +
      "\"ajr\": 0," +
      "\"aosa\": 10," +
      "\"aet\":0," +
      "\"losa\": 10," +
      "\"let\": 1052" +
      "}"
    implicit val formats = DefaultFormats + ChangeInfoRoadPartsSerializer
    val cirp = parse(StringInput(string)).extract[RoadwayChangeSection]
    cirp.roadNumber should be(Some(1))
    cirp.trackCode should be(Some(0))
    cirp.startRoadPartNumber should be(Some(10))
    cirp.startAddressM should be(Some(0))
    cirp.endRoadPartNumber should be(Some(10))
    cirp.endAddressM should be(Some(1052))
  }

  test("Test extract[TRProjectStatus] When using a serialized TRProjectStatus object to JSON Then return the correctly encoded and decoded TRProjectStatus object.") {
    implicit val formats = DefaultFormats + TRProjectStatusSerializer
    val trps = TRProjectStatus(Some(1), Some(2), Some(3), Some(4), Some("status"), Some("name"), Some("change_date"),
      Some(5), Some("muutospvm"), Some("user"), Some("published_date"), Some(6), Some("error_message"), Some("start_time"),
      Some("end_time"), Some(7))
    val string = Serialization.write(trps).toString
    val trps2 = parse(StringInput(string)).extract[TRProjectStatus]
    trps2 should be(trps)
  }

  test("Test extract[TRProjectStatus] When using a example JSON message representing the TRStatus Then return the correct TRStatus.") {
    implicit val formats = DefaultFormats + TRProjectStatusSerializer
    val string = "{\n\"id_tr_projekti\": 1162,\n\"projekti\": 1731,\n\"id\": 13255,\n\"tunnus\": 5,\n\"status\": \"T\"," +
      "\n\"name\": \"test\",\n\"change_date\": \"2017-06-01\",\n\"ely\": 1,\n\"muutospvm\": \"2017-05-15\",\n\"user\": \"user\"," +
      "\n\"published_date\": \"2017-05-15\",\n\"job_number\": 28,\n\"error_message\": null,\n\"start_time\": \"2017-05-15\"," +
      "\n\"end_time\": \"2017-05-15\",\n\"error_code\": 0\n}"
    parse(StringInput(string)).extract[TRProjectStatus]
  }
  test("Test extract[ChangeProject] When using a example JSON message representing the ChangeInfo Then return the correct ChangeInfo.") {
    implicit val formats = DefaultFormats + TRProjectStatusSerializer + ChangeInfoItemSerializer + ChangeProjectSerializer
    val string = "{\n\t\"id\": 8914,\n\t\"name\": \"Numerointi\",\n\t\"user\": \"user\",\n\t\"change_date\": \"2017-06-01\"," +
      " \n\t\"change_info\":[ {\n\t\t\"change_type\": 4,\n\t\t\"source\": {\n\t\t\t\"tie\": 11007," +
      "\n\t\t\t\"ajr\": 0,\n\t\t\t\"aosa\": 2,\n\t\t\t\"aet\": 0,\n\t\t\t\"losa\": 2,\n\t\t\t\"let\": 1895\n\t\t}," +
      "\n\t\t\"target\": {\n\t\t\t\"tie\": 11007,\n\t\t\t\"ajr\": 0,\n\t\t\t\"aosa\": 1,\n\t\t\t\"aet\": 3616," +
      "\n\t\t\t\"losa\": 1,\n\t\t\t\"let\": 5511\n\t\t},\n\t\t\"continuity\": 5,\n\t\t\"road_type\": 821,\n\t\"ely\": 9\n\t}]\n}"
    parse(StringInput(string)).extract[ChangeProject]
  }

  test("Test extract[ChangeProject] and ViiteTierekisteriClient.createJsonMessage() When coding and Decoding a ChangeProject object back and forth Then return the correct ChangeProject object after the coding/decoding process.") {
    implicit val formats = DefaultFormats + TRProjectStatusSerializer + ChangeInfoItemSerializer + ChangeProjectSerializer
    val string = "{\n\t\"id\": 8914,\n\t\"name\": \"Numerointi\",\n\t\"user\": \"user\",\n\t\"change_date\": \"2017-06-01\"," +
      " \n\t\"change_info\":[ {\n\t\t\"change_type\": 4,\n\t\t\"source\": {\n\t\t\t\"tie\": 11007," +
      "\n\t\t\t\"ajr\": 0,\n\t\t\t\"aosa\": 2,\n\t\t\t\"aet\": 0,\n\t\t\t\"losa\": 2,\n\t\t\t\"let\": 1895\n\t\t}," +
      "\n\t\t\"target\": {\n\t\t\t\"tie\": 11007,\n\t\t\t\"ajr\": 0,\n\t\t\t\"aosa\": 1,\n\t\t\t\"aet\": 3616," +
      "\n\t\t\t\"losa\": 1,\n\t\t\t\"let\": 5511\n\t\t},\n\t\t\"continuity\": 5,\n\t\t\"road_type\": 821,\n\t\"ely\": 9\n\t}]\n}"
    val parsedProject = parse(StringInput(string)).extract[ChangeProject]
    val reparsed = parse(StreamInput(ViiteTierekisteriClient.createJsonMessage(parsedProject).getContent)).extract[ChangeProject]
    parsedProject should be(reparsed)
    reparsed.id should be(8914)
    reparsed.changeDate should be("2017-06-01")
    reparsed.changeInfoSeq should have size (1)
  }

  test("Test createJsonMessage to check if it returns the JSON with the ely codes inside the change_info objects") {
    val projectId = 99999999999L
    val roadNumber = 99999
    val oldEly = 8L
    val newEly = 9L

    val changeInfos = List(
      RoadwayChangeInfo(AddressChangeType.Unchanged,
        source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(oldEly)),
        target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(newEly)),
        Continuous, RoadType.apply(1), reversed = false, 1, newEly),
      RoadwayChangeInfo(AddressChangeType.ReNumeration,
        source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(oldEly)),
        target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(newEly)),
        Continuous, RoadType.apply(1), reversed = false, 1, newEly),
      RoadwayChangeInfo(AddressChangeType.Transfer,
        source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(oldEly)),
        target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(newEly)),
        Continuous, RoadType.apply(1), reversed = false, 1, newEly),
      RoadwayChangeInfo(AddressChangeType.New,
        source = null,
        target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(newEly)),
        Continuous, RoadType.apply(1), reversed = false, 1, newEly),
      RoadwayChangeInfo(AddressChangeType.Termination,
        source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(oldEly)),
        target = null,
        Continuous, RoadType.apply(1), reversed = false, 1, oldEly)
    )

    val changes = List(
      ProjectRoadwayChange(projectId, Some("testproject"), 1, "user", DateTime.now(), changeInfos.head, DateTime.now(), Some(2)),
      ProjectRoadwayChange(projectId, Some("testproject"), 1, "user", DateTime.now(), changeInfos(1), DateTime.now(), Some(2)),
      ProjectRoadwayChange(projectId, Some("testproject"), 1, "user", DateTime.now(), changeInfos(2), DateTime.now(), Some(2)),
      ProjectRoadwayChange(projectId, Some("testproject"), 1, "user", DateTime.now(), changeInfos(3), DateTime.now(), Some(2)),
      ProjectRoadwayChange(projectId, Some("testproject"), 1, "user", DateTime.now(), changeInfos.last, DateTime.now(), Some(2))
    )

    val changeProject = ViiteTierekisteriClient.convertToChangeProject(changes)
    val jsonMsg = ViiteTierekisteriClient.createJsonMessage(changeProject)
    implicit val formats = DefaultFormats + ChangeInfoRoadPartsSerializer + ChangeInfoItemSerializer + ChangeProjectSerializer
    val reparsed = parse(StreamInput(jsonMsg.getContent)).extract[ChangeProject]
    reparsed.changeInfoSeq.size should be(changes.size)
    reparsed.changeInfoSeq.foreach(ci => {
      if(AddressChangeType.Termination.value == ci.changeType.value)
        ci.ely should be(oldEly)
      else
        ci.ely should be(newEly)
    })

  }

  test("Test extract[ChangeProject] and ViiteTierekisteriClient.createJsonMessage() When coding and Decoding a ChangeProject object with multiple changes Then return the correct ChangeProject object after the coding/decoding process. ") {
    implicit val formats = DefaultFormats + TRProjectStatusSerializer + ChangeInfoItemSerializer + ChangeProjectSerializer
    val string = "{\n\t\"id\": 8914,\n\t\"name\": \"Numerointi\",\n\t\"user\": \"user\",\n\t\"change_date\": \"2017-06-01\"," +
      " \n\t\"change_info\":[ {\n\t\t\"change_type\": 4,\n\t\t\"source\": {\n\t\t\t\"tie\": 11007," +
      "\n\t\t\t\"ajr\": 0,\n\t\t\t\"aosa\": 2,\n\t\t\t\"aet\": 0,\n\t\t\t\"losa\": 2,\n\t\t\t\"let\": 1895\n\t\t}," +
      "\n\t\t\"target\": {\n\t\t\t\"tie\": 11007,\n\t\t\t\"ajr\": 0,\n\t\t\t\"aosa\": 1,\n\t\t\t\"aet\": 3616," +
      "\n\t\t\t\"losa\": 1,\n\t\t\t\"let\": 5511\n\t\t},\n\t\t\"continuity\": 5,\n\t\t\"road_type\": 821,\n\t\"ely\": 9\n\t}," +
      "{\n\t\t\"change_type\": 4,\n\t\t\"source\": {\n\t\t\t\"tie\": 11007," +
      "\n\t\t\t\"ajr\": 0,\n\t\t\t\"aosa\": 3,\n\t\t\t\"aet\": 0,\n\t\t\t\"losa\": 3,\n\t\t\t\"let\": 95\n\t\t}," +
      "\n\t\t\"target\": {\n\t\t\t\"tie\": 11007,\n\t\t\t\"ajr\": 0,\n\t\t\t\"aosa\": 1,\n\t\t\t\"aet\": 5511," +
      "\n\t\t\t\"losa\": 1,\n\t\t\t\"let\": 5606\n\t\t},\n\t\t\"continuity\": 1,\n\t\t\"road_type\": 821,\n\t\"ely\": 9\n\t}" +
      "]\n}"
    val parsedProject = parse(StringInput(string)).extract[ChangeProject]
    val reparsed = parse(StreamInput(ViiteTierekisteriClient.createJsonMessage(parsedProject).getContent)).extract[ChangeProject]
    parsedProject should be(reparsed)
    reparsed.changeInfoSeq should have size (2)
    val part2 = reparsed.changeInfoSeq.find(_.source.startRoadPartNumber.get == 2)
    val part3 = reparsed.changeInfoSeq.find(_.source.startRoadPartNumber.get == 3)
    part2.nonEmpty should be(true)
    part3.nonEmpty should be(true)
  }

  test("Test ViiteTierekisteriClient.convertToChangeProject() The change table ely codes should be the ones in the change infos") {
    val projectId = 99999999999L
    val roadNumber = 99999
    val oldEly = 8L
    val newEly = 9L

    val changeInfos = List(
      RoadwayChangeInfo(AddressChangeType.Unchanged,
        source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(oldEly)),
        target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(newEly)),
        Continuous, RoadType.apply(1), reversed = false, 1),
      RoadwayChangeInfo(AddressChangeType.ReNumeration,
        source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(oldEly)),
        target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(newEly)),
        Continuous, RoadType.apply(1), reversed = false, 1),
      RoadwayChangeInfo(AddressChangeType.Transfer,
        source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(oldEly)),
        target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(newEly)),
        Continuous, RoadType.apply(1), reversed = false, 1),
      RoadwayChangeInfo(AddressChangeType.New,
        source = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(0L), Some(100L), Some(RoadType.apply(1)), Some(Discontinuity.Continuous), Some(oldEly)),
        target = dummyRoadwayChangeSection(Some(roadNumber), Some(1L), Some(0L), Some(100L), Some(200L), Some(RoadType.apply(5)), Some(Discontinuity.Continuous), Some(newEly)),
        Continuous, RoadType.apply(1), reversed = false, 1)
    )

    val changes = List(
      ProjectRoadwayChange(projectId, Some("testproject"), 1, "user", DateTime.now(), changeInfos.head, DateTime.now(), Some(2)),
      ProjectRoadwayChange(projectId, Some("testproject"), 1, "user", DateTime.now(), changeInfos(1), DateTime.now(), Some(2)),
      ProjectRoadwayChange(projectId, Some("testproject"), 1, "user", DateTime.now(), changeInfos(2), DateTime.now(), Some(2)),
      ProjectRoadwayChange(projectId, Some("testproject"), 1, "user", DateTime.now(), changeInfos.last, DateTime.now(), Some(2))
    )

    val changeProject = ViiteTierekisteriClient.convertToChangeProject(changes)
    changeProject.changeInfoSeq.foreach(cis => {
      cis.source.ely.get should be(oldEly)
      cis.target.ely.get should be(newEly)
    })
  }
}
