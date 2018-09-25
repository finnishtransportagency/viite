package fi.liikennevirasto.viite.process

import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.util.Track
import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.dao.FloatingReason.NoFloating
import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import org.joda.time.DateTime
import org.mockito.Mockito.when
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FunSuite, Matchers}

class RoadwayAddressMapperSpec extends FunSuite with Matchers{

  val mockRoadAddressDAO: RoadAddressDAO = MockitoSugar.mock[RoadAddressDAO]
  val mockLinearLocationDAO: LinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]

  test("Should map current roadway linear location into road addresses"){
    val roadwayAddressMapper = new RoadwayAddressMapper(mockRoadAddressDAO, mockLinearLocationDAO)

    val roadwayNumber = 12L
    val startDate = new DateTime(2007, 1, 1, 0, 0)

    val roadwayAddress = RoadwayAddress(2L, roadwayNumber, 1551, 2, RoadType.PublicRoad, Track.Combined, Discontinuity.Discontinuous, 140, 300, false, startDate, None, "test_user", None, 0, NoTermination, startDate)
    val linearLocations = Seq(
      LinearLocation(2L, 1, 125, 45.0, 105.0, SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(45.0,0.0), Point(105.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber),
      LinearLocation(3L, 2, 123, 0.0, 49.0, SideCode.TowardsDigitizing, 0, (None, Some(250)), NoFloating, Seq(Point(105.0,0.0), Point(154.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber),
      LinearLocation(4L, 3, 124, 0.0, 51.0, SideCode.TowardsDigitizing, 0, (Some(250), Some(300)), NoFloating, Seq(Point(154.0,0.0), Point(205.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
    )

    val roadAddresses = roadwayAddressMapper.mapRoadAddresses(roadwayAddress, linearLocations)

    roadAddresses.size should be (3)

    val roadAddress1 = roadAddresses.find(_.linkId == 125).get
    roadAddress1.startAddrMValue should be (140)
    roadAddress1.calibrationPoints should be (None, None)
    roadAddress1.discontinuity should be (Discontinuity.Continuous)

    val roadAddress2 = roadAddresses.find(_.linkId == 123).get
    roadAddress2.startAddrMValue should be (roadAddress1.endAddrMValue)
    roadAddress2.endAddrMValue should be (250)
    roadAddress2.calibrationPoints should be (None, Some(CalibrationPoint(123, 49.0, 250)))
    roadAddress2.discontinuity should be (Discontinuity.Continuous)

    val roadAddress3 = roadAddresses.find(_.linkId == 124).get
    roadAddress3.startAddrMValue should be (250)
    roadAddress3.endAddrMValue should be (300)
    roadAddress3.calibrationPoints should be (Some(CalibrationPoint(124, 0.0, 250)), Some(CalibrationPoint(124, 51.0, 300)))
    roadAddress3.discontinuity should be (Discontinuity.Discontinuous)
  }

  test("Should map history roadway linear location into road addresses"){
    val roadwayAddressMapper = new RoadwayAddressMapper(mockRoadAddressDAO, mockLinearLocationDAO)

    val roadwayNumber = 12L
    val currentStartDate = new DateTime(2007, 1, 1, 0, 0)
    val historyStartDate = new DateTime(1975, 7, 1, 0, 0)
    val historyEndDate = new DateTime(2006, 12, 31, 0, 0)

    val currentRoadwayAddress = RoadwayAddress(2L, roadwayNumber, 1551, 2, RoadType.PublicRoad, Track.Combined, Discontinuity.Discontinuous, 140, 300, false, currentStartDate, None, "test_user", None, 0, NoTermination, currentStartDate)
    val historyRoadwayAddress = RoadwayAddress(3L, roadwayNumber, 1551, 2, RoadType.PublicRoad, Track.Combined, Discontinuity.Discontinuous, 240, 400, false, historyStartDate, Some(historyEndDate), "test_user", None, 0, NoTermination, historyStartDate)
    val linearLocations = Seq(
      LinearLocation(2L, 1, 125, 45.0, 105.0, SideCode.TowardsDigitizing, 0, (None, None), NoFloating, Seq(Point(45.0,0.0), Point(105.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber),
      LinearLocation(3L, 2, 123, 0.0, 49.0, SideCode.TowardsDigitizing, 0, (None, Some(250)), NoFloating, Seq(Point(105.0,0.0), Point(154.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber),
      LinearLocation(4L, 3, 124, 0.0, 51.0, SideCode.TowardsDigitizing, 0, (Some(250), Some(300)), NoFloating, Seq(Point(154.0,0.0), Point(205.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
    )

    when(mockRoadAddressDAO.fetchByRoadwayNumber(roadwayNumber)).thenReturn(Some(currentRoadwayAddress))

    val roadAddresses = roadwayAddressMapper.mapRoadAddresses(historyRoadwayAddress, linearLocations)

    roadAddresses.size should be (3)

    val roadAddress1 = roadAddresses.find(_.linkId == 125).get
    roadAddress1.startAddrMValue should be (240)
    roadAddress1.calibrationPoints should be (None, None)
    roadAddress1.discontinuity should be (Discontinuity.Continuous)

    val roadAddress2 = roadAddresses.find(_.linkId == 123).get
    roadAddress2.startAddrMValue should be (roadAddress1.endAddrMValue)
    roadAddress2.endAddrMValue should be (350)
    roadAddress2.calibrationPoints should be (None, Some(CalibrationPoint(123, 49.0, 350)))
    roadAddress2.discontinuity should be (Discontinuity.Continuous)

    val roadAddress3 = roadAddresses.find(_.linkId == 124).get
    roadAddress3.startAddrMValue should be (350)
    roadAddress3.endAddrMValue should be (400)
    roadAddress3.calibrationPoints should be (Some(CalibrationPoint(124, 0.0, 350)), Some(CalibrationPoint(124, 51.0, 400)))
    roadAddress3.discontinuity should be (Discontinuity.Discontinuous)
  }
}
