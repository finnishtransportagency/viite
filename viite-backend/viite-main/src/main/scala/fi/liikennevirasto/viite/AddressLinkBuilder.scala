package fi.liikennevirasto.viite

import fi.vaylavirasto.viite.dao.MunicipalityDAO
import fi.vaylavirasto.viite.postgis.PostGISDatabase

trait AddressLinkBuilder {
  val RoadNumber = "roadnumber"

  /** Viite municipality to ELY code mapping */
  lazy val municipalityToViiteELYMapping: Map[Long, Long] = if (PostGISDatabase.isWithinSession)
    MunicipalityDAO.getViiteMunicipalityToElyMapping
  else
    PostGISDatabase.withDynSession {
      MunicipalityDAO.getViiteMunicipalityToElyMapping
    }

  lazy val municipalityNamesMapping: Map[Long, String] = if (PostGISDatabase.isWithinSession)
    MunicipalityDAO.getMunicipalityNames
  else
    PostGISDatabase.withDynSession {
      MunicipalityDAO.getMunicipalityNames
    }

  protected def toIntNumber(value: Any): Int = {
    try {
      value.asInstanceOf[String].toInt
    } catch {
      case _: Throwable => 0
    }
  }

}
