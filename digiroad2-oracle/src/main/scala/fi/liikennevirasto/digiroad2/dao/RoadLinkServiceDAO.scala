package fi.liikennevirasto.digiroad2.dao

import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

object RoadLinkServiceDAO {

  def updateExistingLinkPropertyRow(table: String, column: String, linkId: Long, username: Option[String], existingValue: Int, value: Int)= {
    if (existingValue != value) {
      sqlu"""update #$table
                 set #$column = $value,
                     modified_date = SYSDATE,
                     modified_by = $username
                 where link_id = $linkId""".execute
    }
  }

  def insertNewLinkProperty(table: String, column: String, linkId: Long, username: Option[String], value: Int) = {
    sqlu"""insert into #$table (id, link_id, #$column, modified_by )
                   select primary_key_seq.nextval, $linkId, $value, $username
                   from dual
                   where not exists (select * from #$table where link_id = $linkId)""".execute
  }

  def insertNewAdministrativeClass(table: String, column: String, vvhColumn: String, linkId: Long, username: Option[String], value: Int, mml_id: Option[Long], optionalVVHValue: Option[Int]) = {
    sqlu"""insert into #$table (id, link_id, #$column, created_by, mml_id, #$vvhColumn )
                   select primary_key_seq.nextval, $linkId, $value, $username, $mml_id, $optionalVVHValue
                   from dual
                   where not exists (select * from #$table where link_id = $linkId)""".execute
  }

  def updateExistingAdministrativeClass(table: String, column: String, vvhColumn: String, linkId: Long, username: Option[String], existingValue: Int, value: Int, mmlId: Option[Long], optionalVVHValue: Option[Int]) = {
    expireExistingLinkPropertyRow(table, linkId, username)
    insertValues(table, column, vvhColumn, linkId, username, existingValue, value, mmlId, optionalVVHValue)
  }

  def insertValues(table: String, column: String, vvhColumn: String, linkId: Long, username: Option[String], existingValue: Int, value: Int, mmlId: Option[Long], optionalVVHValue: Option[Int]) = {
    sqlu"""insert into #$table (id, link_id, #$column, created_by, mml_id, #$vvhColumn )
                   select primary_key_seq.nextval, $linkId, $value, $username, $mmlId, $optionalVVHValue
                   from dual
                   where exists (select * from #$table where link_id = $linkId)""".execute
  }
  def getLinkProperty(table: String, column: String, linkId: Long) = {
    sql"""select #$column from #$table where link_id = $linkId""".as[Int].firstOption
  }

  def expireExistingLinkPropertyRow(table: String, linkId: Long, username: Option[String]) = {
    sqlu"""update #$table
                 set valid_to = SYSDATE - 1,
                     modified_by = $username
                 where link_id = $linkId""".execute
  }

}
