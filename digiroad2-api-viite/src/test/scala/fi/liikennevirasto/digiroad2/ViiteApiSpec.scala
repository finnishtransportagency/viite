package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, TrafficDirection}
import fi.liikennevirasto.digiroad2.client.kgv.{KgvRoadLink, KgvRoadLinkClient}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.user.{Configuration, User}
import fi.liikennevirasto.digiroad2.Digiroad2Context._
import fi.liikennevirasto.digiroad2.asset.AdministrativeClass.State
import fi.liikennevirasto.digiroad2.asset.LifecycleStatus.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.FrozenLinkInterface
import fi.liikennevirasto.digiroad2.dao.ComplementaryLinkDAO
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.viite.{NodesAndJunctionsService, PreFillInfo, ProjectService, RoadAddressService, RoadNameService, RoadNameSource, RoadNetworkService, ViiteVkmClient}
import fi.liikennevirasto.viite.dao.{ProjectLinkDAO, ProjectLinkNameDAO}
import fi.liikennevirasto.viite.util.{runWithRollback, DigiroadSerializers, JsonSerializer}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.read
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.scalatest.mockito.MockitoSugar
import org.scalatra.test.scalatest.ScalatraSuite

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ViiteApiSpec extends FunSuite with ScalatraSuite with BeforeAndAfter {
  protected implicit val jsonFormats: Formats = DigiroadSerializers.jsonFormats

  val useFrozenLinkInterface = true
  val mockRoadLinkService   : RoadLinkService             = MockitoSugar.mock[RoadLinkService]
  val mockKgvRoadLink       : KgvRoadLink                 = MockitoSugar.mock[KgvRoadLink]
  val frozenTimeRoadLinkData: KgvRoadLinkClient[RoadLink] = MockitoSugar.mock[KgvRoadLinkClient[RoadLink]]
  val complementaryData     : ComplementaryLinkDAO        = MockitoSugar.mock[ComplementaryLinkDAO]

  val mockProjectService          : ProjectService           = MockitoSugar.mock[ProjectService]
  val mockRoadNetworkService      : RoadNetworkService       = MockitoSugar.mock[RoadNetworkService]
  val mockNodesAndJunctionsService: NodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val roadNameService             : RoadNameService          = new RoadNameService { override def withDynTransaction[T](f: => T): T = runWithRollback(f) }

  val mockViiteVkmClient: ViiteVkmClient = MockitoSugar.mock[ViiteVkmClient]

  val preFilledRoadName = PreFillInfo(1, 2, "roadName", RoadNameSource.RoadAddressSource, -1)
  private val testProjectId = roadNameService.withDynSession { projectDAO.fetchAll().head.id }

  when(mockProjectService.fetchPreFillData("6117675", testProjectId)).thenReturn(Right(preFilledRoadName))
  when(mockKgvRoadLink.frozenTimeRoadLinkData).thenReturn(frozenTimeRoadLinkData)
  when(mockKgvRoadLink.complementaryData).thenReturn(complementaryData)

  val testRoadLink  = RoadLink("6117675", Seq(Point(6975409, 527825, 85.90899999999965), Point(6975409, 528516)), 691.186, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, FrozenLinkInterface, 749, "")
  val mockRoadLink  = RoadLink("3236208", Seq(Point(506770.557, 6824285.205), Point(507110.817, 6824195.643)), 368.357, State, TrafficDirection.TowardsDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, FrozenLinkInterface, 749, "")
  val mockRoadLink2 = RoadLink("f4745622-bfed-4ebd-aa32-aadeefe6289e:1", Seq(Point(534583.06,6994860.59,96.545),Point(534622.935,6994867.342,96.677),Point(534648.202,6994871.385,96.554),Point(534670.801,6994874.999,96.471),Point(534680.071,6994876.473,96.493),Point(534681.014,6994876.676,96.505),Point(534702.96,6994880.636,96.925),Point(534734.215,6994886.084,97.63),Point(534756.75,6994890.217,98.151)), 691.186, State, TrafficDirection.AgainstDigitizing, Some("25.06.2015 03:00:00"), Some("vvh_modified"), InUse, FrozenLinkInterface, 749, "")

  when(frozenTimeRoadLinkData.fetchByLinkId("6117675")).thenReturn(Some(testRoadLink))
  when(frozenTimeRoadLinkData.fetchByLinkId("f4745622-bfed-4ebd-aa32-aadeefe6289e:1")).thenReturn(Some(mockRoadLink2))
  when(frozenTimeRoadLinkData.fetchByLinkIds(any[Set[String]])).thenReturn(Seq(mockRoadLink))
  when(frozenTimeRoadLinkData.fetchByLinkIdsF(any[Set[String]])).thenReturn(Future(Seq(testRoadLink)))

  when(complementaryData.fetchByLinkIdsF(any[Set[String]])).thenReturn(Future(Seq()))
  when(complementaryData.fetchByLinkIds(any[Set[String]])).thenReturn(List())
  when(complementaryData.fetchByBoundsAndMunicipalitiesF(any[BoundingRectangle],any[Set[Int]])).thenReturn(Future(Seq()))
  when(frozenTimeRoadLinkData.fetchBySourceId(any[Long])).thenReturn(Some(testRoadLink))
  when(frozenTimeRoadLinkData.fetchByRoadNumbersBoundsAndMunicipalitiesF(any[BoundingRectangle], any[Set[Int]], any[Seq[(Int, Int)]], any[Boolean])).thenReturn(Future(Seq(testRoadLink)))

  when(mockViiteVkmClient.get(any[String], any[Map[String, String]])).thenReturn(Left("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"x":510780.954,"kuntakoodi":75,"katunumero":1,"y":6714920.766500175,"kuntanimi_se":"Fredrikshamn","elynimi":"Kaakkois-Suomi","hallinnollinen_luokka":2,"katunimi":"Mannerheimintie","maakuntanimi_se":"Kymmenedalen","kuntanimi":"Hamina","vertikaalisuhde":0,"maakuntakoodi":8,"maakuntanimi":"Kymenlaakso","z":13.2705}},{"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"x":275178.084005079,"kuntakoodi":78,"katunimi_se":"Mannerheimvägen","katunumero":1,"y":6639111.276944768,"kuntanimi_se":"Hangö","elynimi":"Uusimaa","hallinnollinen_luokka":2,"katunimi":"Mannerheimintie","maakuntanimi_se":"Nyland","kuntanimi":"Hanko","vertikaalisuhde":0,"maakuntakoodi":1,"maakuntanimi":"Uusimaa","z":3.817}},{"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"x":385782.6517814581,"kuntakoodi":91,"tie":1,"vaylan_luonne":11,"katunimi_se":"Mannerheimvägen","ajorata":1,"katunumero":1,"y":6671898.5914367875,"kuntanimi_se":"Helsingfors","ualuenimi":"Kunta hoitaa","elynimi":"Uusimaa","hallinnollinen_luokka":2,"ely":1,"katunimi":"Mannerheimintie","maakuntanimi_se":"Nyland","kuntanimi":"Helsinki","vertikaalisuhde":0,"maakuntakoodi":1,"maakuntanimi":"Uusimaa","etaisyys":184,"osa":1,"ualue":400,"z":7.155061610986975}},{"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"x":254370.55402264866,"kuntakoodi":214,"katunumero":1,"y":6855368.466820078,"kuntanimi_se":"Kankaanpää","elynimi":"Varsinais-Suomi","hallinnollinen_luokka":3,"katunimi":"Mannerheimintie","maakuntanimi_se":"Satakunta","kuntanimi":"Kankaanpää","vertikaalisuhde":0,"maakuntakoodi":4,"maakuntanimi":"Satakunta","z":92.123}},{"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"x":436038.62699999765,"kuntakoodi":398,"katunumero":1,"y":6754094.406000189,"kuntanimi_se":"Lahtis","elynimi":"Uusimaa","hallinnollinen_luokka":3,"katunimi":"Mannerheimintie","maakuntanimi_se":"Päijät-Häme","kuntanimi":"Lahti","vertikaalisuhde":0,"maakuntakoodi":7,"maakuntanimi":"Päijät-Häme","z":92.253}},{"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"x":525223.594,"kuntakoodi":441,"katunumero":1,"y":6749626.399000189,"kuntanimi_se":"Luumäki","elynimi":"Kaakkois-Suomi","hallinnollinen_luokka":3,"katunimi":"Mannerheimintie","maakuntanimi_se":"Södra Karelen","kuntanimi":"Luumäki","vertikaalisuhde":0,"maakuntakoodi":9,"maakuntanimi":"Etelä-Karjala","z":85.718}},{"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"x":514494.7815168431,"kuntakoodi":491,"tie":45758,"vaylan_luonne":4,"ajorata":0,"katunumero":1,"y":6839008.613566632,"kuntanimi_se":"S:t Michel","ualuenimi":"Kunta hoitaa","elynimi":"Pohjois-Savo","hallinnollinen_luokka":2,"ely":8,"katunimi":"Mannerheimintie","maakuntanimi_se":"Södra Savolax","kuntanimi":"Mikkeli","vertikaalisuhde":0,"maakuntakoodi":10,"maakuntanimi":"Etelä-Savo","etaisyys":205,"osa":1,"ualue":400,"z":79.94520259865256}},{"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"x":291508.1730952337,"kuntakoodi":581,"katunumero":1,"y":6880609.717048872,"kuntanimi_se":"Parkano","elynimi":"Pirkanmaa","hallinnollinen_luokka":2,"katunimi":"Mannerheimintie","maakuntanimi_se":"Birkaland","kuntanimi":"Parkano","vertikaalisuhde":0,"maakuntakoodi":6,"maakuntanimi":"Pirkanmaa","z":129.3610862799476}},{"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"x":293877.4860021637,"kuntakoodi":710,"katunimi_se":"Mannerheimvägen","katunumero":1,"y":6649035.775971972,"kuntanimi_se":"Raseborg","elynimi":"Uusimaa","hallinnollinen_luokka":3,"katunimi":"Mannerheimintie","maakuntanimi_se":"Nyland","kuntanimi":"Raasepori","vertikaalisuhde":0,"maakuntakoodi":1,"maakuntanimi":"Uusimaa","z":20.732}},{"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"x":230847.9620801189,"kuntakoodi":905,"katunimi_se":"Mannerheimsvägen","katunumero":1,"y":7007581.801467923,"kuntanimi_se":"Vasa","elynimi":"Etelä-Pohjanmaa","hallinnollinen_luokka":2,"katunimi":"Mannerheimintie","maakuntanimi_se":"Österbotten","kuntanimi":"Vaasa","vertikaalisuhde":0,"maakuntakoodi":15,"maakuntanimi":"Pohjanmaa","z":13.009}}]}"""))

  val roadLinkService: RoadLinkService = new RoadLinkService(mockKgvRoadLink, eventbus, new JsonSerializer, useFrozenLinkInterface)
  val roadAddressService: RoadAddressService = new RoadAddressService(roadLinkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, roadwayAddressMapper, eventbus, useFrozenLinkInterface){
    override val viiteVkmClient = mockViiteVkmClient
  }

  val projectService: ProjectService = new ProjectService(roadAddressService, mockRoadLinkService, mockNodesAndJunctionsService, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, new ProjectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, eventbus, useFrozenLinkInterface) {
    override def withDynTransaction[T](f: => T): T = runWithRollback(f)
  }

    private val viiteApi = new ViiteApi(roadLinkService, mockKgvRoadLink, roadAddressService, projectService, mockRoadNetworkService, roadNameService, mockNodesAndJunctionsService, swagger = new ViiteSwagger)

  addServlet(viiteApi, "/*")

  test("Test /roadaddress API call returns some data in expected format for UI.") {
      get("/roadaddress?zoom=7&bbox=527800.0,6991096.0,538002.0,6992512.0") {
      status should equal(200)
      val links = read[Seq[Seq[Map[String, Any]]]](body)
      links should have size 2
      links.head.head should have size 33
    }
  }

  test("Test /roadaddress allroads API call returns some data in expected format for UI.") {
      get("/roadaddress?zoom=11&bbox=527800.0,6991096.0,538002.0,6992512.0") {
      status should equal(200)
      val links = read[Seq[Seq[Map[String, Any]]]](body)
      links should have size 1
      links.head.head should have size 33
    }
  }

  test("Test /roadaddress/linkid/:linkId API call returns json data.") {
    get("/roadaddress/linkid/6117675") {
      status should equal(200)
      body should equal("""{"modifiedAt":"25.06.2015 03:00:00","linkId":"6117675","startAddressM":0,"roadNameFi":"","roadPartNumber":0,"success":true,"endDate":"","linearLocationId":0,"roadwayNumber":0,"administrativeClassMML":"State","roadwayId":0,"municipalityCode":749,"middlePoint":{"x":6975409.0,"y":528167.8603886453,"z":43.282671305168655},"calibrationCode":0,"roadNumber":0,"trackCode":99,"roadClass":99,"sideCode":9,"points":[{"x":6975409.0,"y":527825.0,"z":85.90899999999965},{"x":6975409.0,"y":528516.0,"z":0.0}],"newGeometry":[{"x":6975409.0,"y":527825.0,"z":85.90899999999965},{"x":6975409.0,"y":528516.0,"z":0.0}],"municipalityName":"Siilinjärvi","startMValue":0.0,"endAddressM":0,"endMValue":691.0,"roadNameSe":"","calibrationPoints":[],"mmlId":"","startDate":"","modifiedBy":"kgv_modified","elyCode":8,"lifecycleStatus":3,"discontinuity":5,"administrativeClassId":1,"roadLinkSource":4}""")
    }
  }

  test("Test /roadlinks/project/prefill When given linkId and testProjectId with existing project and road Then should return succesfully with prefill data.") {
    get("/roadlinks/project/prefill?linkId=6117675&currentProjectId=7081807") {
      status should equal(200)
      body should equal("""{"roadPartNumber":1,"success":true,"roadNumber":77997,"ely":1,"roadName":"","roadNameSource":99}""")
    }
//    get("/roadlinks/project/prefill?linkId=6117675&currentProjectId=7081807") {
//      status should equal(200)
//      body should equal("{\"roadPartNumber\":1,\"success\":true,\"roadNumber\":77997,\"roadName\":\"\",\"roadNameSource\":99}")
//    }
  }

  test("Test /roadlinks/midpoint/:linkId") {
    get("/roadlinks/midpoint/6117675") {
      status should equal(200)
      body should equal("""{"x":6975409.0,"y":528167.9526781276,"z":43.271197358515636}""")
    }
  }

  test("Test /roadlinks/mtkid/:mtkId") {
    get("/roadlinks/mtkid/5170939") {
      status should equal(200)
      body should equal("""{"x":6975409.0,"y":528170.5,"z":0.0}""")
    }
  }

  test("Test /roadnames") {
    get("/roadnames?roadNumber=5") {
      status should equal(200)
      body should equal("""{"success":true,"roadNameInfo":[{"name":"HELSINKI-SODANKYLÄ","roadNumber":5,"id":1000000,"startDate":"01.01.1989, 00:00:00"}]}""")
    }
    get("/roadnames?roadName=%27PORI-TAMPERE%27") {
      status should equal(200)
      body should equal("""{"success":true,"roadNameInfo":[{"name":"PORI-TAMPERE","endDate":"30.09.2001, 00:00:00","roadNumber":11,"id":1000001,"startDate":"01.01.1996, 00:00:00"}]}""")
    }
  }

  test("Test /roadlinks/roadname/:roadNumber/:projectID") {
    roadAddressService.withDynSession {
      val testProjectRoadName = "Project_road_77998_name"
      ProjectLinkNameDAO.create(testProjectId, 77998, testProjectRoadName)
      // RoadName
      get("/roadlinks/roadname/5/7081807") {
        status should equal(200)
        body should equal("""{"roadName":"HELSINKI-SODANKYLÄ","isCurrent":true}""")
      }
      // ProjectLinkName
      get("/roadlinks/roadname/77998/7081807") {
        status should equal(200)
        body should equal("""{"roadName":"""" + testProjectRoadName + """","isCurrent":false}""")
      }
      ProjectLinkNameDAO.removeByProject(testProjectId)
    }
  }

  test("Test /roadlinks/search for linkId") {
    get("/roadlinks/search?search=f4745622-bfed-4ebd-aa32-aadeefe6289e:1") {
      status should equal(200)
      val links = read[Seq[Map[String, Seq[Any]]]](body)
      links should have size 1
      val linkId = links.flatMap(_.get("linkId")).flatten
      linkId should equal(List(Map("x" -> 534583.06, "y" -> 6994860.59, "z" -> 0.0)))
    }
  }

  test("Test /roadlinks/search for mtkId") {
    get("/roadlinks/search?search=5170939") {
      status should equal(200)
      val links = read[Seq[Map[String, Seq[Any]]]](body)
      links should have size 2
      val mtkId = links.flatMap(_.get("mtkId")).flatten
      mtkId should equal(List(Map("x" -> 6975409.0, "y" -> 528170.5, "z" -> 0.0)))
    }
  }

  test("Test /roadlinks/search for road,part,addr,track") {
    get("/roadlinks/search?search=15113,2,2760,0") {
      status should equal(200)
      val links = read[Seq[Map[String, Seq[Any]]]](body)
      links should have size 1
      val mtkId = links.flatMap(_.get("roadM")).flatten
      mtkId should equal(List(Map("x" -> 507034.82066520397, "y" -> 6824215.646478919, "z" -> 0.0)))
    }
  }

  test("Test /roadlinks/search for street") {
    get("/roadlinks/search?search=mannerheimintie") {
      status should equal(200)
      val links = read[Seq[Map[String, Seq[Any]]]](body)
      links should have size 1
      val street = links.flatMap(_.get("street")).flatten.head
      val features = parse(StringInput(street.toString)).values.asInstanceOf[Map[String, Any]]("features").asInstanceOf[Seq[Map[String, Map[String, Any]]]]
      features.filter(_.get("properties").get.get("kuntanimi").get == "Helsinki") should have size 1
    }
  }

  test("Test /getRoadLinkDate") {
    get("/getRoadLinkDate") {
      status should equal(200)
      body should equal("""{ "result":" 02.01.2018 02:00:00"}""")
    }
  }

  test("Test /project/recalculateProject/:testProjectId") {
    get("/project/recalculateProject/7081807") {
      status should equal(200)
      val response = parse(StringInput(body)).values.asInstanceOf[Map[String, Any]]
      response should have size 2
      response.head should be(("success",true))

      /* Projektilla kaksi validointi virhettä:
          Huom! Ajoratojen geometriapituuksissa yli 20% poikkeama. +
          Tieosalle ei ole määritelty jatkuvuuskoodia "Tien loppu" (1), tieosan viimeiselle linkille.
      */
      response.last._1 should be("validationErrors")
      response.last._2.asInstanceOf[List[Map[String, Any]]] should have size 2
    }
  }

  test("Test /project/getchangetable/:testProjectId") {
    get("/project/getchangetable/7081807") {
      status should equal(200)
      val changeInfo = parse(StringInput(body)).values.asInstanceOf[Map[String, Map[String, Any]]]
      changeInfo("changeTable")("name") should be ("ProjectOne")
    }
  }

  test("Test PUT /roadnames/:roadNumber") {
    put("/roadnames/5", body = """[{"name":"Test name for road 5","roadNumber":5,"id":1000000,"startDate":"31.12.1988"}]""") {
      status should equal(200)
      val response = parse(StringInput(body)).values.asInstanceOf[Map[String, Any]]
      response should have size 1
      response("success").toString should be ("true")
    }
  }

  test("Test PUT /roadlinks/roadaddress/project") {
    put("/roadlinks/roadaddress/project",
      body = """{"id":7081807,"status":1,"name":"ProjectOne to test","startDate":"11.10.2022","additionalInfo":"","reservedPartList":[],"formedPartList":[{"discontinuity":"Tien loppu","ely":1,"roadLength":3214,"roadNumber":77997,"roadPartId":0,"roadPartNumber":1,"startingLinkId":"6117675"}],"resolution":8}"""
    ) {
      status should equal(200)
      val response = parse(StringInput(body)).values.asInstanceOf[Map[String, Any]]
      response should have size 5
      response("success").toString should be ("true")
    }
  }

//TODO: this test library does not allow BODY in delete operation. Update or refactor needed.

//  test("Test DELETE /roadlinks/roadaddress/project") {
//    delete("/roadlinks/roadaddress/project",
//       body = "7081807"
//    ) {
//      status should equal(200)
//      val response = parse(StringInput(body)).values.asInstanceOf[Map[String, Any]]
//      response should have size 1
//      response("success").toString should be ("true")
//    }
//  }

  test("Test json.extract[RoadAddressProjectExtractor] When sending a list of 1 road part link coded in a JSON format Then return the decoded Road Address Project list with 1 element.") {
    val str = "{\"id\":0,\"status\":1,\"name\":\"erwgerg\",\"startDate\":\"22.4.2017\",\"additionalInfo\":\"\",\"projectEly\":5,\"reservedPartList\":[{\"roadPartNumber\":205,\"roadNumber\":5,\"ely\":5,\"roadLength\":6730,\"roadPartId\":30,\"discontinuity\":\"Jatkuva\"}],\"resolution\":8}"
    val json = parse(str)
      json.extract[RoadAddressProjectExtractor].reservedPartList should have size 1
  }

  test("Test json.extract[RoadAddressProjectExtractor] When sending am excessively big name in JSON format Then return the decoded name but with some loss of letters.") {
    val str = "{\"id\":0,\"projectEly\":5,\"status\":1,\"name\":\"ABCDEFGHIJKLMNOPQRSTUVWXYZ01234567890\",\"startDate\":\"22.4.2017\",\"additionalInfo\":\"\",\"reservedPartList\":[],\"resolution\":8}"
    val json = parse(str)
    val extracted = json.extract[RoadAddressProjectExtractor]
    extracted.name should have length 37
    val project = ProjectConverter.toRoadAddressProject(extracted, User(1, "user", Configuration()))
    project.name should have length 32
  }

  test("Test json.extract[Point] When using a single point with a XY coordinate Then return exactly the point with the same XY coordinates that was sent..") {
    val point = Point(0.2,5.0)
    val str = "{\"x\":0.2,\"y\":5.0}"
    val json=parse(str)
    val read = json.extract[Point]
    read should be (point)
  }
}
