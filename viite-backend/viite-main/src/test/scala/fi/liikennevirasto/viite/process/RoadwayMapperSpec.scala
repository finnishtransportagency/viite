package fi.liikennevirasto.viite.process

import fi.liikennevirasto.viite.dao.TerminationCode.NoTermination
import fi.liikennevirasto.viite.dao._
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.CalibrationPointType.RoadAddressCP
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, LinkGeomSource, RoadPart, SideCode, Track}
import org.joda.time.DateTime
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FunSuite, Matchers}

class RoadwayMapperSpec extends FunSuite with Matchers{

  val mockRoadwayDAO: RoadwayDAO = MockitoSugar.mock[RoadwayDAO]
  val mockLinearLocationDAO: LinearLocationDAO = MockitoSugar.mock[LinearLocationDAO]

  test("Test roadwayAddressMapper.mapRoadAddresses() When giving it regular, current linear locations and roadways Then return the mapping of them in the form of Road Addresses."){
    val roadwayAddressMapper = new RoadwayAddressMapper(mockRoadwayDAO, mockLinearLocationDAO)

    val roadwayNumber = 12L
    val startDate = new DateTime(2007, 1, 1, 0, 0)

    val roadwayAddress = Roadway(2L, roadwayNumber, RoadPart(1551, 2), AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous, 140, 300, reversed = false, startDate, None, "test_user", None, 0, NoTermination, startDate)
    val linearLocations = Seq(
      LinearLocation(2L, 1, 125.toString, 45.0, 105.0, SideCode.TowardsDigitizing, 0, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(45.0,0.0), Point(105.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber),
      LinearLocation(3L, 2, 123.toString, 0.0, 49.0, SideCode.TowardsDigitizing, 0, (CalibrationPointReference.None, CalibrationPointReference(Some(250), Some(RoadAddressCP))), Seq(Point(105.0,0.0), Point(154.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber),
      LinearLocation(4L, 3, 124.toString, 0.0, 51.0, SideCode.TowardsDigitizing, 0, (CalibrationPointReference(Some(250), Some(RoadAddressCP)), CalibrationPointReference(Some(300), Some(RoadAddressCP))), Seq(Point(154.0,0.0), Point(205.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
    )

    val roadAddresses = roadwayAddressMapper.mapRoadAddresses(roadwayAddress, linearLocations)

    roadAddresses.size should be (3)

    val roadAddress1 = roadAddresses.find(_.linkId == 125.toString).get
    roadAddress1.addrMRange.start should be (140)
    roadAddress1.calibrationPoints should be (None, None)
    roadAddress1.discontinuity should be (Discontinuity.Continuous)

    val roadAddress2 = roadAddresses.find(_.linkId == 123.toString).get
    roadAddress2.addrMRange.start should be (roadAddress1.addrMRange.end)
    roadAddress2.addrMRange.end should be (250)
    roadAddress2.calibrationPoints should be (None, Some(ProjectCalibrationPoint(123.toString, 49.0, 250, RoadAddressCP)))
    roadAddress2.discontinuity should be (Discontinuity.Continuous)

    val roadAddress3 = roadAddresses.find(_.linkId == 124.toString).get
    roadAddress3.addrMRange.start should be (250)
    roadAddress3.addrMRange.end should be (300)
    roadAddress3.calibrationPoints should be (Some(ProjectCalibrationPoint(124.toString, 0.0, 250, RoadAddressCP)), Some(ProjectCalibrationPoint(124.toString, 51.0, 300, RoadAddressCP)))
    roadAddress3.discontinuity should be (Discontinuity.Discontinuous)
  }

  test("Test roadwayAddressMapper.mapRoadAddresses() When giving it regular, history linear locations and roadways Then return the mapping of them in the form of Road Addresses."){
    val roadwayAddressMapper = new RoadwayAddressMapper(mockRoadwayDAO, mockLinearLocationDAO)

    val roadwayNumber = 12L
    val currentStartDate = new DateTime(2007, 1, 1, 0, 0)
    val historyStartDate = new DateTime(1975, 7, 1, 0, 0)
    val historyEndDate = new DateTime(2006, 12, 31, 0, 0)

    val currentRoadwayAddress = Roadway(2L, roadwayNumber, RoadPart(1551, 2), AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous, 140, 300, reversed = false, currentStartDate, None, "test_user", None, 0, NoTermination, currentStartDate)
    val historyRoadwayAddress = Roadway(3L, roadwayNumber, RoadPart(1551, 2), AdministrativeClass.State, Track.Combined, Discontinuity.Discontinuous, 240, 400, reversed = false, historyStartDate, Some(historyEndDate), "test_user", None, 0, NoTermination, historyStartDate)
    val linearLocations = Seq(
      LinearLocation(2L, 1, 125.toString, 45.0, 105.0, SideCode.TowardsDigitizing, 0, (CalibrationPointReference.None, CalibrationPointReference.None), Seq(Point(45.0,0.0), Point(105.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber),
      LinearLocation(3L, 2, 123.toString, 0.0, 49.0, SideCode.TowardsDigitizing, 0, (CalibrationPointReference.None, CalibrationPointReference(Some(250), Some(RoadAddressCP))), Seq(Point(105.0,0.0), Point(154.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber),
      LinearLocation(4L, 3, 124.toString, 0.0, 51.0, SideCode.TowardsDigitizing, 0, (CalibrationPointReference(Some(250), Some(RoadAddressCP)), CalibrationPointReference(Some(300), Some(RoadAddressCP))), Seq(Point(154.0,0.0), Point(205.0,0.0)), LinkGeomSource.NormalLinkInterface, roadwayNumber)
    )

    when(mockRoadwayDAO.fetchByRoadwayNumber(roadwayNumber)).thenReturn(Some(currentRoadwayAddress))

    val roadAddresses = roadwayAddressMapper.mapRoadAddresses(historyRoadwayAddress, linearLocations)

    roadAddresses.size should be (3)

    val roadAddress1 = roadAddresses.find(_.linkId == 125.toString).get
    roadAddress1.addrMRange.start should be (240)
    roadAddress1.calibrationPoints should be (None, None)
    roadAddress1.discontinuity should be (Discontinuity.Continuous)

    val roadAddress2 = roadAddresses.find(_.linkId == 123.toString).get
    roadAddress2.addrMRange.start should be (roadAddress1.addrMRange.end)
    roadAddress2.addrMRange.end should be (350)
    roadAddress2.calibrationPoints should be (None, Some(ProjectCalibrationPoint(123.toString, 49.0, 350, RoadAddressCP)))
    roadAddress2.discontinuity should be (Discontinuity.Continuous)

    val roadAddress3 = roadAddresses.find(_.linkId == 124.toString).get
    roadAddress3.addrMRange.start should be (350)
    roadAddress3.addrMRange.end should be (400)
    roadAddress3.calibrationPoints should be (Some(ProjectCalibrationPoint(124.toString, 0.0, 350, RoadAddressCP)), Some(ProjectCalibrationPoint(124.toString, 51.0, 400, RoadAddressCP)))
    roadAddress3.discontinuity should be (Discontinuity.Discontinuous)
  }
}
