package fi.vaylavirasto.viite.dao

import fi.vaylavirasto.viite.model.{AdministrativeClass, AddrMRange, Discontinuity, RoadPart, Roadway, TerminationCode, Track}
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

object RoadwayScalike extends SQLSyntaxSupport[Roadway] {
  override val tableName = "ROADWAY"

  def apply(rs: WrappedResultSet): Roadway = new Roadway(
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
}

