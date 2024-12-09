package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.util.LogUtils.time
import fi.liikennevirasto.viite.NewIdValue
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import fi.vaylavirasto.viite.geometry.{BoundingRectangle, Point}
import fi.vaylavirasto.viite.model.{NodeType, RoadPart, Track}
import fi.vaylavirasto.viite.postgis.GeometryDbUtils
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

case class Junction(id: Long, junctionNumber: Option[Long], nodeNumber: Option[Long], startDate: DateTime, endDate: Option[DateTime],
                    validFrom: DateTime, validTo: Option[DateTime], createdBy: String, createdTime: Option[DateTime],
                    junctionPoints: Option[List[JunctionPoint]] = None)

case class JunctionTemplate(id: Long, startDate: DateTime, roadPart: RoadPart, track: Track, addrM: Long, elyCode: Long, coords: Point = Point(0.0, 0.0))

case class JunctionForRoadAddressBrowser(nodeNumber: Long, nodeCoordinates: Point, nodeName: Option[String], nodeType: NodeType, startDate: DateTime, junctionNumber: Option[Long], roadPart: RoadPart, track: Long, addrM: Long, beforeAfter: Seq[Long])

case class JunctionWithCoordinateAndCrossingRoads(id: Long, junctionNumber: Option[Long], nodeNumber: Option[Long], startDate: DateTime, endDate: Option[DateTime],
                                                  validFrom: DateTime, validTo: Option[DateTime], createdBy: String, createdTime: Option[DateTime], xCoord: Double, yCoord: Double, crossingRoads: Seq[RoadwaysForJunction])

case class JunctionWithLinearLocation(id: Long, junctionNumber: Option[Long], nodeNumber: Option[Long], startDate: DateTime, endDate: Option[DateTime],
                                      validFrom: DateTime, validTo: Option[DateTime], createdBy: String, createdTime: Option[DateTime], llId: Seq[Long])

class JunctionDAO extends BaseDAO {
  private val j = Junction.syntax("j")
  private val rw = Roadway.syntax("rw")

  object Junction extends SQLSyntaxSupport[Junction] {
    override val tableName = "junction"
    override val columns = Seq(
      "id",
      "junction_number",
      "node_number",
      "start_date",
      "end_date",
      "valid_from",
      "valid_to",
      "created_by",
      "created_time"
    )

    def apply(rs: WrappedResultSet): Junction = new Junction(
      id = rs.long("id"),
      junctionNumber = rs.longOpt("junction_number"),
      nodeNumber = rs.longOpt("node_number"),
      startDate = rs.jodaDateTime("start_date"),
      endDate = rs.jodaDateTimeOpt("end_date"),
      validFrom = rs.jodaDateTime("valid_from"),
      validTo = rs.jodaDateTimeOpt("valid_to"),
      createdBy = rs.string("created_by"),
      createdTime = rs.jodaDateTimeOpt("created_time")
    )
  }

  object JunctionTemplate extends SQLSyntaxSupport[JunctionTemplate] {

    def apply(rs: WrappedResultSet): JunctionTemplate = new JunctionTemplate(
      id = rs.long("id"),
      startDate = rs.jodaDateTime("start_date"),
      roadPart = RoadPart(rs.long("road_number"), rs.long("road_part_number")),
      track = Track(rs.int("track")),
      addrM = rs.long("addr_m"),
      elyCode = rs.long("ely")
    )
  }

  object JunctionForRoadAddressBrowser extends SQLSyntaxSupport[JunctionForRoadAddressBrowser] {

    def apply(rs: WrappedResultSet): JunctionForRoadAddressBrowser = new JunctionForRoadAddressBrowser(
      nodeNumber = rs.long("node_number"),
      nodeCoordinates = Point(rs.long("xcoord"), rs.long("ycoord")),
      nodeName = rs.stringOpt("name"),
      nodeType = NodeType(rs.int("type")),
      startDate = rs.jodaDateTime("start_date"),
      junctionNumber = rs.longOpt("junction_number"),
      roadPart = RoadPart(rs.long("road_number"), rs.long("road_part_number")),
      track = rs.long("track"),
      addrM = rs.long("addr_m"),
      beforeAfter = parseBeforeAfterValue(rs.string("beforeafter"))
    )

    private def parseBeforeAfterValue(beforeAfterToParse: String): Seq[Long] = {
      beforeAfterToParse.replaceAll("[\\[\\]\\s]","").split(",").map(_.toLong).toSeq
    }
  }

  object JunctionWithLinearLocation extends SQLSyntaxSupport[JunctionWithLinearLocation] {

    def parseJsonbAgg(stringToParse: String): Seq[Long] = {
      val res = stringToParse.replaceAll("[\\[\\]\\s]", "")
      res.split(",").map(_.toLong).toSeq
    }

    def apply(rs: WrappedResultSet): JunctionWithLinearLocation = new JunctionWithLinearLocation(
      id = rs.long("id"),
      junctionNumber = rs.longOpt("junction_number"),
      nodeNumber = rs.longOpt("node_number"),
      startDate = rs.jodaDateTime("start_date"),
      endDate = rs.jodaDateTimeOpt("end_date"),
      validFrom = rs.jodaDateTime("valid_from"),
      validTo = rs.jodaDateTimeOpt("valid_to"),
      createdBy = rs.string("created_by"),
      createdTime = rs.jodaDateTimeOpt("created_time"),
      llId = parseJsonbAgg(rs.string("ll_id"))
    )
  }

  def fetchJunctionsByNodeNumbersWithLinearLocation(nodeNumbers: Seq[Long]): Seq[JunctionWithLinearLocation] = {
    if (nodeNumbers.isEmpty) {
      Seq.empty
    } else {
      val query = sql"""
        SELECT DISTINCT j.id, j.junction_number, j.node_number, j.start_date, j.end_date, j.valid_from, j.valid_to, j.created_by, j.created_time, jsonb_agg(DISTINCT ll.id) AS ll_id
              FROM junction J
              JOIN junction_point jp ON j.id = jp.junction_id AND jp.valid_to IS NULL
              JOIN calibration_point cp ON jp.roadway_point_id = cp.roadway_point_id AND cp.valid_to IS NULL
              JOIN linear_location ll ON cp.link_id = ll.link_id AND ll.valid_to IS NULL
              JOIN roadway_point rp ON cp.roadway_point_id = rp.id AND rp.roadway_number = ll.roadway_number
              JOIN roadway r ON ll.roadway_number = r.roadway_number AND r.valid_to IS NULL AND r.end_date IS NULL
      WHERE j.node_number in ($nodeNumbers) AND j.valid_to IS NULL AND j.end_date IS NULL
      GROUP BY j.id
      """
      runSelectQuery(query.map(JunctionWithLinearLocation.apply))
    }
  }


  // Helper method to run select queries for Junction objects
  private def queryList(query: SQL[Nothing, NoExtractor]): List[Junction] = {
    runSelectQuery(query.map(Junction.apply))
      .groupBy(_.id)
      .map { case (_, list) => list.head }
      .toList
  }

  // Helper method to run select queries for JunctionTemplate objects
  private def queryListTemplate(query: SQL[Nothing, NoExtractor]): List[JunctionTemplate] = {
    runSelectQuery(query.map(JunctionTemplate.apply))
      .groupBy(_.id)
      .map { case (_, list) =>
        list.minBy(jt => (jt.roadPart.roadNumber, jt.roadPart.partNumber, jt.addrM))}
      .toList
  }


  val selectAllFromJunctionQuery = sqls"""
    SELECT ${j.id}, ${j.junctionNumber}, ${j.nodeNumber}, ${j.startDate}, ${j.endDate},
           ${j.validFrom}, ${j.validTo}, ${j.createdBy}, ${j.createdTime}
    FROM ${Junction.as(j)}
  """

  def fetchJunctionByNodeNumber(nodeNumber: Long): Seq[Junction] = {
    fetchJunctionsByValidNodeNumbers(Seq(nodeNumber))
  }

  def fetchJunctionsByValidNodeNumbers(nodeNumbers: Seq[Long]): Seq[Junction] = {
    if (nodeNumbers.isEmpty) {
      Seq()
    } else {
      val query =
        sql"""
        $selectAllFromJunctionQuery
        WHERE ${j.nodeNumber} in ($nodeNumbers) AND ${j.validTo} IS NULL AND ${j.endDate} IS NULL
        """
      queryList(query)
    }
  }

  def fetchByIds(ids: Seq[Long]): Seq[Junction] = {
    if (ids.isEmpty)
      List()
    else {
      val query =
        sql"""
      $selectAllFromJunctionQuery
      WHERE ${j.id} IN (${ids}) AND ${j.validTo} IS NULL AND end_date IS NULL
      """
      queryList(query)
    }
  }

  def fetchAllByIds(ids: Seq[Long]): Seq[Junction] = {
    if (ids.isEmpty)
      List()
    else {
      val query =
        sql"""
      $selectAllFromJunctionQuery
      WHERE id IN (${ids})
      """
      queryList(query)
    }
  }

  def fetchJunctionByIdWithValidPoints(id: Long): Seq[Junction] = {
    val query =
      sql"""
      SELECT j.id, j.junction_number, j.node_number, j.start_date, j.end_date, j.valid_from, j.valid_to, j.created_by, j.created_time
      FROM junction j
      INNER JOIN junction_point jp ON j.id = jp.junction_id AND jp.valid_to IS NULL
      WHERE j.id = $id AND j.valid_to IS NULL AND j.end_date IS NULL
      """
    queryList(query)
  }

  def fetchTemplates() : Seq[JunctionTemplate] = {
    val query =
      sql"""
         SELECT DISTINCT j.id, j.start_date, rw.road_number, rw.road_part_number, rw.track, rp.addr_m, rw.ely
         FROM junction j
         JOIN junction_point jp ON j.id = jp.junction_id AND jp.valid_to IS NULL
         JOIN roadway_point rp ON jp.roadway_point_id = rp.id
         JOIN roadway rw ON rp.roadway_number = rw.roadway_number AND rw.valid_to IS NULL AND rw.end_date IS NULL
            WHERE j.valid_to IS NULL AND j.end_date IS NULL AND j.node_number IS NULL
       """
    queryListTemplate(query)
  }

  def fetchJunctionTemplateById(id: Long): Option[JunctionTemplate] = {
    val query =
      sql"""
         SELECT DISTINCT j.id, j.start_date, rw.road_number, rw.road_part_number, rw.track, rp.addr_m, rw.ely
         FROM junction j
         JOIN junction_point jp ON j.id = jp.junction_id AND jp.valid_to IS NULL
         JOIN roadway_point rp ON jp.roadway_point_id = rp.id
         JOIN roadway rw ON rp.roadway_number = rw.roadway_number AND rw.valid_to IS NULL AND rw.end_date IS NULL
            WHERE j.valid_to IS NULL AND j.end_date IS NULL AND j.node_number IS NULL
            AND j.id = $id
       """
    queryListTemplate(query).headOption
  }

  def fetchTemplatesByRoadwayNumbers(roadwayNumbers: Iterable[Long]) : Seq[JunctionTemplate] = {
    if (roadwayNumbers.nonEmpty) {
      val query =
        sql"""
         SELECT DISTINCT j.id, j.start_date, rw.road_number, rw.road_part_number, rw.track, rp.addr_m, rw.ely
         FROM junction j
         JOIN junction_point jp ON j.id = jp.junction_id AND jp.valid_to IS NULL
         JOIN roadway_point rp ON jp.roadway_point_id = rp.id
         JOIN roadway rw ON rp.roadway_number = rw.roadway_number AND rw.valid_to IS NULL AND rw.end_date IS NULL
         WHERE j.valid_to IS NULL AND j.end_date IS NULL AND j.node_number IS NULL
           AND rw.roadway_number IN ($roadwayNumbers)
       """
      queryListTemplate(query)
    } else {
      Seq.empty[JunctionTemplate]
    }
  }

  def fetchExpiredByRoadwayNumbers(roadwayNumbers: Iterable[Long]) : Seq[Junction] = {
    if (roadwayNumbers.nonEmpty) {
      val query =
        sql"""
         SELECT j.id, j.junction_number, j.node_number, j.start_date, j.end_date, j.valid_from, j.valid_to, j.created_by, j.created_time
         FROM junction j
         JOIN junction_point jp ON j.id = jp.junction_id
         JOIN roadway_point rp ON jp.roadway_point_id = rp.id
         JOIN roadway rw ON rp.roadway_number = rw.roadway_number
         WHERE rw.roadway_number IN (${roadwayNumbers})
        """
      queryList(query)
    } else {
      Seq.empty[Junction]
    }
  }

  def fetchByBoundingBox(boundingRectangle: BoundingRectangle): Seq[Junction] = {
    time(logger, "Fetch Junction templates by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = GeometryDbUtils.boundingBoxFilter(extendedBoundingRectangle, sqls"LL.geometry")

      val query =
        sql"""
         SELECT j.id, j.junction_number, j.node_number, j.start_date, j.end_date, j.valid_from, j.valid_to, j.created_by, j.created_time
         FROM junction j
         JOIN junction_point jp ON j.id = jp.junction_id AND jp.valid_to IS NULL
         JOIN roadway_point rp ON jp.roadway_point_id = rp.id
         JOIN linear_location LL ON (LL.roadway_number = RP.roadway_number AND LL.valid_to IS NULL)
         JOIN roadway rw ON rp.roadway_number = rw.roadway_number AND rw.valid_to IS NULL AND rw.end_date IS NULL
            WHERE j.valid_to IS NULL AND j.end_date IS NULL
            AND $boundingBoxFilter AND JP.valid_to IS NULL
            AND j.node_number IS NOT NULL
        """
      queryList(query)
    }
  }

  def fetchTemplatesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[JunctionTemplate] = {
    time(logger, "Fetch Junction templates by bounding box") {
      val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
        boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))

      val boundingBoxFilter = GeometryDbUtils.boundingBoxFilter(extendedBoundingRectangle, sqls"LL.geometry")

      val query =
        sql"""
         SELECT DISTINCT j.id, j.start_date, rw.road_number, rw.road_part_number, rw.track, rp.addr_m, rw.ely
         FROM junction j
         JOIN junction_point jp ON j.id = jp.junction_id AND jp.valid_to IS NULL
         JOIN roadway_point rp ON jp.roadway_point_id = rp.id
         JOIN linear_location LL ON (LL.roadway_number = RP.roadway_number AND LL.valid_to IS NULL)
         JOIN roadway rw ON rp.roadway_number = rw.roadway_number AND rw.valid_to IS NULL AND rw.end_date IS NULL
            WHERE j.valid_to IS NULL AND j.end_date IS NULL AND j.node_number IS NULL
            AND $boundingBoxFilter AND JP.valid_to IS NULL
        """
      queryListTemplate(query)
    }
  }

  def fetchJunctionsForRoadAddressBrowser(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long],
                                          minRoadPartNumber: Option[Long], maxRoadPartNumber: Option[Long]): Seq[JunctionForRoadAddressBrowser] = {
    val baseQuery =
      sqls"""
        SELECT node.node_number, ST_X(node.COORDINATES) AS xcoord, ST_Y(node.COORDINATES) AS ycoord, node.name, node.type,
        ${j.startDate}, ${j.junctionNumber}, ${rw.column("road_number")}, ${rw.track}, ${rw.column("road_part_number")}, rp.addr_m, json_agg(jp.before_after) as beforeafter
		    FROM junction j
       	JOIN node node ON node.node_number = ${j.nodeNumber} AND node.end_date IS NULL AND node.valid_to IS NULL
       	JOIN junction_point jp ON ${j.id} = jp.junction_id  AND jp.valid_to IS NULL
        JOIN roadway_point rp ON jp.roadway_point_id  = rp.id
        JOIN roadway rw ON rp.roadway_number = ${rw.roadwayNumber} AND ${rw.validTo} IS NULL AND ${rw.endDate} IS NULL
        WHERE ${j.validTo} IS NULL AND ${j.endDate} IS NULL
    """

    def withOptionalParameters(situationDate: Option[String], ely: Option[Long], roadNumber: Option[Long], minRoadPartNumber: Option[Long],
                               maxRoadPartNumber: Option[Long])(baseQuery: SQLSyntax): SQL[Nothing, NoExtractor] = {
      val dateCast = sqls"CAST(${situationDate.get} AS DATE)" // Scalike doesn't support directly casting to date TODO scalike: Better way to do this as a more reusable solution?
      val dateCondition = sqls"AND ${rw.startDate} <= $dateCast"
      val elyCondition = ely.map(e => sqls"AND ${rw.ely} = $e").getOrElse(sqls"")
      val roadNumberCondition = roadNumber.map(rn => sqls"AND ${rw.column("road_number")} = $rn").getOrElse(sqls"")

      val roadPartCondition = (minRoadPartNumber, maxRoadPartNumber) match {
        case (Some(minPart), Some(maxPart)) => sqls"AND ${rw.column("road_part_number")} BETWEEN $minPart AND $maxPart"
        case (None, Some(maxPart)) => sqls"AND ${rw.column("road_part_number")} <= $maxPart"
        case (Some(minPart), None) => sqls"AND ${rw.column("road_part_number")} >= $minPart"
        case _ => sqls""
      }

      sql"""
        $baseQuery $dateCondition $elyCondition $roadNumberCondition $roadPartCondition
        GROUP BY node.node_number, xcoord, ycoord, node.name, node.TYPE, ${j.startDate}, ${j.junctionNumber}, ${rw.column("road_number")}, ${rw.track}, ${rw.column("road_part_number")}, rp.addr_m
        ORDER BY ${rw.column("road_number")}, ${rw.column("road_part_number")}, rp.addr_m
        """
    }

    val fullQuery = withOptionalParameters(situationDate, ely, roadNumber, minRoadPartNumber, maxRoadPartNumber)(baseQuery)
    runSelectQuery(fullQuery.map(JunctionForRoadAddressBrowser.apply))

  }

  def create(junctions: Iterable[Junction]): Seq[Long] = {

    // Set ids for the junctions without one
    val (ready, idLess) = junctions.partition(_.id != NewIdValue)
    val newIds = Sequences.fetchJunctionIds(idLess.size)
    val createJunctions = ready ++ idLess.zip(newIds).map(x =>
      x._1.copy(id = x._2)
    )

    val batchParams: Iterable[Seq[Any]] = createJunctions.map { junction =>
      Seq(
        junction.id,
        junction.junctionNumber,
        junction.nodeNumber,
        new java.sql.Timestamp(junction.startDate.getMillis),
        junction.endDate.map(d => new java.sql.Timestamp(d.getMillis)).orNull,
        junction.createdBy
      )
    }

    val query =
      sql"""
        insert into junction (id, junction_number, node_number, start_date, end_date, created_by)
        values (?, ?, ?, ?, ?, ?)
      """

    runBatchUpdateToDb(query, batchParams.toSeq)

    // Return the ids of the created junctions
    createJunctions.map(_.id).toSeq
  }

  /**
   * Expires junctions (set their valid_to to the current system date).
   *
   * @param ids : Iterable[Long] - The ids of the junctions to expire.
   * @return
   */
  def expireById(ids: Iterable[Long]): Unit = {
    if (ids.isEmpty) 0
    else {
      val query =
        sql"""
            UPDATE junction
            SET valid_to = CURRENT_TIMESTAMP
            WHERE valid_to IS NULL AND id IN ($ids)
            """
      runUpdateToDb(query)
    }
  }

}
