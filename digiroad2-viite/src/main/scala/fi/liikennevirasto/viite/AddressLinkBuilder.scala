package fi.liikennevirasto.viite
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.{FeatureClass, VVHRoadlink}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.RoadType._
import fi.liikennevirasto.viite.dao._
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}

trait AddressLinkBuilder {
  val RoadNumber = "ROADNUMBER"
  val RoadPartNumber = "ROADPARTNUMBER"
  val TrackCode = "TRACKCODE"
  val MunicipalityCode = "MUNICIPALITYCODE"
  val FinnishRoadName = "ROADNAME_FI"
  val SwedishRoadName = "ROADNAME_SE"
  val ComplementarySubType = 3
  val formatter: DateTimeFormatter = DateTimeFormat.forPattern("dd.MM.yyyy")

  lazy val municipalityMapping: Map[Long, Long] = if (OracleDatabase.isWithinSession)
    MunicipalityDAO.getMunicipalityMapping
  else
    OracleDatabase.withDynSession {
      MunicipalityDAO.getMunicipalityMapping
    }

  lazy val municipalityRoadMaintainerMapping: Map[Long, Long] = if (OracleDatabase.isWithinSession)
      MunicipalityDAO.getMunicipalityRoadMaintainers
    else
      OracleDatabase.withDynSession {
        MunicipalityDAO.getMunicipalityRoadMaintainers
      }

  def getRoadType(administrativeClass: AdministrativeClass, linkType: LinkType): RoadType = {
    (administrativeClass, linkType) match {
      case (State, CableFerry) => FerryRoad
      case (State, _) => PublicRoad
      case (Municipality, _) => MunicipalityStreetRoad
      case (Private, _) => PrivateRoadType
      case (_, _) => UnknownOwnerRoad
    }
  }

  def getLinkType(roadLink: VVHRoadlink): LinkType ={  //similar logic used in roadLinkService
    roadLink.featureClass match {
      case FeatureClass.TractorRoad => TractorRoad
      case FeatureClass.DrivePath => SingleCarriageway
      case FeatureClass.CycleOrPedestrianPath => CycleOrPedestrianPath
      case _=> UnknownLinkType
    }
  }

  protected def toIntNumber(value: Any): Int = {
    try {
      value.asInstanceOf[String].toInt
    } catch {
      case e: Throwable => 0
    }
  }

  protected def toLongNumber(value: Any): Long = {
    try {
      value match {
        case b: BigInt => b.longValue()
        case _ => value.asInstanceOf[String].toLong
      }

    } catch {
      case e: Exception => 0L
    }
  }

  protected def toLongNumber(longOpt: Option[Long], valueOpt: Option[Any]): Long = {
    longOpt match {
      case Some(l) if l > 0 => l
      case _ => valueOpt.map(toLongNumber).getOrElse(0L)
    }
  }

  protected def extractModifiedAtVVH(attributes: Map[String, Any]): Option[String] = {
    def toLong(anyValue: Option[Any]) = {
      anyValue.map(_.asInstanceOf[BigInt].toLong)
    }
    def compareDateMillisOptions(a: Option[Long], b: Option[Long]): Option[Long] = {
      (a, b) match {
        case (Some(firstModifiedAt), Some(secondModifiedAt)) =>
          if (firstModifiedAt > secondModifiedAt)
            Some(firstModifiedAt)
          else
            Some(secondModifiedAt)
        case (Some(firstModifiedAt), None) => Some(firstModifiedAt)
        case (None, Some(secondModifiedAt)) => Some(secondModifiedAt)
        case (None, None) => None
      }
    }
    val toIso8601 = DateTimeFormat.forPattern("dd.MM.yyyy HH:mm:ss")
    val createdDate = toLong(attributes.get("CREATED_DATE"))
    val lastEditedDate = toLong(attributes.get("LAST_EDITED_DATE"))
    val geometryEditedDate = toLong(attributes.get("GEOMETRY_EDITED_DATE"))
    val endDate = toLong(attributes.get("END_DATE"))
    val latestDate = compareDateMillisOptions(lastEditedDate, geometryEditedDate)
    val withHistoryLatestDate = compareDateMillisOptions(latestDate, endDate)
    val timezone = DateTimeZone.forOffsetHours(0)
    val latestDateString = withHistoryLatestDate.orElse(createdDate).map(modifiedTime => new DateTime(modifiedTime, timezone)).map(toIso8601.print(_))
    latestDateString
  }
}
