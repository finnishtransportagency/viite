package fi.liikennevirasto.digiroad2.client.kgv

import fi.liikennevirasto.digiroad2.asset._
import org.joda.time.DateTime

trait Filter {
  def withFilter[T](attributeName: String, ids: Set[T]): String

  def withMunicipalityFilter(municipalities: Set[Int]): String

  def withRoadNumbersFilter(roadNumbers:           Seq[(Int, Int)],
                            includeAllPublicRoads: Boolean,
                            filter:                String = ""
                           ): String

  def combineFiltersWithAnd(filter1: String, filter2: String): String

  def combineFiltersWithAnd(filter1: String, filter2: Option[String]): String

  // Query filters methods
  def withLinkIdFilter[T](linkIds: Set[T]): String

  def withSourceIdFilter(sourceIds: Set[Long]): String

  def withMtkClassFilter(ids: Set[Long]): String

  def withLastEditedDateFilter(lowerDate: DateTime, higherDate: DateTime): String

  def withDateLimitFilter(attributeName: String, lowerDate: DateTime, higherDate: DateTime): String

}

object FilterOgc extends Filter {

  val singleFilter:       (String, String) => String = (field: String, value: String) => s"${field}='${value}'"
  val singleAddQuotation: String => String           = (value: String) => s"'${value}'"

  override def withSourceIdFilter(sourceIds: Set[Long]): String =
    withFilter("sourceid", sourceIds)

  override def withFilter[T](attributeName: String, ids: Set[T]): String =
    if (ids.nonEmpty)
      s"${attributeName.toLowerCase} IN (${ids.map(t => singleAddQuotation(t.toString)).mkString(",")})"
    else ""

  override def withRoadNumbersFilter(roadNumbers:           Seq[(Int, Int)],
                                     includeAllPublicRoads: Boolean,
                                     filter:                String = ""
                                    ): String = {
    if (roadNumbers.isEmpty)
      return s"""$filter"""
    if (includeAllPublicRoads)
      return withRoadNumbersFilter(roadNumbers, includeAllPublicRoads = false, "adminclass = 1")
    val limit     = roadNumbers.head
    val filterAdd = s"""(roadnumber >= ${limit._1} and roadnumber <= ${limit._2})"""
    if ("".equals(filter))
      withRoadNumbersFilter(roadNumbers.tail, includeAllPublicRoads, filterAdd)
    else
      withRoadNumbersFilter(roadNumbers.tail, includeAllPublicRoads, s"""$filter OR $filterAdd""")
  }

  override def withMunicipalityFilter(municipalities: Set[Int]): String =
    if (municipalities.size == 1) singleFilter("municipalitycode", municipalities.head.toString)
    else withFilter("municipalitycode", municipalities)

  override def combineFiltersWithAnd(filter1: String, filter2: Option[String]): String = {
    val lifeCycleStatusFilter =
      s"lifecyclestatus IN (${LifecycleStatus.filteredLinkStatus.map(_.value).mkString(",")})"
    combineFiltersWithAnd(lifeCycleStatusFilter,
                          combineFiltersWithAnd(filter2.getOrElse(""), filter1)
                         )
  }

  override def combineFiltersWithAnd(filter1: String, filter2: String): String =
    (filter1.isEmpty, filter2.isEmpty) match {
      case (true, true)   => ""
      case (true, false)  => filter2
      case (false, true)  => filter1
      case (false, false) => s"$filter1 AND $filter2"
    }

  // Query filters methods
  override def withLinkIdFilter[T](linkIds: Set[T]): String =
    if (linkIds.nonEmpty) withFilter("id", linkIds)
    else ""

  override def withMtkClassFilter(ids: Set[Long]): String =
    if (ids.nonEmpty) withFilter("roadclass", ids)
    else ""

  def withLifecycleStatusFilter(values: Set[Int]): String =
    if (values.nonEmpty) withFilter("lifecyclestatus", values)
    else ""

  override def withLastEditedDateFilter(lowerDate: DateTime, higherDate: DateTime): String =
    withDateLimitFilter("versionstarttime", lowerDate, higherDate)

  override def withDateLimitFilter(attributeName: String,
                                   lowerDate:     DateTime,
                                   higherDate:    DateTime
                                  ): String =
    s"$attributeName >= $lowerDate and $attributeName <= $higherDate"
}
