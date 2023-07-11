package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.viite.dao._
import fi.vaylavirasto.viite.dao.MunicipalityDAO
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter, ISODateTimeFormat}

trait AddressLinkBuilder {
  val RoadNumber = "roadnumber"
  val RoadPartNumber = "roadpartnumber"
  val MunicipalityCode = "municipalitycode"
  val FinnishRoadName = "roadnamefin"
  val SwedishRoadName = "roadnameswe"
  val ComplementarySubType = 3
  val formatter: DateTimeFormatter = DateTimeFormat.forPattern("dd.MM.yyyy")
  val dateTimeFormatter: DateTimeFormatter = ISODateTimeFormat.dateOptionalTimeParser()

  lazy val municipalityRoadMaintainerMapping: Map[Long, Long] = if (PostGISDatabase.isWithinSession)
    MunicipalityDAO.getMunicipalityRoadMaintainers
  else
    PostGISDatabase.withDynSession {
      MunicipalityDAO.getMunicipalityRoadMaintainers
    }

  lazy val municipalityNamesMapping: Map[Long, String] = if (PostGISDatabase.isWithinSession)
    MunicipalityDAO.getMunicipalityNames
  else
    PostGISDatabase.withDynSession {
      MunicipalityDAO.getMunicipalityNames
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

  // TODO: Duplicate of ComplementaryLinkDAO
  def extractModifiedAt(attributes: Map[String, Any]): Option[String] = {
    def toLong(anyValue: Option[Any]): Option[Long] = {
      anyValue.map(v => dateTimeFormatter.parseDateTime(v.toString).getMillis)
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

    val starttime = toLong(attributes.get("starttime"))
    val versionstarttime = toLong(attributes.get("versionstarttime"))
    val versionendtime = toLong(attributes.get("versionendtime"))
    compareDateMillisOptions(versionendtime, compareDateMillisOptions(versionstarttime,starttime)).map(modifiedTime => new DateTime(modifiedTime).toString)
  }
}
