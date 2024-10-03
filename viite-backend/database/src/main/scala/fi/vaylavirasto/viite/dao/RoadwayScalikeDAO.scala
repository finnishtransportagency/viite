package fi.vaylavirasto.viite.dao

import fi.vaylavirasto.viite.model.{AddrMRange, AdministrativeClass, Discontinuity, RoadPart, RoadwayNew, TerminationCode, Track}
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

object RoadwayScalike extends SQLSyntaxSupport[RoadwayNew] {
  override val tableName = "ROADWAY"

  def apply(rs: WrappedResultSet): RoadwayNew =  RoadwayNew(
    id = rs.long("id"),
    roadwayNumber = rs.long("roadway_number"),
    roadPart = RoadPart(rs.long("road_number"), rs.long("road_part_number")),
    administrativeClass = AdministrativeClass(rs.int("administrative_class")),
    track = Track(rs.int("track")),
    discontinuity = Discontinuity(rs.int("discontinuity")),
    addrMRange = AddrMRange(rs.long("start_addr_m"), rs.long("end_addr_m")),
    reversed = rs.boolean("reversed"),
    startDate = rs.jodaDateTime("start_date"),
    endDate = rs.jodaDateTimeOpt("end_date"),
    createdBy = rs.string("created_by"),
    roadName = rs.stringOpt("road_name"),
    ely = rs.long("ely"),
    terminated = TerminationCode(rs.int("terminated")),
    validFrom = rs.jodaDateTime("valid_from"),
    validTo = rs.jodaDateTimeOpt("valid_to")
  )
}


// dao for RoadwayScalike
object RoadwayScalikeDAO extends ScalikeJDBCBaseDAO {
  private val rw = RoadwayScalike.syntax("rw")
  // TODO HOW TO IMPORT THIS
  val NewIdValue: Long = -1000L

  def create(roadways: Iterable[RoadwayNew]): Seq[Long] = {
    val column = RoadwayScalike.column
    val (ready, idLess) = roadways.partition(_.id != NewIdValue)
    val newIds = SequencesScalikeJDBC.fetchRoadwayIds(idLess.size)
    val createRoadways = ready ++ idLess.zip(newIds).map { case (rw, newId) =>
      rw.copy(id = newId)
    }

    // Assign roadwayNumber if needed
    val roadwaysWithNumbers = createRoadways.map { roadway =>
      val roadwayNumber = if (roadway.roadwayNumber == NewIdValue) {
        SequencesScalikeJDBC.nextRoadwayNumber
      } else {
        roadway.roadwayNumber
      }
      roadway.copy(roadwayNumber = roadwayNumber)
    }

    // Prepare batch parameters
    val batchParams: Seq[Seq[Any]] = roadwaysWithNumbers.map { roadway =>
      Seq(
        roadway.id,
        roadway.roadwayNumber,
        roadway.roadPart.roadNumber,
        roadway.roadPart.partNumber,
        roadway.track.value,
        roadway.addrMRange.start,
        roadway.addrMRange.end,
        if (roadway.reversed) 1 else 0,
        roadway.discontinuity.value,
        new java.sql.Date(roadway.startDate.getMillis),
        roadway.endDate.map(date => new java.sql.Date(date.getMillis)).orNull,
        roadway.createdBy,
        roadway.administrativeClass.value,
        roadway.ely,
        roadway.terminated.value
      )
    }.toSeq

    // Construct the SQL insert query
    val insertQuery = sql"""
      INSERT INTO ROADWAY (
        ${column.id},
        ${column.roadwayNumber},
        road_number,
        road_part_number,
        ${column.track},
        start_addr_m,
        end_addr_m,
        ${column.reversed},
        ${column.discontinuity},
        ${column.startDate},
        ${column.endDate},
        ${column.createdBy},
        ${column.administrativeClass},
        ${column.ely},
        ${column.terminated}
      ) VALUES (
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
      )
    """

    // Execute the batch update
    runBatchUpdateToDbScalike(insertQuery, batchParams)

    // Return the list of IDs
    roadwaysWithNumbers.map(_.id).toSeq
  }


}


