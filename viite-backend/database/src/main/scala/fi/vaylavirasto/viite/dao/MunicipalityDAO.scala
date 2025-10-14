package fi.vaylavirasto.viite.dao

import fi.vaylavirasto.viite.postgis.SessionProvider.session
import scalikejdbc._
/**
  * Created by venholat on 27.10.2016.
  */
object MunicipalityDAO extends BaseDAO {

  def fetchMunicipalityIds: Seq[Int] = {
    sql"""
         SELECT id
         FROM municipality
         """
      .map(_.int(1))
      .list()
      .apply()
  }

/** Viite municipality to ELY code mapping.
 * Viite ELY codes for each municipality are persisted in the ROAD_MAINTAINER column in the DB.*/
  def getViiteMunicipalityToElyMapping: Map[Long, Long] = {
    sql"""
      SELECT id, road_maintainer_id
      FROM municipality
    """
      .map(rs => rs.long("id") -> rs.long("ROAD_MAINTAINER_ID"))
      .list()
      .apply()
      .toMap
  }

  /** Viite municipality to EVK code mapping.
   * Viite EVK codes for each municipality are persisted in the ROAD_MAINTAINER column in the DB.*/
  def getViiteMunicipalityToEvkMapping: Map[Long, String] = {
    sql"""
      SELECT id, road_maintainer
      FROM municipality
    """
      .map(rs => rs.long("id") -> rs.string("ROAD_MAINTAINER"))
      .list()
      .apply()
      .toMap
  }

  /** Municipality to municipality name mapping */
  def getMunicipalityNames: Map[Long, String] = {
    sql"""
      SELECT id, name_fi
      FROM municipality
    """
      .map(rs => rs.long("ID") -> rs.string("NAME_FI"))
      .list()
      .apply()
      .toMap  }

/** Get the municipality id corresponding to the given <i>municipalityName</i>. */
  def getMunicipalityIdByName(municipalityName: String): Map[Long, String] = {
    sql"""
      SELECT id, name_fi
      FROM municipality
      WHERE LOWER(name_fi) = LOWER($municipalityName)
    """
      .map(rs => rs.long("ID") -> rs.string("NAME_FI"))
      .list()
      .apply()
      .toMap
  }
}
