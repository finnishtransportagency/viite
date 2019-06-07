package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.model.Anomaly

case class UnaddressedRoadLink(linkId: Long, startAddrMValue: Option[Long], endAddrMValue: Option[Long],
                               roadType: RoadType, roadNumber: Option[Long], roadPartNumber: Option[Long],
                               startMValue: Option[Double], endMValue: Option[Double], anomaly: Anomaly, geom: Seq[Point])
