package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.process.strategy.{TerminatedTwoTrackSectionSynchronizer, AdministrativeClassTwoTrackSynchronizer}
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class TwoTrackSectionSynchronizerSpec extends AnyFunSuite with Matchers {

  def buildTestLink(id: Long, track: Track, addr: (Long, Long), status: RoadAddressChangeType, discontinuity: Discontinuity = Discontinuity.Continuous, adminClass: AdministrativeClass = AdministrativeClass.State, originalAdminClass: AdministrativeClass = AdministrativeClass.State): ProjectLink = {
    val roadPart = RoadPart(99999, 1)
    val baseLink = ProjectLink(
      id = id, roadPart = roadPart, track = track,
      discontinuity = discontinuity,
      addrMRange = AddrMRange(addr._1, addr._2),
      originalAddrMRange = AddrMRange(addr._1, addr._2),
      startDate = None, endDate = None, createdBy = Some("test"),
      linkId = s"link$id", startMValue = 0.0, endMValue = (addr._2 - addr._1).toDouble,
      sideCode = SideCode.TowardsDigitizing,
      geometry = Seq(Point(addr._1.toDouble, if (track == Track.RightSide) 0.0 else 1.0),
        Point(addr._2.toDouble, if (track == Track.RightSide) 0.0 else 1.0)),
      projectId = 1L, status = status,
      administrativeClass = adminClass,
      geometryLength = (addr._2 - addr._1).toDouble,
      roadwayId = id, linearLocationId = id,
      reversed = false, roadwayNumber = 100,
      roadMaintainer = ArealRoadMaintainer.getEVK(8),
      linkGeometryTimeStamp = 0L
    )
    
    // ProjectLink.originalAdministrativeClass is derived from a DB lookup (roadwayDAO), not a constructor
    // parameter. Without a real roadway row it falls back to administrativeClass, making it impossible
    // to test scenarios where the two differ. Override the method via subclass instead.
    new ProjectLink(baseLink.id, baseLink.roadPart, baseLink.track, baseLink.discontinuity, 
      baseLink.addrMRange, baseLink.originalAddrMRange, baseLink.startDate, baseLink.endDate, 
      baseLink.createdBy, baseLink.linkId, baseLink.startMValue, baseLink.endMValue, 
      baseLink.sideCode, baseLink.calibrationPointTypes, baseLink.originalCalibrationPointTypes,
      baseLink.geometry, baseLink.projectId, baseLink.status, baseLink.administrativeClass,
      baseLink.linkGeomSource, baseLink.geometryLength, baseLink.roadwayId, baseLink.linearLocationId,
      baseLink.roadMaintainer, baseLink.reversed, baseLink.connectedLinkId,
      baseLink.linkGeometryTimeStamp, baseLink.roadwayNumber, baseLink.roadName,
      baseLink.roadAddressLength, baseLink.roadAddressStartAddrM, baseLink.roadAddressEndAddrM,
      baseLink.roadAddressTrack, baseLink.roadAddressRoadPart) {
      
      override def originalAdministrativeClass: AdministrativeClass = originalAdminClass
    }
  }

  test("Case 1 - Terminations: Synchronize road part start") {
    /**
     * Legend: Terminated: ==> | Nonterminated: -->
     *
     * Before: (15, 25) -> Avg 20
     * 0     15        40
     * ======>--------->
     * ==========>----->
     * 0         25    40
     *
     * After:
     * 0        20     40
     * =========>------->
     * =========>------->
     * 0        20     40
     */
    val links = Seq(
      buildTestLink(10, Track.RightSide, (0, 15), RoadAddressChangeType.Termination),
      buildTestLink(11, Track.LeftSide, (0, 25), RoadAddressChangeType.Termination),
      buildTestLink(12, Track.RightSide, (15, 40), RoadAddressChangeType.Unchanged),
      buildTestLink(13, Track.LeftSide, (25, 40), RoadAddressChangeType.Unchanged)
    )

    val result = TerminatedTwoTrackSectionSynchronizer.adjustTerminations(links)

    result.find(_.id == 10).get.addrMRange should be(AddrMRange(0, 20))
    result.find(_.id == 11).get.addrMRange should be(AddrMRange(0, 20))
    result.find(_.id == 12).get.addrMRange should be(AddrMRange(20, 40))
    result.find(_.id == 13).get.addrMRange should be(AddrMRange(20, 40))
  }

  test("Case 2 - Terminations: Synchronize road part middle") {
    /**
     * Before: (60, 62) -> Avg 61 | (80, 84) -> Avg 82
     * 40    60    80    100
     * ------>=====>----->
     * -------->======>-->
     * 40      62     84 100
     *
     * After:
     * 40     61    82   100
     * ------->=====>----->
     * ------->=====>----->
     * 40     61    82   100
     */
    val links = Seq(
      buildTestLink(20, Track.RightSide, (40, 60), RoadAddressChangeType.Unchanged, Discontinuity.MinorDiscontinuity),
      buildTestLink(21, Track.RightSide, (60, 80), RoadAddressChangeType.Termination),
      buildTestLink(22, Track.LeftSide, (40, 62), RoadAddressChangeType.Unchanged, Discontinuity.MinorDiscontinuity),
      buildTestLink(23, Track.LeftSide, (62, 84), RoadAddressChangeType.Termination),
      buildTestLink(24, Track.RightSide, (80, 100), RoadAddressChangeType.Unchanged),
      buildTestLink(25, Track.LeftSide, (84, 100), RoadAddressChangeType.Unchanged)
    )

    val result = TerminatedTwoTrackSectionSynchronizer.adjustTerminations(links)

    result.find(_.id == 20).get.addrMRange should be(AddrMRange(40, 61))
    result.find(_.id == 22).get.addrMRange should be(AddrMRange(40, 61))
    result.find(_.id == 21).get.addrMRange should be(AddrMRange(61, 82))
    result.find(_.id == 23).get.addrMRange should be(AddrMRange(61, 82))
    result.find(_.id == 24).get.addrMRange should be(AddrMRange(82, 100))
    result.find(_.id == 25).get.addrMRange should be(AddrMRange(82, 100))
  }

  // --- CASE 3: END OF ROAD PART ---
  test("Case 3- Terminations: Synchronize road part end") {
    /**
     * Before: (145, 135) -> Avg 140
     * 100        145   160
     * ----------->=====>
       * -------->========>
       * 100     135      160
       *
       * After:
       * 100       140    160
       * ----------->=====>
       * ----------->=====>
     * 100       140    160
     */
    val links = Seq(
      buildTestLink(30, Track.RightSide, (100, 145), RoadAddressChangeType.Unchanged),
      buildTestLink(31, Track.RightSide, (145, 160), RoadAddressChangeType.Termination),
      buildTestLink(32, Track.LeftSide, (100, 135), RoadAddressChangeType.Unchanged),
      buildTestLink(33, Track.LeftSide, (135, 160), RoadAddressChangeType.Termination)
    )

    val result = TerminatedTwoTrackSectionSynchronizer.adjustTerminations(links)

    result.find(_.id == 30).get.addrMRange should be(AddrMRange(100, 140))
    result.find(_.id == 32).get.addrMRange should be(AddrMRange(100, 140))

    result.find(_.id == 31).get.addrMRange should be(AddrMRange(140, 160))
    result.find(_.id == 33).get.addrMRange should be(AddrMRange(140, 160))
  }

  test("Case 4 - Terminations: Large gap should NOT synchronize") {
    /**
     * Before: Large gap between terminations (> maxDiffForTracks = 10)
     * 0     20         50
     * ======>--------> 
     * ======>-------------->
     * 0     40           60
     *
     * After: Should remain unchanged because gap is too large
     * 0     20         50
     * ======>--------> 
     * ======>-------------->
     * 0     40           60
     */
    val links = Seq(
      buildTestLink(40, Track.RightSide, (0, 20), RoadAddressChangeType.Termination),
      buildTestLink(41, Track.RightSide, (20, 50), RoadAddressChangeType.Unchanged),
      buildTestLink(42, Track.LeftSide, (0, 40), RoadAddressChangeType.Termination),
      buildTestLink(43, Track.LeftSide, (40, 60), RoadAddressChangeType.Unchanged)
    )

    val result = TerminatedTwoTrackSectionSynchronizer.adjustTerminations(links)

    // Links should remain unchanged because gap (40-20=20) > maxDiffForTracks (10)
    result.find(_.id == 40).get.addrMRange should be(AddrMRange(0, 20))
    result.find(_.id == 41).get.addrMRange should be(AddrMRange(20, 50))
    result.find(_.id == 42).get.addrMRange should be(AddrMRange(0, 40))
    result.find(_.id == 43).get.addrMRange should be(AddrMRange(40, 60))
  }

  test("Case 5 - Administrative class change: No administrative class changes should not affect links") {
    val links = Seq(
      buildTestLink(100, Track.RightSide, (0, 100), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(101, Track.LeftSide, (0, 100), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality)
    )

    val result = TerminatedTwoTrackSectionSynchronizer.adjustTerminations(links)

    // Links should remain unchanged
    result.find(_.id == 100).get.addrMRange should be(AddrMRange(0, 100))
    result.find(_.id == 101).get.addrMRange should be(AddrMRange(0, 100))
  }

  test("Case 6- Administrative Class Change: Synchronize road part start") {
    /**
      * Legend: Admin Class Changed: ~~> | Unchanged: -->
      *
      * Before:
      * 0     15      40         60
      * ~~~~~~=>~~~~~~=>---------> 
      * ~~~~~~~~=>~~~~~~=>------->
      * 0       25      50       62
      *
      * After:
      * 0     15      45         60
      * ~~~~~~=>~~~~~~=>---------> 
      * ~~~~~~~~=>~~~~~~=>------->
      * 0       25      45       62
      */
    val links = Seq(
      buildTestLink(60, Track.RightSide, (0, 15), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(61, Track.RightSide, (15, 40), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(62, Track.LeftSide, (0, 25), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(63, Track.LeftSide, (25, 50), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(64, Track.RightSide, (40, 60), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(65, Track.LeftSide, (50, 62), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality)
    )

    val result = AdministrativeClassTwoTrackSynchronizer.adjustAdministrativeClassChanges(links)

    result.find(_.id == 60).get.addrMRange should be(AddrMRange(0, 15))
    result.find(_.id == 60).get.originalAddrMRange should be(AddrMRange(0, 15))
    result.find(_.id == 62).get.addrMRange should be(AddrMRange(0, 25))
    result.find(_.id == 62).get.originalAddrMRange should be(AddrMRange(0, 25))

    result.find(_.id == 61).get.addrMRange should be(AddrMRange(15, 45))
    result.find(_.id == 61).get.originalAddrMRange should be(AddrMRange(15, 45))
    result.find(_.id == 63).get.addrMRange should be(AddrMRange(25, 45))
    result.find(_.id == 63).get.originalAddrMRange should be(AddrMRange(25, 45))

    result.find(_.id == 64).get.addrMRange should be(AddrMRange(45, 60))
    result.find(_.id == 64).get.originalAddrMRange should be(AddrMRange(45, 60))
    result.find(_.id == 65).get.addrMRange should be(AddrMRange(45, 62))
    result.find(_.id == 65).get.originalAddrMRange should be(AddrMRange(45, 62))
  }

  test("Case 7 - Administrative Class Change: Synchronize road part middle with minor discontinuity") {
    /**
      * Before:
      * 38   60      80                    80      100   120
      * ----->~~~~~~~> (minor discontiniuity)~~~~~~~>-----> 
      * 36   56      76                    76      104   126
      * ----->~~~~~~~> (minor discontiniuity)~~~~~~~>-----> 
      *
      * After:
      * 38   58      80                    80      102   120
      * ----->~~~~~~~> (minor discontiniuity)~~~~~~~>-----> 
      * 36   58      76                    76      102   126
      * ----->~~~~~~~> (minor discontiniuity)~~~~~~~>-----> 
      *
      * avg start = (60+56)/2 = 58  →  preceding ends snap to 58, first admin links start at 58
      * avg end   = (100+104)/2 = 102  →  last admin links end at 102, following links start at 102
      * internal boundary (80 / 76) is untouched
      */
    val links = Seq(
      buildTestLink(70, Track.RightSide, (38, 60),  RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(71, Track.RightSide, (60, 80),  RoadAddressChangeType.Unchanged, Discontinuity.MinorDiscontinuity, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(72, Track.RightSide, (80, 100), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(73, Track.RightSide, (100, 120), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),

      buildTestLink(74, Track.LeftSide, (36, 56),  RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(75, Track.LeftSide, (56, 76),  RoadAddressChangeType.Unchanged, Discontinuity.MinorDiscontinuity, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(76, Track.LeftSide, (76, 104), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(77, Track.LeftSide, (104, 126), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality)
    )

    val result = AdministrativeClassTwoTrackSynchronizer.adjustAdministrativeClassChanges(links)

    // Preceding links: only ends are snapped, starts are preserved
    result.find(_.id == 70).get.addrMRange should be(AddrMRange(38, 58))
    result.find(_.id == 70).get.originalAddrMRange should be(AddrMRange(38, 58))
    result.find(_.id == 74).get.addrMRange should be(AddrMRange(36, 58))
    result.find(_.id == 74).get.originalAddrMRange should be(AddrMRange(36, 58))

    // First admin-changed links: start averaged to 58, end (80/76) untouched
    result.find(_.id == 71).get.addrMRange should be(AddrMRange(58, 80))
    result.find(_.id == 71).get.originalAddrMRange should be(AddrMRange(58, 80))
    result.find(_.id == 75).get.addrMRange should be(AddrMRange(58, 76))
    result.find(_.id == 75).get.originalAddrMRange should be(AddrMRange(58, 76))

    // Last admin-changed links: start (80/76) untouched, end averaged to 102
    result.find(_.id == 72).get.addrMRange should be(AddrMRange(80, 102))
    result.find(_.id == 72).get.originalAddrMRange should be(AddrMRange(80, 102))
    result.find(_.id == 76).get.addrMRange should be(AddrMRange(76, 102))
    result.find(_.id == 76).get.originalAddrMRange should be(AddrMRange(76, 102))

    // Following links: starts snapped to 102
    result.find(_.id == 73).get.addrMRange should be(AddrMRange(102, 120))
    result.find(_.id == 73).get.originalAddrMRange should be(AddrMRange(102, 120))
    result.find(_.id == 77).get.addrMRange should be(AddrMRange(102, 126))
    result.find(_.id == 77).get.originalAddrMRange should be(AddrMRange(102, 126))
  }

  test("Case 8 - Administrative Class Change: Synchronize road part end with 1 link on track 1 and 2 links on track 2") {
    /**
      * Before: (145, 135) -> Avg 140
      * 100        145   160
      * ---------->~~~~~~~>
      * -------->~~~~~~~~~~>
      * 100     135      160
      *
      * After:
      * 100       140    160
      * ---------->~~~~~~~~~~>
      * ---------->~~~~~~~~~~>
      * 100       140     160
      */
    val links = Seq(
      buildTestLink(80, Track.RightSide, (100, 145), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(81, Track.RightSide, (145, 160), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(82, Track.LeftSide, (100, 135), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(83, Track.LeftSide, (135, 160), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality)
    )

    val result = AdministrativeClassTwoTrackSynchronizer.adjustAdministrativeClassChanges(links)

    result.find(_.id == 80).get.addrMRange should be(AddrMRange(100, 140))
    result.find(_.id == 80).get.originalAddrMRange should be(AddrMRange(100, 140))

    result.find(_.id == 81).get.addrMRange should be(AddrMRange(140, 160))
    result.find(_.id == 81).get.originalAddrMRange should be(AddrMRange(140, 160))

    result.find(_.id == 82).get.addrMRange should be(AddrMRange(100, 140))
    result.find(_.id == 82).get.originalAddrMRange should be(AddrMRange(100, 140))

    result.find(_.id == 83).get.addrMRange should be(AddrMRange(140, 160))
    result.find(_.id == 83).get.originalAddrMRange should be(AddrMRange(140, 160))
  }

  test("Case 9 - Administrative Class Change: Uneven link counts across tracks") {
    /**
      * Before:
      * 0    50                 150   180
      * ------>~~~~~~~~~~~~~~~~~>------>
      * ------->~~~~~~~~>~~~~~~~~>------->
      * 0      54      105     160   180
      *
      * After: Start avg: (50+54)/2 = 52, end avg: (150+160)/2 = 155
      * 0   52                  155   180
      * ---->~~~~~~~~~~~~~~~~~~~>------>  
      * ---->~~~~~~~~>~~~~~~~~~~~>------>  
      * 0   52      105        155   180
      */
    val links = Seq(
      buildTestLink(90, Track.RightSide, (0, 50), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(91, Track.RightSide, (50, 150), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(92, Track.RightSide, (150, 180), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),

      buildTestLink(93, Track.LeftSide, (0, 54), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(94, Track.LeftSide, (54, 105), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(95, Track.LeftSide, (105, 160), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(96, Track.LeftSide, (160, 180), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality)
    )

    val result = AdministrativeClassTwoTrackSynchronizer.adjustAdministrativeClassChanges(links)

    // Preceding links: end snapped to averaged start boundary (52)
    result.find(_.id == 90).get.addrMRange should be(AddrMRange(0, 52))
    result.find(_.id == 90).get.originalAddrMRange should be(AddrMRange(0, 52))
    result.find(_.id == 93).get.addrMRange should be(AddrMRange(0, 52))
    result.find(_.id == 93).get.originalAddrMRange should be(AddrMRange(0, 52))

    // Changed links: right has one long link, left has two split links
    result.find(_.id == 91).get.addrMRange should be(AddrMRange(52, 155))
    result.find(_.id == 91).get.originalAddrMRange should be(AddrMRange(52, 155))
    result.find(_.id == 94).get.addrMRange should be(AddrMRange(52, 105))
    result.find(_.id == 94).get.originalAddrMRange should be(AddrMRange(52, 105))
    result.find(_.id == 95).get.addrMRange should be(AddrMRange(105, 155))
    result.find(_.id == 95).get.originalAddrMRange should be(AddrMRange(105, 155))

    // Following links: start snapped to averaged end boundary (155)
    result.find(_.id == 92).get.addrMRange should be(AddrMRange(155, 180))
    result.find(_.id == 92).get.originalAddrMRange should be(AddrMRange(155, 180))
    result.find(_.id == 96).get.addrMRange should be(AddrMRange(155, 180))
    result.find(_.id == 96).get.originalAddrMRange should be(AddrMRange(155, 180))
  }

  test("Case 10 - Administrative Class Change: Minor discontinuity before changed section updates original ranges") {
    /**
      * Before:
      * 40    60    80    100
      * ------>~~~~~>----->    (minor disc)
      * -------->~~~~~~>-->    (minor disc)
      * 40      62    84 100
      *
      * After: Start avg: (60+62)/2 = 61, end avg: (80+84)/2 = 82
      * 40     61    82   100
      * ------>~~~~~>----->  
      * ------>~~~~~>----->  
      * 40     61    82   100
      */
    val links = Seq(
      buildTestLink(110, Track.RightSide, (40, 60), RoadAddressChangeType.Unchanged, Discontinuity.MinorDiscontinuity, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(111, Track.RightSide, (60, 80), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(112, Track.RightSide, (80, 100), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),

      buildTestLink(113, Track.LeftSide, (40, 62), RoadAddressChangeType.Unchanged, Discontinuity.MinorDiscontinuity, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(114, Track.LeftSide, (62, 84), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.State, originalAdminClass = AdministrativeClass.Municipality),
      buildTestLink(115, Track.LeftSide, (84, 100), RoadAddressChangeType.Unchanged, adminClass = AdministrativeClass.Municipality, originalAdminClass = AdministrativeClass.Municipality)
    )

    val result = AdministrativeClassTwoTrackSynchronizer.adjustAdministrativeClassChanges(links)

    result.find(_.id == 110).get.addrMRange should be(AddrMRange(40, 61))
    result.find(_.id == 110).get.originalAddrMRange should be(AddrMRange(40, 61))
    result.find(_.id == 113).get.addrMRange should be(AddrMRange(40, 61))
    result.find(_.id == 113).get.originalAddrMRange should be(AddrMRange(40, 61))

    result.find(_.id == 111).get.addrMRange should be(AddrMRange(61, 82))
    result.find(_.id == 111).get.originalAddrMRange should be(AddrMRange(61, 82))
    result.find(_.id == 114).get.addrMRange should be(AddrMRange(61, 82))
    result.find(_.id == 114).get.originalAddrMRange should be(AddrMRange(61, 82))

    result.find(_.id == 112).get.addrMRange should be(AddrMRange(82, 100))
    result.find(_.id == 112).get.originalAddrMRange should be(AddrMRange(82, 100))
    result.find(_.id == 115).get.addrMRange should be(AddrMRange(82, 100))
    result.find(_.id == 115).get.originalAddrMRange should be(AddrMRange(82, 100))
  }
}
