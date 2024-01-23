package fi.vaylavirasto.viite.dao

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation

/**
  * Created by venholat on 27.10.2016.
  */
object MunicipalityDAO {

  /** Digiroad's municipality to ELY code mapping.
   * Database ely_nro field naming is from the era when Viite was still part of the Digiroad codebase.
   * For Viite ELY codes, see @getViiteMunicipalityToElyMapping
   * @TODO What if this could be removed? Used only in the Fixture reset, and test spec code. */
  def getDigiroadMunicipalityToElyMapping = {
    Q.queryNA[(Long, Long)]("""SELECT id, ely_nro FROM MUNICIPALITY ORDER BY ely_nro ASC""").list.map(x => x._1 -> x._2).toMap
  }

/** Viite municipality to ELY code mapping.
 * Viite ELY codes for each municipality are persisted in the ROAD_MAINTAINER column in the DB.*/
  def getViiteMunicipalityToElyMapping = {
    Q.queryNA[(Long, Long)]("""SELECT id, ROAD_MAINTAINER_ID FROM MUNICIPALITY""").list.map(x => x._1 -> x._2).toMap
  }

  /** Municipality to municipality name mapping */
  def getMunicipalityNames = {
    Q.queryNA[(Long, String)]("""SELECT ID, NAME_FI FROM MUNICIPALITY""").list.map(x => x._1 -> x._2).toMap
  }

/** Get the municipality id corresponding to the given <i>municipalityName</i>. */
  def getMunicipalityIdByName(municipalityName: String): Map[Long, String] = {
    sql"""SELECT ID, NAME_FI FROM MUNICIPALITY WHERE LOWER(name_fi) = LOWER($municipalityName)""".as[(Long, String)].toMap
  }
}
