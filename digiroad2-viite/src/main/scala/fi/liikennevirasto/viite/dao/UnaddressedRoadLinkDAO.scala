package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.Point
import fi.liikennevirasto.digiroad2.asset.AdministrativeClass
import fi.liikennevirasto.viite.model.Anomaly

case class UnaddressedRoadLink(linkId: String, startAddrMValue: Option[Long], endAddrMValue: Option[Long], administrativeClass: AdministrativeClass, roadNumber: Option[Long], roadPartNumber: Option[Long], startMValue: Option[Double], endMValue: Option[Double], anomaly: Anomaly, geom: Seq[Point])
