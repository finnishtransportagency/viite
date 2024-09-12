 package fi.vaylavirasto.viite.dynamicnetwork

//import fi.liikennevirasto.digiroad2.{LinkInfo, LinkNetworkChange, LinkNetworkUpdater, ReplaceInfo}
import fi.liikennevirasto.viite.dao.{CalibrationPointDAO, LinearLocationDAO}
import fi.vaylavirasto.viite.dao.LinkDAO
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{LinkGeomSource, RoadPart}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import fi.vaylavirasto.viite.util.ViiteException
import org.joda.time.DateTime
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LinkNetworkUpdaterSpec extends AnyFunSuite with Matchers {


  val linkNetworkUpdater = new LinkNetworkUpdater() {
    //override def withDynSession[T](f: => T): T = f // overriding functionality: NOP
    override def withDynTransaction[T](f: => T): T = f // overriding functionality: NOP. We want to rollback.
  }

  private val correctChangeTypeJSON : String = """"changeType": "replace""""
  private val correctOldLinkJSON    : String = """"oldLink":   {"linkId": "oldLink:1", "linkLength": 43.498, "geometry": "LINESTRING ZM(0 0 0, 1 1 1)"}"""
  private val correctNewLinksJSON   : String = """"newLinks": [{"linkId": "newLink:2", "linkLength": 49.631, "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)"}]"""
  private val correctReplaceInfoOldLinkJSON: String = """"oldLinkId": "oldLink:1", "oldFromMValue": 0.0, "oldToMValue": 43.498"""
  private val correctReplaceInfoNewLinkJSON: String = """"newLinkId": "newLink:2", "newFromMValue": 0.0, "newToMValue": 49.631"""
  private val correctReplaceInfoJSON: String = s""""replaceInfo": [{
                                                   $correctReplaceInfoOldLinkJSON,
                                                   $correctReplaceInfoNewLinkJSON,
                                                   "digitizationChange": false
                                                 }]"""
  /**
   * Returns a JSON formatted change set of a single change.
   * By default, returns a properly formatted, and data wise correct change set.
   * Set your own parameters to test incorrect change sets.
   */
  private def getTestJSON( changeType:  String = correctChangeTypeJSON,
                           oldLink:     String = correctOldLinkJSON,
                           newLinks:    String = correctNewLinksJSON,
                           replaceInfo: String = correctReplaceInfoJSON ): String = {

    s"[" + // NOTE, excess array square brackets required due to JsonMethods.parse functionality. Correct, when the library allows.
    s"\n  {" +
    s"\n    $changeType," +
    s"\n    $oldLink," +
    s"\n    $newLinks," +
    s"\n    $replaceInfo" +
    s"\n  }" +
    s"\n]" // NOTE, excess array square brackets required due to JsonMethods.parse functionality. Correct, when the library allows.
  }



  val testChangeMetaData = ChangeSetMetaData("TestSpec-"+DateTime.now, DateTime.parse("2222-11-22"), LinkGeomSource.Unknown)

  /////////////////////////////////////////////////////////////////////////////////
  //////// Test JSON format related stuff (parseJSONForLinkNetworkChanges) ////////

  private def assertFaultyLinkNetworkChangeJSONIsCaught(faultyJSON: String, testJSONName: String) = {
    assertThrows[ViiteException] {
      linkNetworkUpdater.parseJSONForLinkNetworkChanges(faultyJSON, testJSONName)
    }
  }

  //TODO Geometry field reading should be corrected
  ignore("Test When invalid   old link   JSON structure Then throw ViiteException") {

    def assertFaultyOldIsCaught(faultyOldLinkJSON: String) = {
      assertFaultyLinkNetworkChangeJSONIsCaught(getTestJSON(oldLink=faultyOldLinkJSON), "invalid old link in JSON")
    }

    //TODO THESE TESTS FAIL NOW FOR WRONG REASON: CANNOT READ Geometry FIELD IN CORRECTLY
    runWithRollback { // should not get to write to DB, but just in case...
      assertFaultyOldIsCaught(""""oldLink": { }""")

      assertFaultyOldIsCaught(""""oldLink": {                        "linkLength": 43.498, "geometry": "LINESTRING ZM(0 0 0, 1 1 1)" }""")
      assertFaultyOldIsCaught(""""oldLink": { "linkId": 1,           "linkLength": 43.498, "geometry": "LINESTRING ZM(0 0 0, 1 1 1)" }""")
      assertFaultyOldIsCaught(""""oldLink": { "linkId": "oldLink:1",                       "geometry": "LINESTRING ZM(0 0 0, 1 1 1)" }""")
      assertFaultyOldIsCaught(""""oldLink": { "linkId": "oldLink:1", "linkLength": "asdf", "geometry": "LINESTRING ZM(0 0 0, 1 1 1)" }""")
      //assertFaultyOldIsCaught(""""oldLink": { "linkId": "oldLink:1", "linkLength": 43.498                                            }""") // This Passes?!? Why?
      assertFaultyOldIsCaught(""""oldLink": { "linkId": "oldLink:1", "linkLength": 43.498, "geometry": 1                             }""")

      assertFaultyOldIsCaught(""""oldLink": { "dummy":  "oldLink:1", "linkLength": 43.498, "geometry": "LINESTRING ZM(0 0 0, 1 1 1)" }""")
      assertFaultyOldIsCaught(""""oldLink": { "linkId": "oldLink:1", "linkLength": 43.498, "geometry": "LINESTRING ZM(0 0 0, 1 1 1)", "extrafield": "dummydata" }""")
    }
  }

  //TODO Geometry field reading should be corrected
  ignore("Test When invalid   new link   JSON structure Then throw ViiteException") {

    def assertFaultyNewIsCaught(faultyNewLinkJSON: String) = {
      assertFaultyLinkNetworkChangeJSONIsCaught(getTestJSON(newLinks=faultyNewLinkJSON), "invalid new link in JSON")
    }

     //TODO THESE TESTS FAIL NOW FOR WRONG REASON: CANNOT READ Geometry FIELD IN CORRECTLY
    runWithRollback { // should not get to write to DB, but just in case...
      assertFaultyNewIsCaught(""""newLinks": [{ }]""")
      assertFaultyNewIsCaught(""""newLinks": [{                       "linkLength": 49.631, "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)"}]""")
      assertFaultyNewIsCaught(""""newLinks": [{"linkId": 1,           "linkLength": 49.631, "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)"}]""")
      assertFaultyNewIsCaught(""""newLinks": [{"linkId": "newLink:2",                       "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)"}]""")
      assertFaultyNewIsCaught(""""newLinks": [{"linkId": "newLink:2", "linkLength": "asdf", "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)"}]""")
      assertFaultyNewIsCaught(""""newLinks": [{"linkId": "newLink:2", "linkLength": 49.631                                                       }]""")
      assertFaultyNewIsCaught(""""newLinks": [{"linkId": "newLink:2", "linkLength": 49.631, "geometry": 1                                        }]""")

      assertFaultyNewIsCaught(""""newLinks": [{"dummy":  "newLink:2", "linkLength": 49.631, "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)"}]""")
      assertFaultyNewIsCaught(""""newLinks": [{"linkId": "newLink:2", "linkLength": 49.631, "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)", "extrafield": "dummydata"}]""")
    }
  }

  //TODO Geometry field reading should be corrected
  ignore("Test When invalid replace info JSON structure Then throw ViiteException") {

    def assertFaultyReplaceInfoIsCaught(faultyReplaceInfoJSON: String) = {
      assertFaultyLinkNetworkChangeJSONIsCaught(getTestJSON(replaceInfo=faultyReplaceInfoJSON), "invalid replace info JSON")
    }

    val oldL = correctReplaceInfoOldLinkJSON
    val newL = correctReplaceInfoNewLinkJSON
    val digit = s"""\n  "digitizationChange": false\n"""

    //TODO THESE TESTS FAIL NOW FOR WRONG REASON: CANNOT READ Geometry FIELD IN CORRECTLY
    runWithRollback { // should not get to write to DB, but just in case...
      // Missing, and faulty type fields on the old link data
      assertFaultyReplaceInfoIsCaught("")
      assertFaultyReplaceInfoIsCaught(""""replaceInfo": [{ }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{                           "oldFromMValue": 0.0, "oldToMValue": 43.498,     $newL, $digit }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ "oldLinkId":  1234567890, "oldFromMValue": 0.0, "oldToMValue": 43.498,     $newL, $digit }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ "oldLinkId": "oldLink:1",                       "oldToMValue": 43.498,     $newL, $digit }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ "oldLinkId": "oldLink:1", "oldFromMValue": "X", "oldToMValue": 43.498,     $newL, $digit }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ "oldLinkId": "oldLink:1", "oldFromMValue": 0.0,                            $newL, $digit }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ "oldLinkId": "oldLink:1", "oldFromMValue": 0.0, "oldToMValue": "XXXX",     $newL, $digit }]""")

      // Missing, and faulty type fields on the new link data
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ $oldL,                             "newFromMValue": 0.0, "newToMValue": 49.631,   $digit }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ $oldL,   "newLinkId":  1234567890, "newFromMValue": 0.0, "newToMValue": 49.631,   $digit }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ $oldL,   "newLinkId": "newLink:2",                       "newToMValue": 49.631,   $digit }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ $oldL,   "newLinkId": "newLink:2", "newFromMValue": "X", "newToMValue": 49.631,   $digit }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ $oldL,   "newLinkId": "newLink:2", "newFromMValue": 0.0,                          $digit }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ $oldL,   "newLinkId": "newLink:2", "newFromMValue": 0.0, "newToMValue": "XXXX",   $digit }]""")

      // Missing, and faulty type fields on the digitization data
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ $oldL, $newL                                                                             }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ $oldL, $newL,                                                    "digitizationChange": 1 }]""")

      // other faulty structure
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo": [{ $oldL, $newL, $digit, "extrafield" = "dummydata" }]""")
      assertFaultyReplaceInfoIsCaught(s""""replaceInfo":  { $oldL, $newL, $digit }""") // Not as an array, but as plain object. In fact, this SHOULD PASS, but our json4s.jackson parser version in use does not allow it.
    }
  }


  //////////////////////////////////////////////////////////////////////////////////
  //////// Test Network changes validation stuff (convertToAValidXXXChange) ////////

  // Building blocks of a default test change. Change with simply checkable, but not realistic values
  private val correctOldSegment: Seq[Point] = Seq(Point(0.0, 0.0, 0.0), Point(3.0, 4.0, 100.0 )) // length  5.000, Z values insane; does not matter, we're in 2D
  private val correctNewSegment: Seq[Point] = Seq(Point(0.1, 0.1, 0.1), Point(6.1, 8.1,   1.1))  // length 10.000, Z values changed dramatically; does not matter, we're in 2D
  private val correctChangeType: String = "replace"
  private val correctOldLink: LinkInfo = LinkInfo("oldLink:1", 5.000, correctOldSegment)
  private val correctNewLink: LinkInfo = LinkInfo("newLink:2", 10.000, correctNewSegment)

  private val dummyMeta = Seq(ViiteMetaData(1,0.0,1.0,1,1,RoadPart(1,1)))
  private val correctReplaceInfo: ReplaceInfo = ReplaceInfo("oldLink:1", 0.0, 5.000, "newLink:2", 0.0, 10.000, digitizationChange=false, dummyMeta)


  /** Change builder, to build a single LinkNetworkChange for testing.
   * Change type must be given, but the other values have simple, congruent contents in them as default.
   * Modify the defaults to test incorrect data, or to build your own working examples. :) */
  private def getTestChange(changeType:  String,
                            oldLink:     LinkInfo         = correctOldLink,
                            newLinks:    Seq[LinkInfo]    = Seq(correctNewLink),
                            replaceInfo: Seq[ReplaceInfo] = Seq(correctReplaceInfo)): LinkNetworkChange = {

    LinkNetworkChange(changeType, oldLink, newLinks, replaceInfo)
  }

  private def assertFaultyLinkNetworkChangeIsCaught(faultyChangeList: Seq[LinkNetworkChange], testJSONName: String) = {
    assertThrows[ViiteException] {
      linkNetworkUpdater.persistLinkNetworkChanges(faultyChangeList, testChangeMetaData.copy(changeSetName=testJSONName))
    }
  }


  test("Test When invalid number of items in the change structure Then throw ViiteException") {
    def assertFaultyStructureIsCaught(faultyChange: LinkNetworkChange) = {
      assertFaultyLinkNetworkChangeIsCaught(Seq(faultyChange),"Testing invalid Change structures")
    }
    runWithRollback { // should not get to write to DB, as should fail, but just in case...
      assertFaultyStructureIsCaught( getTestChange("replace", correctOldLink, Seq(correctNewLink),                Seq()                  ) ) // must have a replaceInfo
      assertFaultyStructureIsCaught( getTestChange("replace", correctOldLink, Seq(),                              Seq(correctReplaceInfo)) ) // must have exacly one new link
      assertFaultyStructureIsCaught( getTestChange("replace", correctOldLink, Seq(correctNewLink,correctNewLink), Seq(correctReplaceInfo)) ) // must have exacly one new link

      assertFaultyStructureIsCaught( getTestChange("split", correctOldLink, Seq(correctNewLink,correctNewLink),   Seq()                  ) ) // must have at least two replaceInfos
      assertFaultyStructureIsCaught( getTestChange("split", correctOldLink, Seq(correctNewLink,correctNewLink),   Seq(correctReplaceInfo)) ) // must have at least two replaceInfos
      assertFaultyStructureIsCaught( getTestChange("split", correctOldLink, Seq(),               Seq(correctReplaceInfo,correctReplaceInfo)) ) // must have at least two new links
      assertFaultyStructureIsCaught( getTestChange("split", correctOldLink, Seq(correctNewLink), Seq(correctReplaceInfo,correctReplaceInfo)) ) // must have at least two new links
    }
  }


  test("Test When faulty/incongruent  old link ~ replaceInfo  data values Then throw ViiteException") {

    def assertFaultyOldIsCaught(faultyOldLink: LinkInfo) = {
      assertFaultyLinkNetworkChangeIsCaught(Seq(getTestChange("replace", oldLink=faultyOldLink)),"Testing incongruent old link ~ replaceInfo")
    }

    runWithRollback { // should not get to write to DB, as should fail, but just in case...
      assertFaultyOldIsCaught(LinkInfo("oldLink:11",  5.000, correctOldSegment)) // incongruent link ids for old link
      assertFaultyOldIsCaught(LinkInfo("oldLink:1",  55.000, correctOldSegment)) // incongruent lengths for old link
      assertFaultyOldIsCaught(LinkInfo("oldLink:1",   5.000, Seq(Point(0,0,0)))) // one point in the geometry is not enough
    }
  }

  test("Test When faulty/incongruent  new link ~ replaceInfo   data values Then throw ViiteException") {

    def assertFaultyNewIsCaught(faultyNewLink: LinkInfo) = {
      assertFaultyLinkNetworkChangeIsCaught(Seq(getTestChange("replace", newLinks = Seq(faultyNewLink))),"incongruent new link ~ replaceInfo")
    }

    runWithRollback { // should not get to write to DB, but just in case...
      assertFaultyNewIsCaught(LinkInfo("newLink:22", 10.000, correctNewSegment)) // incongruent link ids for new link
      assertFaultyNewIsCaught(LinkInfo("newLink:2",   9.000, correctNewSegment)) // incongruent lengths for new link
      assertFaultyNewIsCaught(LinkInfo("newLink:2",  10.000, Seq(Point(0.1, 0.1, 0.1)))) // one point in the geometry is not enough
    }
  }

  test("Test When faulty/incongruent replaceInfo ~old/new link data values Then throw ViiteException") {

    def assertFaultyReplaceInfoIsCaught(faultyReplaceInfo: ReplaceInfo) = {
      assertFaultyLinkNetworkChangeIsCaught(Seq(getTestChange("replace", replaceInfo = Seq(faultyReplaceInfo))), "incongruent replaceInfo ~ old/new link")
    }

    runWithRollback { // should not get to write to DB, but just in case...
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:12", 0.0,  5.000,   "newLink:2",   0.0, 10.000, digitizationChange=false, dummyMeta))
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1", -1.0,  5.000,   "newLink:2",   0.0, 10.000, digitizationChange=false, dummyMeta))
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0, 10.000,   "newLink:2",   0.0, 10.000, digitizationChange=false, dummyMeta))

      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0,  1.000,   "newLink:2",   0.0, 10.000, digitizationChange=false, dummyMeta))// would be allowed for split
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  1.0,  5.000,   "newLink:2",   0.0, 10.000, digitizationChange=false, dummyMeta))// would be allowed for split

      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0,  5.000,   "newLink:23",  0.0, 10.000, digitizationChange=false, dummyMeta))
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0,  5.000,   "newLink:2",  -1.0, 10.000, digitizationChange=false, dummyMeta))
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0,  5.000,   "newLink:2",   0.0, 20.000, digitizationChange=false, dummyMeta))

      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0,  5.000,   "newLink:2",   0.0,  2.000, digitizationChange=false, dummyMeta))// would be allowed for combine
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0,  5.000,   "newLink:2",   2.0, 10.000, digitizationChange=false, dummyMeta))// would be allowed for combine
    }
  }

  test("Test When data values are acceptable Then persisting succeeds") {

    // Building blocks for the correctly formatted test change object.
    val oldSegment: Seq[Point] = Seq(Point(0.0, 0.0, 0.0), Point( 0.0, 25.398, 0.0))
    val newSegment: Seq[Point] = Seq(Point(0.1, 0.1, 0.1), Point( 0.1, 27.562, 0.1))
    val oldLink: LinkInfo = LinkInfo("152148",    25.398, oldSegment)
    val newLink: LinkInfo = LinkInfo("newLink:2", 27.462, newSegment)
    val replaceInfo: ReplaceInfo = ReplaceInfo("152148", 0.0, 25.398, "newLink:2", 0.0, 27.462, digitizationChange=false, dummyMeta)

    def runLinkNetworkChangeWithRollback(faultyChangeList: Seq[LinkNetworkChange], testingWhat: String) = {
      runWithRollback { // do not save.
        val meta = testChangeMetaData.copy(changeSetName=testingWhat)
        linkNetworkUpdater.persistLinkNetworkChanges(faultyChangeList, meta)
      }
    }

    // Test change base object. Copy with parts to test
    val okTestChange = LinkNetworkChange("replace", oldLink, Seq(newLink), Seq(replaceInfo))

    val closeEnough = "testing length match close enough"
    // These must not fail to validations:
    runLinkNetworkChangeWithRollback(Seq(okTestChange.copy(oldLink=     LinkInfo("152148",    25.389, oldSegment) )), closeEnough) // length match is close enough; does not throw an error
    runLinkNetworkChangeWithRollback(Seq(okTestChange.copy(oldLink=     LinkInfo("152148",    25.407, oldSegment) )), closeEnough) // length match is close enough; does not throw an error
    runLinkNetworkChangeWithRollback(Seq(okTestChange.copy(newLinks=Seq(LinkInfo("newLink:2", 27.453, newSegment)))), closeEnough) // length match is close enough; does not throw an error
    runLinkNetworkChangeWithRollback(Seq(okTestChange.copy(newLinks=Seq(LinkInfo("newLink:2", 27.471, newSegment)))), closeEnough) // length match is close enough; does not throw an error
  }

  ////////////////////////////////////////////////////////////////////////////////
  //////// Test Network changes related stuff (persistLinkNetworkChanges) ////////

  test("Test When Proper Network change (version change) Then Viite data changed ") {

    // -- proper example with production data --
    //val linkIdWithoutVersion = "9f94baac-4445-4c73-93a4-7c9c86990b68"
    //val properNetworkChange = LinkNetworkChange(
    //  "replace",
    //  LinkInfo(linkIdWithoutVersion+":1", 215.009, Seq(
    //    Point(338224.664,7580280.386,0.0), Point(338183.762,7580491.459,0.0))),
    //  Seq(LinkInfo(
    //    linkIdWithoutVersion+":2", 215.009,Seq(
    //      Point(338224.664,7580280.386,283.143), Point(338218.583,7580314.838,282.307), Point(338208.619,7580364.672,280.785),
    //      Point(338199.665,7580410.316,280.075), Point(338191.485,7580450.038,280.02),  Point(338183.762,7580491.459,280.36)
    //    )
    //  )),
    //  Seq(ReplaceInfo(
    //    linkIdWithoutVersion+":1", 0.0, 215.009,
    //    linkIdWithoutVersion+":2", 0.0, 215.009,
    //    digitizationChange=false
    //  ))
    //)

    // -- data used from fixture reset data --
    // id     |roadway_number|order_number|link_id|start_measure|end_measure|side|geometry                                                                  |valid_from             |valid_to|created_by|created_time           |
    // 1008815|     126192013|           2|1347466|        0.000|    125.614|   2|LINESTRING ZM(547706.797 7130178.99 0 0, 547819.202 7130234.983 0 125.614)|2016-04-29 00:00:00.000|        |import    |2023-07-25 17:07:24.425|

    val oldLinkId = "1347466"       //"1347466"+":2" //
    val newLinkId = oldLinkId+":2"  //"1347466"      //
    val properNetworkChange = LinkNetworkChange(
      "replace",
      LinkInfo(oldLinkId, 125.614, Seq(
        Point(547706.797, 7130178.99, 0.0), Point(547819.202, 7130234.983, 0.0))),
      Seq(LinkInfo(
        newLinkId, 134.929, Seq(
          Point(547706.797, 7130178.99,  283.143), Point(547726.583, 7130212.838, 282.307),
          Point(547777.485, 7130220.038, 280.02 ), Point(547819.202, 7130234.983, 280.36)
        )
      )),
      Seq(ReplaceInfo(
        oldLinkId, 0.0, 125.614,
        newLinkId, 0.0, 134.929,
        digitizationChange = false,
        dummyMeta
      ))
    )

    val llDAO = new LinearLocationDAO

    runWithRollback { // we do not really want to change the DB
      //LinkDAO.createIfEmptyFetch(linkIdWithoutVersion+":1",DateTime.now().getMillis,LinkGeomSource.Change.value)

      // check there are link, and linear location available, for the old link ...
      val llBefore = llDAO.fetchByLinkId(Set(oldLinkId))
      LinkDAO.fetch(oldLinkId)            should not be empty
      llBefore                            should not be empty
      //println("Size = "+ llDAO.fetchByLinkId(Set(oldLinkId)).size )
      // ... but no corresponding stuff for the new link
      LinkDAO.fetch(newLinkId)            shouldBe empty
      llDAO.fetchByLinkId(Set(newLinkId)) shouldBe empty

      CalibrationPointDAO.fetchByLinkId(Seq(oldLinkId)) should not be empty
      CalibrationPointDAO.fetchByLinkId(Seq(newLinkId)) shouldBe empty

      linkNetworkUpdater.persistLinkNetworkChanges(
        Seq(properNetworkChange),
        "Test Network change (version)",
        DateTime.now,
        LinkGeomSource.Unknown // We have no "Test data" option.
      )
      //logger.debug("Persisted.")
//
      // check the old link stuff has been invalidated ...
      val llAfter = llDAO.fetchByLinkId(Set(newLinkId))
      LinkDAO.fetch(oldLinkId)            should not be empty // not deleted, and no valid_to available
      llDAO.fetchByLinkId(Set(oldLinkId)) shouldBe empty
      // ...and the new link stuff is alive
      LinkDAO.fetch(newLinkId)            should not be empty
      llAfter                             should not be empty

      llBefore should not be (llAfter)

      CalibrationPointDAO.fetchByLinkId(Seq(newLinkId)) should not be empty
    }
  }

}
