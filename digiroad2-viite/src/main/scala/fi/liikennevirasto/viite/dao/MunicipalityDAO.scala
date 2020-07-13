package fi.liikennevirasto.viite.dao

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.{StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation

/**
  * Created by venholat on 27.10.2016.
  */
object MunicipalityDAO {
  def getMunicipalityMapping = {
    Q.queryNA[(Long, Long)]("""SELECT id, ely_nro FROM MUNICIPALITY ORDER BY ely_nro ASC""").list.map(x => x._1 -> x._2).toMap
  }

  def getMunicipalityRoadMaintainers = {
    Q.queryNA[(Long, Long)]("""SELECT id, ROAD_MAINTAINER_ID FROM MUNICIPALITY""").list.map(x => x._1 -> x._2).toMap
  }

  def getMunicipalityNames = {
    Q.queryNA[(Long, String)]("""SELECT ID, NAME_FI FROM MUNICIPALITY""").list.map(x => x._1 -> x._2).toMap
  }

  def getMunicipalityIdByName(municipalityName: String): Map[Long, String] = {
    sql"""SELECT ID, NAME_FI FROM MUNICIPALITY WHERE LOWER(name_fi) = LOWER($municipalityName)""".as[(Long, String)].toMap
  }
}
