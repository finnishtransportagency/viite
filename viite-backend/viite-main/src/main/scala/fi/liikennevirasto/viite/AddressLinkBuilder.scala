package fi.liikennevirasto.viite

import fi.vaylavirasto.viite.dao.MunicipalityDAO
import fi.vaylavirasto.viite.postgis.PostGISDatabaseScalikeJDBC.runWithReadOnlySession

trait AddressLinkBuilder {
  val RoadNumber = "roadnumber"

  /** Viite municipality to ELY code mapping */
  def municipalityToViiteELYMapping: Map[Long, Long] = {
    runWithReadOnlySession {
      MunicipalityDAO.getViiteMunicipalityToElyMapping
    }
  }

  def municipalityNamesMapping: Map[Long, String] = {
    runWithReadOnlySession {
      MunicipalityDAO.getMunicipalityNames
    }
  }

  protected def toIntNumber(value: Any): Int = {
    try {
      value.asInstanceOf[String].toInt
    } catch {
      case _: Throwable => 0
    }
  }

}
