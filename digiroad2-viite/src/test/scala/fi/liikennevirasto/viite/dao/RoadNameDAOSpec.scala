package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.AdministrativeClass
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.util.Track
import org.scalatest.{FunSuite, Matchers}
import org.joda.time.DateTime
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

class RoadNameDAOSpec extends FunSuite with Matchers {

  def runWithRollback(f: => Unit): Unit = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      f
      dynamicSession.rollback()
    }
  }

  val roadwayDAO = new RoadwayDAO

  test("Test when fetchRoadNamesForRoadAddressBrowser then return RoadNameForRoadAddressBrowser based on the query") {
    runWithRollback {
      val date = "2022-01-01"
      val roadNumber = 99
      val roadPartNumber = 1
      val olderDate = "1990-01-01"

      // create roadway
      roadwayDAO.create(
        Seq(
          Roadway(Sequences.nextRoadwayId, Sequences.nextRoadwayNumber, roadNumber, roadPartNumber, AdministrativeClass.State, Track.Combined, Discontinuity.EndOfRoad, 0L, 1000L, false, DateTime.parse(date), None, "test", Some("testRoad"), 1L, validFrom = DateTime.parse(date), validTo = None)
        )
      )

      // create road name
      RoadNameDAO.create(
        Seq(
          RoadName(Sequences.nextRoadNameId, roadNumber, "testRoad", Some(DateTime.parse(date)), None, Some(DateTime.parse(date)), None, "test")
        )
      )

      // fetch with situation date the same as the roadways startDate
      val resForFetch1 = RoadNameDAO.fetchRoadNamesForRoadAddressBrowser(Some(date), None, Some(roadNumber), None, None )
      resForFetch1.size should be (1)
      resForFetch1.head shouldBe a [RoadNameForRoadAddressBrowser]
      resForFetch1.head.roadNumber should equal(roadNumber)

      // fetch with situation date older than the roadway of the road name
      val resForFetch2 = RoadNameDAO.fetchRoadNamesForRoadAddressBrowser(Some(olderDate), None, Some(roadNumber), None, None)
      resForFetch2.size should be (0)
    }
  }


}
