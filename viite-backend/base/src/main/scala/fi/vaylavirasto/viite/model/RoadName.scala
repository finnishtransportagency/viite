package fi.vaylavirasto.viite.model

import org.joda.time.DateTime

case class RoadName(id: Long, roadNumber: Long, roadName: String, startDate: Option[DateTime], endDate: Option[DateTime] = None,
                    validFrom: Option[DateTime] = None, validTo: Option[DateTime] = None, createdBy: String)

case class RoadNameForRoadAddressBrowser(ely: Long, roadNumber: Long, roadName: String)
