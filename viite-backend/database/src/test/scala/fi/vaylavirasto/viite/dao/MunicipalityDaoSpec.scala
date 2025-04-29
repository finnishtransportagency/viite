package fi.vaylavirasto.viite.dao

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback


/**
  * Created by pedrosag on 31-10-2016.
  */
class MunicipalityDaoSpec extends AnyFunSuite with Matchers{

  test("Test getViiteMunicipalityToElyMapping When getting all Viite ELYs (road maintainers) Then should return some"){
    runWithRollback{
      val municipalityRoadMaitainerMap = MunicipalityDAO.getViiteMunicipalityToElyMapping
      municipalityRoadMaitainerMap.isEmpty should be(false)
      municipalityRoadMaitainerMap.keySet.forall(_ > 0) should be(true)
      municipalityRoadMaitainerMap.values.forall(_ >= 0) should be(true)
    }
  }

}
