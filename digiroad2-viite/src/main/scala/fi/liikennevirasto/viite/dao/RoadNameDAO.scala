package fi.liikennevirasto.viite.dao

import org.joda.time.DateTime
import org.slf4j.LoggerFactory
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}

case class RoadName(id: Long, roadNumber: Long, roadName: String, startDate: Option[DateTime] = None, endDate: Option[DateTime] = None,
                    validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, createdBy: String)


object RoadNameDAO {
  private val logger = LoggerFactory.getLogger(getClass)
  private val roadsNameQueryBase =  s"""select id,road_number,road_Name,start_date,end_date,valid_from,valid_To,created_By
  from Road_Names"""
  implicit val getRoadNameRow = new GetResult[RoadName] {
    def apply(r: PositionedResult) = {
      val roadNameId = r.nextLong()
      val roadNumber = r.nextLong()
      val roadName = r.nextString()
      val startDate = r.nextDateOption().map(d => new DateTime(d.getTime))
      val endDate = r.nextDateOption().map(d => new DateTime(d.getTime))
      val validFrom = r.nextDateOption().map(d => new DateTime(d.getTime))
      val validTo = r.nextDateOption().map(d => new DateTime(d.getTime))
      val createdBy = r.nextString()

      RoadName(roadNameId, roadNumber, roadName, startDate, endDate, validFrom, validTo, createdBy)
    }
  }

  def dateParser(oDate: Option[DateTime]):String ={
    oDate match {
      case Some(date) => {
      val dtfOut: DateTimeFormatter = DateTimeFormat.forPattern("dd.MM.yyyy")
        dtfOut.print(date)
      }
      case _=>
        logger.error("Failed to parse date in RoadName search ")
        "01.01.1900"
    }
  }

  private def queryList(query: String) = {
    Q.queryNA[RoadName](query).iterator.toSeq
  }

  /**
    * Get roadNames ValidFrom - ValidTo determines database history vector and startDate and endDate determines data history
    */
  def getRoadNamesByRoadNumber(roadNumber: Long, oValidFrom: Option[DateTime] = None, oValidTo: Option[DateTime] = None,
                               oStartDate: Option[DateTime] = None, oEndDate: Option[DateTime] = None): Seq[RoadName] = {
    val validFromFilter = if (oValidFrom.isDefined) s"AND valid_From >= ${dateParser(oValidFrom)}" else " AND valid_From <= sysdate"
    val validToFilter = if (oValidTo.isDefined) s"AND valid_To <= ${dateParser(oValidTo)}" else " AND (valid_to > sysdate or valid_to is null)"
    val startDateFilter = if (oStartDate.isDefined) s"AND start_Date >= ${dateParser(oStartDate)}" else ""
    val endDateFilter = if (oEndDate.isDefined) s"AND end_Date <= ${dateParser(oEndDate)}" else ""

    val query =
      s"""$roadsNameQueryBase Where road_number = $roadNumber $validFromFilter $validToFilter $startDateFilter $endDateFilter"""
    queryList(query)
  }

  def getRoadNamesByRoadNumber(roadNumbers: Seq[Long]): Seq[RoadName] = {
    roadNumbers.flatMap(rn => getRoadNamesByRoadNumber(rn))
  }

  /**
    * Get current roadName by roadNumber (endDate is null)
    */
  def getCurrentRoadNamesByRoadNumber(roadNumber: Long): Seq[RoadName] = {
    val query =
      s"""$roadsNameQueryBase where road_number = $roadNumber and end_date is null """
    queryList(query)
  }

  /** // TODO
    * We probably want to use slightly slower prepared statement when we query with user given string to avoid sql injection
    * @param oRoadNumber
    * @param oValidFrom
    * @param oValidTo
    * @param startDate
    * @param endDate
    * @return
    */
  def getRoadNamesByRoadNameAndRoadNumber(oRoadNumber: Option[Long], oRoadName: Option[String], oValidFrom: Option[DateTime] = None, oValidTo: Option[DateTime] = None,
                                          startDate: Option[DateTime] = None, endDate: Option[DateTime] = None): Seq[RoadName] = {
    val validFromFilter = if (oValidFrom.isDefined) s"AND valid_From >= to_date(' ${dateParser(oValidFrom)}', 'dd.MM.RRRR')" else ""
    val validToFilter = if (oValidTo.isDefined) s"AND valid_To <= to_date('${dateParser(oValidTo)}', 'dd.MM.RRRR')" else ""
    val startDateFilter = if (startDate.isDefined) s"AND start_Date >= to_date('${dateParser(startDate)}', 'dd.MM.RRRR')" else ""
    val endDateFilter = if (endDate.isDefined) s"AND end_Date <= to_date('${dateParser(endDate)}', 'dd.MM.RRRR')" else ""
    val roadName = if (oRoadName.isDefined) s"road_Name = '${oRoadName.getOrElse("")}'" else ""
    val roadNameApplied = if (roadName == "") "" else " AND " //adds AND to SQL query if we already have roadname applied
    val roadNumber = if (oRoadNumber.isDefined) s" $roadNameApplied road_number = ${oRoadNumber.getOrElse("")}" else ""
    if (roadName + roadNumber != "") {
      val query =
        s"""$roadsNameQueryBase Where $roadName $roadNumber $validFromFilter $validToFilter $startDateFilter $endDateFilter"""
      queryList(query)
    } else
      Seq.empty[RoadName]
  }
}
