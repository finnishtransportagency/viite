 package fi.vaylavirasto.viite.dynamicnetwork

//import fi.liikennevirasto.digiroad2.{LinkInfo, LinkNetworkChange, LinkNetworkUpdater, ReplaceInfo}
import fi.liikennevirasto.viite.dao.LinearLocationDAO
import fi.vaylavirasto.viite.dao.LinkDAO
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.LinkGeomSource
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import fi.vaylavirasto.viite.util.ViiteException
import org.joda.time.DateTime
import org.scalatest.{FunSuite, Matchers}

class LinkNetworkUpdaterSpec extends FunSuite with Matchers {


  val linkNetworkUpdater = new LinkNetworkUpdater() {
    //override def withDynSession[T](f: => T): T = f // overriding functionality: NOP
    override def withDynTransaction[T](f: => T): T = f // overriding functionality: NOP. We want to rollback.
  }

  private val correctChangeTypeJSON : String = s""""changeType": "replace""""
  private val correctOldLinkJSON    : String = s""""oldLink": { "linkId": "oldLink:1", "linkLength": 43.498, "geometry": "LINESTRING ZM(0 0 0, 1 1 1)"}"""
  private val correctNewLinkJSON    : String = s""""newLink": [{"linkId": "newLink:2", "linkLength": 49.631, "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)"}]"""
  private val correctReplaceInfoOldLinkJSON: String = s""""oldLinkId": "oldLink:1", "oldFromMValue": 0.0, "oldToMValue": 43.498"""
  private val correctReplaceInfoNewLinkJSON: String = s""""newLinkId": "newLink:2", "newFromMValue": 0.0, "newToMValue": 49.631"""
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
  private def getTestJSON( changeType: String = correctChangeTypeJSON,
                             oldLink: String = correctOldLinkJSON,
                             newLink: String = correctNewLinkJSON,
                             replaceInfo: String = correctReplaceInfoJSON ): String = {

    s"[" + // NOTE, excess array square brackets required due to JsonMethods.parse functionality. Correct, when the library allows.
    s"\n  {" +
    s"\n    $changeType," +
    s"\n    $oldLink," +
    s"\n    $newLink," +
    s"\n    $replaceInfo" +
    s"\n  }" +
    s"\n]" // NOTE, excess array square brackets required due to JsonMethods.parse functionality. Correct, when the library allows.
  }

  private val correctFirstPoint:  List[Point] = List(Point(0,   0,   0  ), Point(1,   1,   1  ))
  private val correctSecondPoint: List[Point] = List(Point(0.1, 0.1, 0.1), Point(1.1, 1.1, 1.1))
  private val correctChangeType: String = "replace"
  private val correctOldLink: LinkInfo = LinkInfo("oldLink:1", 43.498, correctFirstPoint)
  private val correctNewLink: LinkInfo = LinkInfo("newLink:2", 49.631, correctSecondPoint)
  private val correctReplaceInfo: ReplaceInfo = ReplaceInfo("oldLink:1", 0.0, 43.498, "newLink:2", 0.0, 49.631, digitizationChange=false)

  private def getTestChange(changeType:  String            = correctChangeType,
                            oldLink:     LinkInfo          = correctOldLink,
                            newLinks:    List[LinkInfo]    = List(correctNewLink),
                            replaceInfo: List[ReplaceInfo] = List(correctReplaceInfo)): LinkNetworkChange = {

    LinkNetworkChange(changeType, oldLink, newLinks, replaceInfo)
  }

  val testChangeMetaData = ChangeSetMetaData("Test-"+DateTime.now, DateTime.parse("7357-11-11"), LinkGeomSource.Unknown)


  private def assertFaultyLinkNetworkChangeJSONIsCaught(faultyJSON: String, testJSONName: String) = {
    assertThrows[ViiteException] {
      linkNetworkUpdater.parseJSONForLinkNetworkChanges(faultyJSON, testJSONName)
    }
  }

  private def assertFaultyLinkNetworkChangeIsCaught(faultyChangeList: List[LinkNetworkChange], testJSONName: String) = {
    assertThrows[ViiteException] {
      linkNetworkUpdater.persistLinkNetworkChanges(faultyChangeList, testChangeMetaData)
    }
  }

  test("Test When invalid   old link   JSON structure Then throw ViiteException") {

    def assertFaultyOldIsCaught(faultyOldLinkJSON: String) = {
      assertFaultyLinkNetworkChangeJSONIsCaught(getTestJSON(oldLink=faultyOldLinkJSON), "invalid old link JSON")
    }

    runWithRollback { // should not get to write to DB, but just in case...
      assertFaultyOldIsCaught("")
      assertFaultyOldIsCaught(""""oldLink": { }""")

      assertFaultyOldIsCaught(""""oldLink": {                        "linkLength": 43.498, "geometry": "LINESTRING ZM(0 0 0, 1 1 1)" }""")
      assertFaultyOldIsCaught(""""oldLink": { "linkId": 1,           "linkLength": 43.498, "geometry": "LINESTRING ZM(0 0 0, 1 1 1)" }""")
      assertFaultyOldIsCaught(""""oldLink": { "linkId": "oldLink:1",                       "geometry": "LINESTRING ZM(0 0 0, 1 1 1)" }""")
      assertFaultyOldIsCaught(""""oldLink": { "linkId": "oldLink:1", "linkLength": "asdf", "geometry": "LINESTRING ZM(0 0 0, 1 1 1)" }""")
      //assertFaultyOldIsCaught(""""oldLink": { "linkId": "oldLink:1", "linkLength": 43.498                                            }""") // This Passes?!? Why?
      assertFaultyOldIsCaught(""""oldLink": { "linkId": "oldLink:1", "linkLength": 43.498, "geometry": 1                             }""")

      assertFaultyOldIsCaught(""""oldLink": { "dummy":  "oldLink:1", "linkLength": 43.498, "geometry": "LINESTRING ZM(0 0 0, 1 1 1)" }""")
      assertFaultyOldIsCaught(""""oldLink": { "linkId": "oldLink:1", "linkLength": 43.498, "geometry": "LINESTRING ZM(0 0 0, 1 1 1)", "extrafield": "dummydata"""")
    }
  }

  test("Test When invalid   new link   JSON structure Then throw ViiteException") {

    def assertFaultyNewIsCaught(faultyNewLinkJSON: String) = {
      assertFaultyLinkNetworkChangeJSONIsCaught(getTestJSON(newLink=faultyNewLinkJSON), "invalid new link JSON")
    }

    runWithRollback { // should not get to write to DB, but just in case...
      assertFaultyNewIsCaught( "")
      assertFaultyNewIsCaught( """"newLink": { }""")
      assertFaultyNewIsCaught( """"newLink": {                        "linkLength": 49.631, "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)" }""")
      assertFaultyNewIsCaught( """"newLink": { "linkId": 1,           "linkLength": 49.631, "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)" }""")
      assertFaultyNewIsCaught( """"newLink": { "linkId": "newLink:2",                       "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)" }""")
      assertFaultyNewIsCaught( """"newLink": { "linkId": "newLink:2", "linkLength": "asdf", "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)" }""")
      assertFaultyNewIsCaught( """"newLink": { "linkId": "newLink:2", "linkLength": 49.631                                                        }""")
      assertFaultyNewIsCaught( """"newLink": { "linkId": "newLink:2", "linkLength": 49.631, "geometry": 1                                         }""")

      assertFaultyNewIsCaught( """"newLink": { "dummy":  "newLink:2", "linkLength": 49.631, "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)" }""")
      assertFaultyNewIsCaught(s""""newLink": { "linkId": "newLink:2", "linkLength": 49.631, "geometry": "LINESTRING ZM(0.1 0.1 0.1, 1.1 1.1 1.1)", "extrafield": "dummydata"""")
    }
  }

  test("Test When invalid replace info JSON structure Then throw ViiteException") {

    def assertFaultyReplaceInfoIsCaught(faultyReplaceInfoJSON: String) = {
      assertFaultyLinkNetworkChangeJSONIsCaught(getTestJSON(replaceInfo=faultyReplaceInfoJSON), "invalid replace info JSON")
    }

    val oldL = correctReplaceInfoOldLinkJSON
    val newL = correctReplaceInfoNewLinkJSON
    val digit = s"""\n  "digitizationChange": false\n"""

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

  test("Test When incongruent  old link ~ replaceInfo  data values Then throw ViiteException") {

    def assertFaultyOldIsCaught(faultyOldLink: LinkInfo) = {
      assertFaultyLinkNetworkChangeIsCaught(List(getTestChange(oldLink=faultyOldLink)),"incongruent old link ~ replaceInfo")
    }

    runWithRollback { // should not get to write to DB, but just in case...
      assertFaultyOldIsCaught(LinkInfo("oldLink:11", 43.498, correctFirstPoint))
      assertFaultyOldIsCaught(LinkInfo("oldLink:1",   3.498, correctFirstPoint))
      assertFaultyOldIsCaught(LinkInfo("oldLink:1",  43.498, List(Point(0,0,0))))
    }
  }

  test("Test When incongruent  new link ~ replaceInfo   data values Then throw ViiteException") {

    def assertFaultyNewIsCaught(faultyNewLink: LinkInfo) = {
      assertFaultyLinkNetworkChangeIsCaught(List(getTestChange(newLinks = List(faultyNewLink))),"incongruent new link ~ replaceInfo")
    }

    runWithRollback { // should not get to write to DB, but just in case...
      assertFaultyNewIsCaught(LinkInfo("newLink:22", 49.631, correctSecondPoint))
      assertFaultyNewIsCaught(LinkInfo("newLink:2",  99.999, correctSecondPoint))
      assertFaultyNewIsCaught(LinkInfo("newLink:2",  49.631, List(Point(0.1, 0.1, 0.1))))
    }
  }

  test("Test When incongruent replaceInfo ~old/new link data values Then throw ViiteException") {

    def assertFaultyReplaceInfoIsCaught(faultyReplaceInfo: ReplaceInfo) = {
      assertFaultyLinkNetworkChangeIsCaught(List(getTestChange(replaceInfo = List(faultyReplaceInfo))), "incongruent replaceInfo ~old/new link")
    }


    runWithRollback { // should not get to write to DB, but just in case...
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:12", 0.0, 43.498,   "newLink:2",   0.0, 49.631, digitizationChange=false))
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  1.0, 43.498,   "newLink:2",   0.0, 49.631, digitizationChange=false))
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0, 43.498,   "newLink:2",   0.0, 49.631, digitizationChange=false))// would be allowed for split
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0,  3.498,   "newLink:2",   0.0, 49.631, digitizationChange=false))// would be allowed for split
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0, 99.999,   "newLink:2",   0.0, 49.631, digitizationChange=false))

      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0, 43.498,   "newLink:23",  0.0, 49.631, digitizationChange=false))
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0, 43.498,   "newLink:2",  -1.0, 49.631, digitizationChange=false))
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0, 43.498,   "newLink:2",  10.0, 49.631, digitizationChange=false))// would be allowed for combine
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0, 43.498,   "newLink:2",   0.0,  9.631, digitizationChange=false))// would be allowed for combine
      assertFaultyReplaceInfoIsCaught(ReplaceInfo("oldLink:1",  0.0, 43.498,   "newLink:2",   0.0, 99.999, digitizationChange=false))
    }
  }

  test("Test When Proper Network change (version change) Then Viite data changed ") {

    // -- proper example with production data --
    //val linkIdWithoutVersion = "9f94baac-4445-4c73-93a4-7c9c86990b68"
    //val properNetworkChange = LinkNetworkChange(
    //  "replace",
    //  LinkInfo(linkIdWithoutVersion+":1", 215.009, List(
    //    Point(338224.664,7580280.386,0.0), Point(338183.762,7580491.459,0.0))),
    //  List(LinkInfo(
    //    linkIdWithoutVersion+":2", 215.009,List(
    //      Point(338224.664,7580280.386,283.143), Point(338218.583,7580314.838,282.307), Point(338208.619,7580364.672,280.785),
    //      Point(338199.665,7580410.316,280.075), Point(338191.485,7580450.038,280.02),  Point(338183.762,7580491.459,280.36)
    //    )
    //  )),
    //  List(ReplaceInfo(
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
      LinkInfo(oldLinkId, 125.614, List(
        Point(547706.797, 7130178.99, 0.0), Point(547819.202, 7130234.983, 0.0))),
      List(LinkInfo(
        newLinkId, 125.614, List(
          Point(547706.797, 7130178.99,  283.143), Point(547726.583, 7130212.838, 282.307),
          Point(547777.485, 7130220.038, 280.02 ), Point(547819.202, 7130234.983, 280.36)
        )
      )),
      List(ReplaceInfo(
        oldLinkId, 0.0, 125.614,
        newLinkId, 0.0, 125.614,
        digitizationChange = false
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

      // TODO runWithRollback ja ilmeisesti LinkNetworkUpdaterista täytyy tehdä class, jotta voi overridata withDynTransactionin
      // TODO runWithRollback ja ilmeisesti LinkNetworkUpdaterista täytyy tehdä class, jotta voi overridata withDynTransactionin
      // TODO runWithRollback ja ilmeisesti LinkNetworkUpdaterista täytyy tehdä class, jotta voi overridata withDynTransactionin
      // TODO runWithRollback ja ilmeisesti LinkNetworkUpdaterista täytyy tehdä class, jotta voi overridata withDynTransactionin

      linkNetworkUpdater.persistLinkNetworkChanges(
        List(properNetworkChange),
        "Testing proper Network change (version change)",
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
    }
  }

}