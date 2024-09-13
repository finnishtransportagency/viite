package fi.vaylavirasto.viite.model

import org.joda.time.DateTime

case class Roadway(
                    id: Long,
                    roadwayNumber: Long,
                    roadPart: RoadPart,
                    administrativeClass: AdministrativeClass,
                    track: Track,
                    discontinuity: Discontinuity,
                    addrMRange: AddrMRange,
                    reversed: Boolean = false,
                    startDate: DateTime,
                    endDate: Option[DateTime] = None,
                    createdBy: String,
                    roadName: Option[String],
                    ely: Long,
                    terminated: TerminationCode = TerminationCode.NoTermination,
                    validFrom: DateTime = DateTime.now(),
                    validTo: Option[DateTime] = None
                         )

case class RoadwaysForJunction(jId: Long, roadwayNumber: Long, roadPart: RoadPart, track: Long, addrM: Long, beforeAfter: Long)

sealed trait TerminationCode {
  def value: Int
}

object TerminationCode {
  val values: Set[TerminationCode] = Set(NoTermination, Termination, Subsequent)

  def apply(intValue: Int): TerminationCode = {
    values.find(_.value == intValue).getOrElse(NoTermination)
  }

  case object NoTermination extends TerminationCode {
    def value = 0
  }

  case object Termination extends TerminationCode {
    def value = 1
  }

  case object Subsequent extends TerminationCode {
    def value = 2
  }

  //TODO: Add the rest of the related classes here and remove them from the RoadwayDAO
}

