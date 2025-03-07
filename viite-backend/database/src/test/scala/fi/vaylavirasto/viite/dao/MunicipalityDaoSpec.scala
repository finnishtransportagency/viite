package fi.vaylavirasto.viite.dao

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithRollback


/**
  * Created by pedrosag on 31-10-2016.
  */
class MunicipalityDaoSpec extends AnyFunSuite with Matchers{

  test("Test getDigiroadMunicipalityToElyMapping When getting all municipalities Then should return some"){
    runWithRollback{
      val municipalityMap = MunicipalityDAO.getDigiroadMunicipalityToElyMapping
      municipalityMap.isEmpty should be(false)
      municipalityMap.keySet.forall(_ > 0) should be(true)
      municipalityMap.values.forall(_ >= 0) should be(true)
    }
  }

  test("Test getViiteMunicipalityToElyMapping When getting all Viite ELYs (road maintainers) Then should return some"){
    runWithRollback{
      val municipalityRoadMaitainerMap = MunicipalityDAO.getViiteMunicipalityToElyMapping
      municipalityRoadMaitainerMap.isEmpty should be(false)
      municipalityRoadMaitainerMap.keySet.forall(_ > 0) should be(true)
      municipalityRoadMaitainerMap.values.forall(_ >= 0) should be(true)
    }
  }

  test("Test if the Viite ELY (road maintainer) is correct for a Digiroad Ely (ElyNro) value " +
    "When checking if Viite ELY (road maintainer) contains mapped municipalities " +
    "Then it should be true") {
    runWithRollback{
      val municipalityMap = MunicipalityDAO.getDigiroadMunicipalityToElyMapping
      municipalityMap.isEmpty should be(false)

      val (selectedMunicipalityId, selectedElyNro) = municipalityMap.head // pick a municipality, whichever is returned first
      val viiteMunicipalityToELYMap = MunicipalityDAO.getViiteMunicipalityToElyMapping
      viiteMunicipalityToELYMap.isEmpty should be(false)

      // check that the picked municipality returns the correct Digiroad-ELY - Viite-ELY mapping
      viiteMunicipalityToELYMap.contains(selectedMunicipalityId) should be (true)
      selectedElyNro match {
        case 1 => viiteMunicipalityToELYMap(selectedMunicipalityId) should be (14)
        case 2 => viiteMunicipalityToELYMap(selectedMunicipalityId) should be (12)
        case 3 => viiteMunicipalityToELYMap(selectedMunicipalityId) should be (10)
        case 4 => viiteMunicipalityToELYMap(selectedMunicipalityId) should be (9)
        case 5 => viiteMunicipalityToELYMap(selectedMunicipalityId) should be (8)
        case 6 => viiteMunicipalityToELYMap(selectedMunicipalityId) should be (4)
        case 7 => viiteMunicipalityToELYMap(selectedMunicipalityId) should be (2)
        case 8 => viiteMunicipalityToELYMap(selectedMunicipalityId) should be (3)
        case 9 => viiteMunicipalityToELYMap(selectedMunicipalityId) should be (1)
        case 0 => viiteMunicipalityToELYMap(selectedMunicipalityId) should be (0)
      }
    }
  }

}
