package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.vaylavirasto.viite.dao.MunicipalityDAO

trait AddressLinkBuilder {
  val RoadNumber = "roadnumber"

  lazy val municipalityRoadMaintainerMapping: Map[Long, Long] = if (PostGISDatabase.isWithinSession)
    MunicipalityDAO.getMunicipalityRoadMaintainers
  else
    PostGISDatabase.withDynSession {
      MunicipalityDAO.getMunicipalityRoadMaintainers
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
