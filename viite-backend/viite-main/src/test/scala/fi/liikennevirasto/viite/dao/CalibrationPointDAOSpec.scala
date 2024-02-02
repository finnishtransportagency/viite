package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.model.{CalibrationPoint, CalibrationPointLocation, CalibrationPointType}
import fi.vaylavirasto.viite.postgis.PostGISDatabase.runWithRollback
import org.scalatest.{FunSuite, Matchers}

class CalibrationPointDAOSpec extends FunSuite with Matchers {

  test("Test compare calibration point types When compared Then comparison works correctly") {
    val a = CalibrationPointType.UserDefinedCP
    val b = CalibrationPointType.JunctionPointCP
    val c = CalibrationPointType.RoadAddressCP
    a < b should be(true)
    b < c should be(true)
    b > a should be(true)
    c > b should be(true)
    a <= b should be(true)
    b <= c should be(true)
    b >= a should be(true)
    c >= b should be(true)
    a <= a should be(true)
    b <= b should be(true)
    c <= c should be(true)
    a >= a should be(true)
    b >= b should be(true)
    c >= c should be(true)
    a == a should be(true)
    b == b should be(true)
    c == c should be(true)
  }

  test("Test fetch calibration point fetches by linkId") {
    runWithRollback { // fetches should not write to DB, but just in case...

      // fetch(linkId, startEnd)
      // -- (not found: There is no such link 123)
      val cp: Option[CalibrationPoint] = CalibrationPointDAO.fetch("123", CalibrationPointLocation.StartOfLink.value)
      cp shouldBe None
      // -- (is available)
      val cpBylinkId: Option[CalibrationPoint] = CalibrationPointDAO.fetch("4388117", CalibrationPointLocation.StartOfLink.value)
      cpBylinkId.get should not be None
      cpBylinkId.get.roadwayPointId should be (1)

      // fetch(Seq(linkId), startEnd)
      // -- (not found: There are no such links 123, 124)
      val cps: Seq[CalibrationPoint] = CalibrationPointDAO.fetch(Seq("123","124"), CalibrationPointLocation.StartOfLink.value)
      cps.size should be (0)
      //  -- (are available)
      val cpBylinkIds: Seq[CalibrationPoint] = CalibrationPointDAO.fetch(Seq("4388117","4388118"), CalibrationPointLocation.StartOfLink.value.toLong)
      cpBylinkIds.size should be (2)
      cpBylinkIds.head.roadwayPointId should be (1) // first points ...
      cpBylinkIds.last.roadwayPointId should be (2) // ... in the set

      // fetchByLinkId(linkIds)
      val cpList: Seq[CalibrationPoint] = CalibrationPointDAO.fetchByLinkId(Seq("1991144"))
      cpList.size should be (2)

    }
  }

  test("Test fetch calibration point fetches by roadwayPoints") {
    runWithRollback { // fetches should not write to DB, but just in case...

      // fetchByRoadwayPointId
      // // -- existing roadway point
      val cpByRp: Seq[CalibrationPoint] = CalibrationPointDAO.fetchByRoadwayPointId(118)
      cpByRp.size should be (1)
      cpByRp.head.linkId should be ("1991144")
      // -- non-existing roadway point
      val cpByRpNone: Seq[CalibrationPoint] = CalibrationPointDAO.fetchByRoadwayPointId(5432)
      cpByRpNone.size should be (0)

      // fetchByRoadwayPointIds
      // (fetch with available rwpoints)
      val cpByRps: Seq[CalibrationPoint] = CalibrationPointDAO.fetchByRoadwayPointIds(Seq(118,119))
      cpByRps.size should be (2)
      cpByRps.head.linkId should be ("1991144")
      cpByRps.last.linkId should be ("1991144")
      // (fetch with non-existing rwpoints)
      val cpByRpsFail: Seq[CalibrationPoint] = CalibrationPointDAO.fetchByRoadwayPointIds(Seq(11111))
      cpByRpsFail.size should be (0)
      // (fetch with partly non-existing rwpoints)
      val cpByRpsOkAndFail: Seq[CalibrationPoint] = CalibrationPointDAO.fetchByRoadwayPointIds(Seq(118,11111))
      cpByRpsOkAndFail.size should be (1)
      cpByRpsOkAndFail.head.linkId should be ("1991144")

    }
  }

  test("Test fetch calibration point fetches by calibration point id") {
    runWithRollback { // fetches should not write to DB, but just in case...

      // TODO is this a good way to handle things... ...should we throw an explicit exception from the fetch function?
      assertThrows[Exception] {
        // fetch(id)
        // -- (not found: There is no such id 987654321)
        val cpByIdNotFound: CalibrationPoint = CalibrationPointDAO.fetch(987654321)
        cpByIdNotFound.linkId should be ("4388117")
      }
      // -- (is found)
      val cpForId: Seq[CalibrationPoint] = CalibrationPointDAO.fetchByLinkId(Seq("1991144")) // get a calibr. point to get a valid id to fetch
      val cpById: CalibrationPoint = CalibrationPointDAO.fetch(cpForId.head.id)
      cpById.linkId should be ("1991144")

    }
  }

  test("Test fetch calibration point fetches by roadway number") {
    runWithRollback { // fetches should not write to DB, but just in case...

      //fetchIdByRoadwayNumberSection(roadwayNumber, startAddr, endAddr)
      val CpIdsBoth: Set[Long]  = CalibrationPointDAO.fetchIdByRoadwayNumberSection(39317,    0,4000)
      CpIdsBoth.size shouldBe (2)
      val CpIdsStart: Set[Long] = CalibrationPointDAO.fetchIdByRoadwayNumberSection(39317,    0,1000)
      CpIdsStart.size shouldBe (1)
      val CpIdsEnd: Set[Long]   = CalibrationPointDAO.fetchIdByRoadwayNumberSection(39317, 1000,4000)
      CpIdsEnd.size shouldBe (1)

    }
  }

  test("Test calibration point expiration") {
    runWithRollback { // tests should not make permanent changes to the DB.

      // first, get an existing calibration point to expire
      val CpToExpireOption: Option[CalibrationPoint] = CalibrationPointDAO.fetch("4388117", CalibrationPointLocation.StartOfLink.value)
      val CpToExpire = CpToExpireOption.get
      CpToExpire should not be None

      // expire it
      CalibrationPointDAO.expireById(Seq(CpToExpire.id))

      // check it is expired indeed
      val CpExpired: Option[CalibrationPoint] = CalibrationPointDAO.fetch("4388117", CalibrationPointLocation.StartOfLink.value)
      CpExpired shouldBe None

    }
  }

  def getCalPointToCreate: CalibrationPoint = {
    CalibrationPoint(
      NewIdValue,
      11,
      "1718137", //"1718142",
      98765,
      0,
      CalibrationPointLocation.StartOfLink,
      CalibrationPointType.UserDefinedCP,
      None,
      None,
      createdBy="thisIsATestRun"
    )
  }

  test("Test calibration point create with CalibrationPoint object") {

    val cpToCreate: CalibrationPoint = getCalPointToCreate

    runWithRollback { // tests should not make permanent changes to the DB.
      // first, check there is no such cp already
      val CpToCreateOption1: Option[CalibrationPoint] = CalibrationPointDAO.fetch(cpToCreate.linkId, cpToCreate.startOrEnd.value)
      CpToCreateOption1 shouldBe None

      // create it
      CalibrationPointDAO.create(cpToCreate)

      // check it is available after creation
      val CpToCreateOption2: Option[CalibrationPoint] = CalibrationPointDAO.fetch(cpToCreate.linkId, cpToCreate.startOrEnd.value)
      CpToCreateOption2 should not be None

    }
  }

  test("Test calibration point create with values") {

    val cp: CalibrationPoint = getCalPointToCreate

    runWithRollback { // tests should not make permanent changes to the DB.

      // first, check there is no such cp already
      val CpToCreateOption1: Option[CalibrationPoint] = CalibrationPointDAO.fetch(cp.linkId, cp.startOrEnd.value)
      CpToCreateOption1 shouldBe None

      // create it
      CalibrationPointDAO.create(cp.roadwayPointId, cp.linkId, cp.startOrEnd, cp.typeCode, cp.createdBy)

      // check it is available after creation
      val CpToCreateOption2: Option[CalibrationPoint] = CalibrationPointDAO.fetch(cp.linkId, cp.startOrEnd.value)
      CpToCreateOption2 should not be None

    }
  }

}
