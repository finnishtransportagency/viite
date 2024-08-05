package fi.liikennevirasto.digiroad2

import fi.liikennevirasto.viite.RoadAddressService
import org.mockito.Mockito.when
import org.scalatest.FunSuite
import org.scalatestplus.mockito.MockitoSugar
import org.scalatra.test.scalatest.ScalatraSuite

class SearchApiSpec extends FunSuite with ScalatraSuite {

  val mockRoadAddressService: RoadAddressService = MockitoSugar.mock[RoadAddressService]

  private val searchApi = new SearchApi(mockRoadAddressService, new ViiteSwagger)
  addServlet(searchApi, "/*")

  val retWith400AndFeedback = "should return with 1) HTTP-400 BadRequest, and 2) a feedback message"


  /* Test GET /search/road_numbers */

  test("Test GET /search/road_numbers " +
    "- When what ever query param - Then should return with 1) HTTP-200 OK, and 2) a road number list.")
  {
    when(mockRoadAddressService.getRoadNumbers).thenReturn(List(1L,2L,3L, /*andVeryManyOtherNumbers,*/ 99607L))

    get("/road_numbers") {
      status should equal(200)
      response.body should startWith("[")
      response.body should endWith("]")
    }

    get("/road_numbers?put=here&whatever_you_wish=select%32*%32from%32roadway&i=dont-mind-at-all") {
      status should equal(200)
      response.body should startWith("[")
      response.body should endWith("]")
    }
  }


  /* Test GET /search/road_address */

  // TODO check that  'GET /search/road_address' passes with HTTP-200 when proper parameters given, and returns only [] if no data, or with data, when there exists such data

  test("Test GET /search/road_address " +
    "- When a MISSING mandatory parameter (linkId) - Then " + retWith400AndFeedback)
  {
    val linkMissingNotification = "Missing mandatory query parameter 'linkId'"
    get("/road_address")                                 {   status should equal(400);   response.body shouldBe linkMissingNotification   }
    get("/road_address?startMeasure=0&endMeasure=10000") {   status should equal(400);   response.body shouldBe linkMissingNotification   }
  }

  test("Test GET /search/road_address " +
    "- When an ERRONEOUS mandatory parameter (linkId) - Then " + retWith400AndFeedback)
  {
    val erroneousLinkIdNotification = "[At least] a single malformed linkId. MML KGV link ids expected. Now got:"

    get("/road_address?linkId=")                                              {    status should equal(400);  response.body should startWith(erroneousLinkIdNotification)    }
    get("/road_address?linkId=1")                                             {    status should equal(400);  response.body should startWith(erroneousLinkIdNotification)    }
    get("/road_address?linkId=a")                                             {    status should equal(400);  response.body should startWith(erroneousLinkIdNotification)    }
    get("/road_address?linkId=EXTRA-36be5dec-0496-4292-b260-884664467174:1")  {    status should equal(400);  response.body should startWith(erroneousLinkIdNotification)    }
    get("/road_address?linkId=36be5dec-0496-4292-b260-884664467174:1-EXTRA")  {    status should equal(400);  response.body should startWith(erroneousLinkIdNotification)    }
  }

  test("Test GET /search/road_address " +
    "- When an ERRONEOUS optional parameter - Then " + retWith400AndFeedback)
  {
    val erroneousOptionalParameterNotification = "At least one malformed parameter: 'startMeasure' or 'endMeasure'. Now got"

    get("/road_address?linkId=36be5dec-0496-4292-b260-884664467174:1&startMeasure="   ) {   status should equal(400);   response.body should startWith(erroneousOptionalParameterNotification)   }
    get("/road_address?linkId=36be5dec-0496-4292-b260-884664467174:1&endMeasure="     ) {   status should equal(400);   response.body should startWith(erroneousOptionalParameterNotification)   }
    get("/road_address?linkId=36be5dec-0496-4292-b260-884664467174:1&startMeasure=a"  ) {   status should equal(400);   response.body should startWith(erroneousOptionalParameterNotification)   }
    get("/road_address?linkId=36be5dec-0496-4292-b260-884664467174:1&endMeasure=b"    ) {   status should equal(400);   response.body should startWith(erroneousOptionalParameterNotification)   }
    get("/road_address?linkId=36be5dec-0496-4292-b260-884664467174:1&startMeasure=[]]") {   status should equal(400);   response.body should startWith(erroneousOptionalParameterNotification)   }
    get("/road_address?linkId=36be5dec-0496-4292-b260-884664467174:1&endMeasure=[]"   ) {   status should equal(400);   response.body should startWith(erroneousOptionalParameterNotification)   }
  }

  test("Test GET /search/road_address " +
    "- Whan a parameter out-of-range - Then " + retWith400AndFeedback) {
    val properLinkIdParam = "linkId=36be5dec-0496-4292-b260-884664467174:1"
    val negativeMeasuresNotification = "Invalid value(s) in measure(s). A measure must be >=0, and start < end. Now got"

    get("/road_address?" + properLinkIdParam + "&startMeasure=-1&endMeasure=10000") {   status should equal(400);   response.body should startWith(negativeMeasuresNotification)   }
    get("/road_address?" + properLinkIdParam + "&startMeasure=-2&endMeasure=-1")    {   status should equal(400);   response.body should startWith(negativeMeasuresNotification)   }
    get("/road_address?" + properLinkIdParam + "&startMeasure=0&endMeasure=-1")     {   status should equal(400);   response.body should startWith(negativeMeasuresNotification)   }
  }

  test("Test GET /search/road_address " +
    "- When start measure bigger than end measure - Then " + retWith400AndFeedback)
  {
    val erroneousMutualOrderingNotification = "Invalid value(s) in measure(s). A measure must be >=0, and start < end. Now got"

    get("/road_address?linkId=36be5dec-0496-4292-b260-884664467174:1&startMeasure=950&endMeasure=940") {
      status should equal(400);
      response.body should startWith(erroneousMutualOrderingNotification)
    }
  }

  // TODO The code does not take these single negative measure parameters into consideration, but lets them pass. Should fix this so that they are caught.
  //test("Test " +
  //  "When calling POST /search/road_address with single negative measure parameters" +
  //  "Then " + retWith400AndFeedbeck)
  //{
  //  when(mockRoadAddressService.getRoadNumbers).thenReturn(List(1L,2L,3L, /*andVeryManyOtherNumbers,*/ 99607L))
  //
  //  val negativeMeasuresNotification = "Invalid value(s) in measure(s). A measure must be >=0, and start < end. Now got" // Or sthg like this
  //
  //  get("/road_address?linkId=36be5dec-0496-4292-b260-884664467174:1&startMeasure=-1") {   status should equal(400);   response.body should startWith(negativeMeasuresNotification)   }
  //  get("/road_address?linkId=36be5dec-0496-4292-b260-884664467174:1&endMeasure=-1"  ) {   status should equal(400);   response.body should startWith(negativeMeasuresNotification)   }
  //}


  /* Test POST /search/road_address */

  // TODO check that  'POST /search/road_address' passes with HTTP-200 when proper parameters given, and returns only [] if no data, or with data, when there exists such data

  test("Test POST /search/road_address " +
    "- When a non-list as a body - Then " + retWith400AndFeedback)
  {
    val properKGVLinksListExpected = "List of proper MML KGV link ids expected in the body; [id-1, id-2, ...]"

    val missingIdListNotification = properKGVLinksListExpected
    post("/road_address", "")                                           {   status should equal(400);   response.body should startWith(missingIdListNotification)   }
    post("/road_address", "asdf")                                       {   status should equal(400);   response.body should startWith(missingIdListNotification)   }
    post("/road_address", "36be5dec-0496-4292-b260-884664467174:1")     {   status should equal(400);   response.body should startWith(missingIdListNotification)   }
    post("/road_address","[")                                           {   status should equal(400);   response.body should startWith(missingIdListNotification)   }

    val nonKGVIdListNotification  = properKGVLinksListExpected
    post("/road_address","1")                                           {   status should equal(400);   response.body should startWith(nonKGVIdListNotification)   }
    post("/road_address","\"1\"")                                       {   status should equal(400);   response.body should startWith(nonKGVIdListNotification)   }
    post("/road_address", "\"36be5dec-0496-4292-b260-884664467174:1\"") {   status should equal(400);   response.body should startWith(nonKGVIdListNotification)   }
  }

  test("Test POST /search/road_address " +
    "- When a non-KGV linkID in the list in the body - Then " + retWith400AndFeedback)
  {
    val OKlinkId = "36be5dec-0496-4292-b260-884664467174:1"

    val missingIdListNotification = "List of proper MML KGV link ids expected in the body; [id-1, id-2, ...]"
    post("/road_address", "[]")      {   status should equal(400);      response.body should startWith(missingIdListNotification)    }
    post("/road_address", "[a]")     {   status should equal(400);      response.body should startWith(missingIdListNotification)    }
    post("/road_address", "[a,b]")   {   status should equal(400);      response.body should startWith(missingIdListNotification)    }
    post("/road_address", OKlinkId)  {   status should equal(400);      response.body should startWith(missingIdListNotification)    }
    post("/road_address", "[")       {   status should equal(400);      response.body should startWith(missingIdListNotification)    }

    val malformedKGVIdNotification = "[At least] a single malformed linkId. MML KGV link ids expected."
    post("/road_address", "[1]")     {   status should equal(400);      response.body should startWith(malformedKGVIdNotification)    }
    post("/road_address", "[\"" +OKlinkId+ "\",2]")   {   status should equal(400);      response.body should startWith(malformedKGVIdNotification)    }
    post("/road_address", "[\"1\"]") {   status should equal(400);      response.body should startWith(malformedKGVIdNotification)    }
    post("/road_address", "[\"" +OKlinkId+ "\",\"6ad00ce3-92ef-4952-91ae-dcb1bf45caf8:n0nnum6er\"]")
    {   status should equal(400);      response.body should startWith(malformedKGVIdNotification)    }
    post("/road_address", "[\"" +OKlinkId+ "\",\"6ad00ce3-92ef-4952-91ae-NUMbeRM1551Ng\"]")
    {   status should equal(400);      response.body should startWith(malformedKGVIdNotification)    }
    post("/road_address", "[\"" +OKlinkId+ "\",\"6ad00ce3-92ef-4952-91ae-dcb1bf45caf8:1-EXTRACHARS\"]")
    {   status should equal(400);      response.body should startWith(malformedKGVIdNotification)    }
    post("/road_address", "[\"" +OKlinkId+ "\",\"EXTRACHARS-6ad00ce3-92ef-4952-91ae-dcb1bf45caf8:1\"]")
    {   status should equal(400);      response.body should startWith(malformedKGVIdNotification)    }
  }


  /* Test GET /search/road_address/{road} */

  // TODO check that  'GET /search/road_address/{road}' passes with HTTP-200 when proper parameters given, and returns only [] if no data, or with data, when there exists such data

  test("Test GET /search/road_address/{road} " +
    "- When a non-integer parameter - Then " + retWith400AndFeedback)
  {
    val invalidRoadFormatNotification = "Check the given parameters. An integer expected. Now got 'road="
    get("/road_address/a?tracks=1"  )            {   status should equal(400);   response.body should startWith(invalidRoadFormatNotification)    }
    get("/road_address/3.3?tracks=1")            {   status should equal(400);   response.body should startWith(invalidRoadFormatNotification)    }
    get("/road_address/3,3?tracks=1")            {   status should equal(400);   response.body should startWith(invalidRoadFormatNotification)    }

    val invalidTrackFormatNotification = "Check the given parameters. A malformed field in the query path: tracks="
    get("/road_address/40922?tracks=d")          {   status should equal(400);   response.body should startWith(invalidTrackFormatNotification)   }
    get("/road_address/40922?tracks=4.4")        {   status should equal(400);   response.body should startWith(invalidTrackFormatNotification)   }
    get("/road_address/40922?tracks=4,4")        {   status should equal(400);   response.body should startWith(invalidTrackFormatNotification)   }
    get("/road_address/40922?tracks=1&tracks=d") {   status should equal(400);   response.body should startWith(invalidTrackFormatNotification)   }
    get("/road_address/40922?tracks")            {   status should equal(400);   response.body should startWith(invalidTrackFormatNotification)   }
    get("/road_address/40922?tracks=")           {   status should equal(400);   response.body should startWith(invalidTrackFormatNotification)   }
  }

  test("Test GET /search/road_address/{road} " +
    "- When a parameter out-of-range - Then " + retWith400AndFeedback)
  {
    val invalidRoadNumberNotification = "Check the given parameters. Range of 'road' is 1 - 99999. Now got"
    get("/road_address/0?tracks=1")              {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)    }
    get("/road_address/100000?tracks=1")         {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)    }

    val invalidTrackNumberNotification = "Check the given parameters. Check tracks."
    get("/road_address/40922?tracks=-1")         {   status should equal(400);   response.body should startWith(invalidTrackNumberNotification)   }
    get("/road_address/99999?tracks=3")          {   status should equal(400);   response.body should startWith(invalidTrackNumberNotification)   }
    get("/road_address/99999?tracks=0&tracks=3") {   status should equal(400);   response.body should startWith(invalidTrackNumberNotification)   }
  }


  /* Test POST /search/road_address/{road} */

  // TODO check that  'POST /search/road_address/{road}' passes with HTTP-200 when proper parameters given, and returns only [] if no data, or with data, when there exists such data

  test("Test POST /search/road_address/{road} - " +
    "- When a MISSING mandatory parameter (BODY) - Then " + retWith400AndFeedback)
  {
    val missingParameterNotification = "Check the given parameters. Missing or faulty mandatory body parameters, or a non-JSON body. Check 'tracks', and 'roadParts' lists, and correct formatting of the body."
    post("/road_address/1",   "{                     \"tracks\":[0]}") {   status should equal(400);   response.body should startWith(missingParameterNotification)   }
    post("/road_address/1",   "{ \"roadParts\":[1]                 }") {   status should equal(400);   response.body should startWith(missingParameterNotification)   }
  }

  test("Test POST /search/road_address/{road} " +
    "- When a FAULTY mandatory parameter (BODY) - Then " + retWith400AndFeedback)
  {
    val faultyParameterNotification = "Check the given parameters. Missing or faulty mandatory body parameters, or a non-JSON body. Check 'tracks', and 'roadParts' lists, and correct formatting of the body."
    post("/road_address/1", "{ \"roadParts\":,     \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(faultyParameterNotification)   }
    post("/road_address/1", "{ \"roadParts\":[,    \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(faultyParameterNotification)   }
    post("/road_address/1", "{ \"roadParts\":[a],  \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(faultyParameterNotification)   }
    post("/road_address/1", "{ \"roadParts\":[1,a],\"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(faultyParameterNotification)   }

    post("/road_address/1", "{ \"roadParts\":[1],  \"tracks\":     }") {   status should equal(400);   response.body should startWith(faultyParameterNotification)   }
    post("/road_address/1", "{ \"roadParts\":[1],  \"tracks\":[    }") {   status should equal(400);   response.body should startWith(faultyParameterNotification)   }
    post("/road_address/1", "{ \"roadParts\":[1],  \"tracks\":[a]  }") {   status should equal(400);   response.body should startWith(faultyParameterNotification)   }
    post("/road_address/1", "{ \"roadParts\":[1],  \"tracks\":[0,a]}") {   status should equal(400);   response.body should startWith(faultyParameterNotification)   }

    // faulty JSON examples
    post("/road_address/1", "{                                      ") {   status should equal(400);   response.body should startWith(faultyParameterNotification)   }
    post("/road_address/1", "{ \"roadParts\":[1]\"                  ") {   status should equal(400);   response.body should startWith(faultyParameterNotification)   }
  }

  test("Test POST /search/road_address/{road} " +
    "- When a non-integer parameter (PATH) - Then " + retWith400AndFeedback)
  {
    val erroneousRoadNumberNotification = "Check the given parameters. An integer expected. Now got 'road="
    post("/road_address/a",  "{ \"roadParts\":[1],       \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(erroneousRoadNumberNotification)   }
    post("/road_address/3.3","{ \"roadParts\":[1],       \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(erroneousRoadNumberNotification)   }
    // TODO test fails onto IllegalArgumentException; passes with curl
    //  post("/road_address/[]", "{ \"roadParts\":[1],       \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(erroneousRoadNumberNotification)   }
  }

  test("Test POST /search/road_address/{road} " +
    "- When a non-integer parameter (BODY) - Then " + retWith400AndFeedback)
  {
    val emptyParameterNotification = "Check the given parameters. Empty '"
    post("/road_address/1", "{ \"roadParts\":[],         \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(emptyParameterNotification) } //" 'roadParts' list in the body."
    post("/road_address/1", "{ \"roadParts\":[1],        \"tracks\":[]   }") {   status should equal(400);   response.body should startWith(emptyParameterNotification) } //  'tracks' list in the body. "

    val cannotParseRoadPartsNotification = "Check the given parameters. Cannot parse 'roadParts' list (in the body) to integer list."
    post("/road_address/1", "{ \"roadParts\":[\"a:a\"],  \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(cannotParseRoadPartsNotification)  }
    post("/road_address/1", "{ \"roadParts\":[1,\"a:a\"],\"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(cannotParseRoadPartsNotification)  }
    post("/road_address/1", "{ \"roadParts\":[\"1\"],    \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(cannotParseRoadPartsNotification)  }

    val cannotParseTracksNotification = "Check the given parameters. Cannot parse 'tracks' list (in the body) to integer list."
    post("/road_address/1", "{ \"roadParts\":[1],    \"tracks\":[\"a:a\"]  }") {   status should equal(400);   response.body should startWith(cannotParseTracksNotification)   }
    post("/road_address/1", "{ \"roadParts\":[1],    \"tracks\":[0,\"a:a\"]}") {   status should equal(400);   response.body should startWith(cannotParseTracksNotification)   }
    post("/road_address/1", "{ \"roadParts\":[1],    \"tracks\":[\"1\"]    }") {   status should equal(400);   response.body should startWith(cannotParseTracksNotification)   }

  }

  test("Test POST /search/road_address/{road} " +
    "- When a parameter out-of-range - Then " + retWith400AndFeedback)
  {
    val roadNumberOutOfRangeNotification = "Check the given parameters. Range of 'road' is 1 - 99999. Now got "
    post("/road_address/0",     "{ \"roadParts\":[1],    \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(roadNumberOutOfRangeNotification)   }
    post("/road_address/100000","{ \"roadParts\":[1],    \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(roadNumberOutOfRangeNotification)   }

    val roadPartNumberOutOfRangeNotification = "Check the given parameters. At least one invalid road part number. "
    post("/road_address/40922", "{ \"roadParts\":[0],    \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(roadPartNumberOutOfRangeNotification)   }
    post("/road_address/40922", "{ \"roadParts\":[1000], \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(roadPartNumberOutOfRangeNotification)   }
    post("/road_address/40922" ,"{ \"roadParts\":[1,0],  \"tracks\":[0]  }") {   status should equal(400);   response.body should startWith(roadPartNumberOutOfRangeNotification)   }

    val trackNumberOutOfRangeNotification = "Check the given parameters. Check tracks.  A track's scope is from 0 to 2. "
    post("/road_address/40922", "{ \"roadParts\":[1],    \"tracks\":[3]  }") {   status should equal(400);   response.body should startWith(trackNumberOutOfRangeNotification)   }
    post("/road_address/40922", "{ \"roadParts\":[1],    \"tracks\":[1,3]}") {   status should equal(400);   response.body should startWith(trackNumberOutOfRangeNotification)   }
    post("/road_address/40922", "{ \"roadParts\":[1],    \"tracks\":[-1] }") {   status should equal(400);   response.body should startWith(trackNumberOutOfRangeNotification)   }
  }


  /* Test GET /search/road_address/{road}/{roadPart} */

  // TODO check that  'GET /search/road_address/{road}/{roadPart}' passes with HTTP-200 when proper parameters given, and returns only [] if no data, or with data, when there exists such data

  test("Test GET /search/road_address/{road}/{roadPart} " +
    "- When a non-integer parameter - Then " + retWith400AndFeedback)
  {
    val invalidRoadNumberNotification = "Check the given parameters. An integer expected. Now got 'road="
    get("/road_address/a/1")    {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }
    get("/road_address/3.3/1")  {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }
    get("/road_address/3,3/1")  {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }
    // TODO test fails onto IllegalArgumentException; passes with curl
    //  get("/road_address/[]/1") {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }

    val invalidRoadPartNumberNotification = "Check the given parameters. An integer expected. Now got 'roadPart="
    get("/road_address/1/a")    {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)    }
    get("/road_address/1/1.1")  {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)    }
    get("/road_address/1/1,1")  {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)    }
    // TODO test fails onto IllegalArgumentException; passes with curl
    //  get("/road_address/1/[]") {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)   }
  }

  test ("Test GET /search/road_address/{road}/{roadPart} " +
    "- When a parameter out-of-range - Then " + retWith400AndFeedback)
  {
    val roadNumberOutOfRangeNotification = "Check the given parameters. Range of 'road' is 1 - 99999."
    get ("/road_address/0/1")        {   status should equal (400);   response.body should startWith (roadNumberOutOfRangeNotification)  }
    get ("/road_address/100000/1")   {   status should equal (400);   response.body should startWith (roadNumberOutOfRangeNotification)  }

    val roadPartNumberOutOfRangeNotification = "Check the given parameters. Range of 'roadPart' is 1 - 999."
    get ("/road_address/40922/0")    {   status should equal (400);   response.body should startWith (roadPartNumberOutOfRangeNotification)  }
    get ("/road_address/40922/1000") {   status should equal (400);   response.body should startWith (roadPartNumberOutOfRangeNotification)  }
  }


  /* Test GET /search/road_address/{road}/{roadPart}/{address} */

  // TODO check that  'GET /search/road_address/{road}/{roadPart}/{address}' passes with HTTP-200 when proper parameters given, and returns only [] if no data, or with data, when there exists such data

  test("Test GET /search/road_address/{road}/{roadPart}/{address} " +
    "- When a non-integer parameter - Then " + retWith400AndFeedback)
  {
    val invalidRoadNumberNotification = "Check the given parameters. An integer expected. Now got 'road="
    get("/road_address/a/1/0?track=1")   {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }
    get("/road_address/3.3/1/0?track=1") {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }
    get("/road_address/3,3/1/0?track=1") {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }
    // TODO test fails onto IllegalArgumentException; passes with curl
    //  get("/road_address/[]/1/0?track=1") {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }

    val invalidRoadPartNumberNotification = "Check the given parameters. An integer expected. Now got 'roadPart="
    get("/road_address/1/b/0?track=1")   {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)   }
    get("/road_address/1/2.2/0?track=1") {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)   }
    get("/road_address/1/2,2/0?track=1") {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)   }
    // TODO test fails onto IllegalArgumentException; passes with curl
    //  get("/road_address/1/[]/0?track=1") {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)   }

    val invalidAddressValueNotification = "Check the given parameters. An integer expected. Now got 'address="
    get("/road_address/1/1/c?track=1")   {   status should equal(400);   response.body should startWith(invalidAddressValueNotification)   }
    get("/road_address/1/1/3.3?track=1") {   status should equal(400);   response.body should startWith(invalidAddressValueNotification)   }
    get("/road_address/1/1/3,3?track=1") {   status should equal(400);   response.body should startWith(invalidAddressValueNotification)   }
    // TODO test fails onto IllegalArgumentException; passes with curl
    //  get("/road_address/1/1/[]?track=1") {   status should equal(400);   response.body should startWith(invalidAddressValueNotification)   }

    val invalidTrackNotification = "Check the given parameters. An invalid track parameter: "
    get("/road_address/1/1/0?track=d")   {   status should equal(400);   response.body should startWith(invalidTrackNotification)   }
    get("/road_address/1/1/0?track=4.4") {   status should equal(400);   response.body should startWith(invalidTrackNotification)   }
    get("/road_address/1/1/0?track=4,4") {   status should equal(400);   response.body should startWith(invalidTrackNotification)   }
    get("/road_address/1/1/0?track=[]")  {   status should equal(400);   response.body should startWith(invalidTrackNotification)   }

    get("/road_address/1/1/0?track")     {   status should equal(400);   response.body should startWith(invalidTrackNotification)   }
    get("/road_address/1/1/0?track=")    {   status should equal(400);   response.body should startWith(invalidTrackNotification)   }
    get("/road_address/1/1/0?track=[")   {   status should equal(400);   response.body should startWith(invalidTrackNotification)   }

  }

  test("Test GET /search/road_address/{road}/{roadPart}/{address} " +
    "- When a parameter out-of-range - Then " + retWith400AndFeedback)
  {
    val roadNumberOutOfRangeNotification = "Check the given parameters. Range of 'road' is 1 - 99999."
    get("/road_address/0/1/0?track=1")        {   status should equal(400);   response.body should startWith(roadNumberOutOfRangeNotification)   }
    get("/road_address/100000/999/0?track=1") {   status should equal(400);   response.body should startWith(roadNumberOutOfRangeNotification)   }
    // ^also covertly checking, that maximal allowed roadPartNumbers pass

    val roadPartNumberOutOfRangeNotification = "Check the given parameters. Range of 'roadPart' is 1 - 999."
    get("/road_address/1/0/0?track=1")        {   status should equal(400);   response.body should startWith(roadPartNumberOutOfRangeNotification)   }
    get("/road_address/99999/1000/0?track=1") {   status should equal(400);   response.body should startWith(roadPartNumberOutOfRangeNotification)   }
    // ^also covertly checking, that maximal allowed roadNumbers pass

    val addressOutOfRangeNotification = "Check the given parameters. Range of 'address' is 0 - "
    get("/road_address/1/1/-1?track=1")       {   status should equal(400);   response.body should startWith(addressOutOfRangeNotification)   }

    val trackOutOfRangeNotification = "Check the given parameters. Check track."
    get("/road_address/1/1/0?track=-1")       {   status should equal(400);   response.body should startWith(trackOutOfRangeNotification)   }
    get("/road_address/99999/999/0?track=3")  {   status should equal(400);   response.body should startWith(trackOutOfRangeNotification)   }
  }


  /* Test GET /search/road_address/{road}/{roadPart}/{startAddress}/{endAddress} */

  // TODO check that  'GET /search/road_address/{road}/{roadPart}/{startAddress}/{endAddress}' passes with HTTP-200 when proper parameters given, and returns only [] if no data, or with data, when there exists such data

  test("Test GET /search/road_address/{road}/{roadPart}/{startAddress}/{endAddress} " +
    "- When a non-integer parameter - Then " + retWith400AndFeedback)
  {
    val invalidRoadNumberNotification = "Check the given parameters. An integer expected. Now got 'road="
    get("/road_address/a/1/0/1000")   {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }
    get("/road_address/3.3/1/0/1000") {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }
    get("/road_address/3,3/1/0/1000") {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }
    // TODO test fails onto IllegalArgumentException; passes with curl
    //  get("/road_address/[]/1/0/1000") {   status should equal(400);   response.body should startWith(invalidRoadNumberNotification)   }

    val invalidRoadPartNumberNotification = "Check the given parameters. An integer expected. Now got 'roadPart="
    get("/road_address/1/b/0/1000")   {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)   }
    get("/road_address/1/2.2/0/1000") {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)   }
    get("/road_address/1/2,2/0/1000") {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)   }
    // TODO test fails onto IllegalArgumentException; passes with curl
    //  get("/road_address/1/[]/0/1000") {   status should equal(400);   response.body should startWith(invalidRoadPartNumberNotification)   }

    val invalidStartAddressValueNotification = "Check the given parameters. An integer expected. Now got 'startAddress="
    get("/road_address/1/1/c/1000")   {   status should equal(400);   response.body should startWith(invalidStartAddressValueNotification)   }
    get("/road_address/1/1/3.3/1000") {   status should equal(400);   response.body should startWith(invalidStartAddressValueNotification)   }
    get("/road_address/1/1/3,3/1000") {   status should equal(400);   response.body should startWith(invalidStartAddressValueNotification)   }
    // TODO test fails onto IllegalArgumentException; passes with curl
    //  get("/road_address/1/1/[]/1000") {   status should equal(400);   response.body should startWith(invalidStartAddressValueNotification)   }

    val invalidEndAddressNotification = "Check the given parameters. An integer expected. Now got 'endAddress="
    get("/road_address/1/1/0/d")      {   status should equal(400);   response.body should startWith(invalidEndAddressNotification)   }
    get("/road_address/1/1/0/4.4")    {   status should equal(400);   response.body should startWith(invalidEndAddressNotification)   }
    get("/road_address/1/1/0/4,4")    {   status should equal(400);   response.body should startWith(invalidEndAddressNotification)   }
    // TODO test fails onto IllegalArgumentException; passes with curl
    //  get("/road_address/1/1/0/[]") {   status should equal(400);   response.body should startWith(invalidEndAddressNotification)   }
  }

  test("Test GET /search/road_address/{road}/{roadPart}/{startAddress}/{endAddress} " +
    "- When a parameter out-of-range - Then " + retWith400AndFeedback)
  {
    val roadNumberOutOfRangeNotification = "Check the given parameters. Range of 'road' is 1 - 99999."
    get("/road_address/0/1/0/1000")        {   status should equal(400);   response.body should startWith(roadNumberOutOfRangeNotification)    }
    get("/road_address/100000/999/0/1000") {   status should equal(400);   response.body should startWith(roadNumberOutOfRangeNotification)    }
    // ^also covertly checking, that maximal allowed roadPartNumbers pass

    val roadPartNumberOutOfRangeNotification = "Check the given parameters. Range of 'roadPart' is 1 - 999."
    get("/road_address/1/0/0/1000")        {   status should equal(400);   response.body should startWith(roadPartNumberOutOfRangeNotification)    }
    get("/road_address/99999/1000/0/1000") {   status should equal(400);   response.body should startWith(roadPartNumberOutOfRangeNotification)    }
    // ^also covertly checking, that maximal allowed roadNumbers pass

    val addressOutOfRangeNotification = "Check the given parameters. Range of " // "['startAddress'|'endAddress] is 0 - "
    get("/road_address/1/1/-1/1000")       {   status should equal(400);   response.body should startWith(addressOutOfRangeNotification)    }
    get("/road_address/1/1/0/-1")          {   status should equal(400);   response.body should startWith(addressOutOfRangeNotification)    }

    val trackOutOfRangeNotification = "Check the given parameters. 'endAddress' (now 100) must be bigger than start address (now 1000)"
    get("/road_address/1/1/1000/100")      {   status should equal(400);   response.body should startWith(trackOutOfRangeNotification)    }
  }
}