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

  /** Digiroad's municipality to ELY code mapping.
   * Database ely_nro field naming is from the era when Viite was still part of the Digiroad codebase.
   * For Viite ELY codes, see @getViiteMunicipalityToElyMapping
   * @TODO What if this could be removed? Used only in the Fixture reset, and test spec code. */
  def getDigiroadMunicipalityToElyMapping: Map[Long, Long] = {
    sql"""
      SELECT id, ely_nro
      FROM municipality
      ORDER BY ely_nro ASC
    """
      .map(rs => rs.long("id") -> rs.long("ely_nro"))
      .list()
      .apply()
      .toMap
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
