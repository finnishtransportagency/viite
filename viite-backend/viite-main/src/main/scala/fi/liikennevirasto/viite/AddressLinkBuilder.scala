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

  /** Viite municipality to EVK code mapping */
  def municipalityToViiteEVKMapping: Map[Long, String] = {
    println(s"Fetching municipality to EVK mapping from database")
    val result = runWithReadOnlySession {
      MunicipalityDAO.getViiteMunicipalityToEvkMapping
    }
    println(s"Fetched ${result.size} municipality to EVK mappings from database")
    result.foreach(r => println(s"Municipality code: ${r._1}, EVK code: ${r._2}"))
    result
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
