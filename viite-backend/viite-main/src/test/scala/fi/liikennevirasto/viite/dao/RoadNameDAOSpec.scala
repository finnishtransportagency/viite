package fi.liikennevirasto.viite.dao

import fi.vaylavirasto.viite.dao.{RoadNameScalikeDAO, RoadwayScalikeDAO, SequencesScalikeJDBC}
import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, RoadName, RoadNameForRoadAddressBrowser, RoadPart, RoadwayNew, Track}
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollbackScalike
import org.joda.time.DateTime
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class RoadNameDAOSpec extends AnyFunSuite with Matchers {


  test("Test when fetchRoadNamesForRoadAddressBrowser then return RoadNameForRoadAddressBrowser based on the query") {
    runWithRollbackScalike {
      val date = "2022-01-01"
      val roadNumber = 99
      val roadPartNumber = 1
      val olderDate = "1990-01-01"

      // create roadway
      RoadwayScalikeDAO.create(
        Seq(
          RoadwayNew(SequencesScalikeJDBC.nextRoadwayId, SequencesScalikeJDBC.nextRoadwayNumber, RoadPart(roadNumber, roadPartNumber), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 1000L), reversed=false, DateTime.parse(date), None, "test", Some("testRoad"), 1L, validFrom = DateTime.parse(date), validTo = None)
        )
      )

      // create road name
      RoadNameScalikeDAO.create(
        Seq(
          RoadName(SequencesScalikeJDBC.nextRoadNameId, roadNumber, "testRoad", Some(DateTime.parse(date)), None, Some(DateTime.parse(date)), None, "test")
        )
      )

      // fetch with situation date the same as the roadways startDate
      val resForFetch1 = RoadNameScalikeDAO.fetchRoadNamesForRoadAddressBrowser(Some(date), None, Some(roadNumber), None, None )
      resForFetch1.size should be (1)
      resForFetch1.head shouldBe a [RoadNameForRoadAddressBrowser]
      resForFetch1.head.roadNumber should equal(roadNumber)

      // fetch with situation date older than the roadway of the road name
      val resForFetch2 = RoadNameScalikeDAO.fetchRoadNamesForRoadAddressBrowser(Some(olderDate), None, Some(roadNumber), None, None)
      resForFetch2.size should be (0)
    }
  }

  test("Test fetchRoadNamesForRoadAddressBrowser when situation date is equal to the end date of the history road name and start date of the current road name, then return the current road name with the correct ely number") {
    /**
      *                                               Ely & Road name changes
      *                          TIME                 v
      * ------------------------------------------------------------->
      * 1990                                    2022-12-15
      *
      *                       old name
      *                       ely 1                       new name
      *                       history rw                  ely 9
      *  -------------------------------------------->    new rw
      *                                               --------------->
      */


    runWithRollbackScalike {
      val rwHistoryStartDate = "1990-01-01"
      val rwHistoryEndDate = "2022-12-14"
      val rwCurrentStartDate = "2022-12-15"
      val roadNameStartAndEndDate = "2022-12-15"
      val validFrom = "2022-01-01"
      val roadNumber = 99
      val roadPartNumber = 1
      val roadwayNumber = SequencesScalikeJDBC.nextRoadwayNumber
      val ely1 = 1L
      val ely9 = 9L
      val situationDate = "2022-12-15"
      val situationDateDayBeforeChanges = "2022-12-14"

      // ely changes from 1 to 9
      val historyRoadway = RoadwayNew(SequencesScalikeJDBC.nextRoadwayId, roadwayNumber, RoadPart(roadNumber, roadPartNumber), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 1000L), reversed=false, DateTime.parse(rwHistoryStartDate),Some(DateTime.parse(rwHistoryEndDate)), "test", Some("oldName"),ely1, validFrom = DateTime.parse(validFrom), validTo = None)
      val currentRoadway = RoadwayNew(SequencesScalikeJDBC.nextRoadwayId, roadwayNumber, RoadPart(roadNumber, roadPartNumber), AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, AddrMRange(0L, 1000L), reversed=false, DateTime.parse(rwCurrentStartDate), None, "test", Some("newName"),ely9, validFrom = DateTime.parse(validFrom), validTo = None)

      // create roadways
      RoadwayScalikeDAO.create(
        Seq(
          historyRoadway,
          currentRoadway
        )
      )

      // road name changes from oldName -> newName
      val historyRoadName =  RoadName(SequencesScalikeJDBC.nextRoadNameId, roadNumber, "oldName", Some(DateTime.parse(rwHistoryStartDate)), Some(DateTime.parse(roadNameStartAndEndDate)), Some(DateTime.parse(validFrom)), None, "test")
      val currentRoadName =  RoadName(SequencesScalikeJDBC.nextRoadNameId, roadNumber, "newName", Some(DateTime.parse(roadNameStartAndEndDate)), None, Some(DateTime.parse(validFrom)), None, "test")

      // create road names
      RoadNameScalikeDAO.create(
        Seq(
          historyRoadName,
          currentRoadName
        )
      )

      // result for situation date after changes
      val res = RoadNameScalikeDAO.fetchRoadNamesForRoadAddressBrowser(Some(situationDate), None, Some(roadNumber), None, None)
      res.size should be (1)
      res.head shouldBe a [RoadNameForRoadAddressBrowser]
      res.head.ely should be (9)
      res.head.roadName should be ("newName")

      // result for history situation date
      val res2 = RoadNameScalikeDAO.fetchRoadNamesForRoadAddressBrowser(Some(situationDateDayBeforeChanges), None, Some(roadNumber), None, None)
      res2.size should be (1)
      res2.head shouldBe a [RoadNameForRoadAddressBrowser]
      res2.head.ely should be (1)
      res2.head.roadName should be ("oldName")
    }
  }
}
