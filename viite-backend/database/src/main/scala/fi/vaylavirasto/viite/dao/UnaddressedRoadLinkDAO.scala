package fi.vaylavirasto.viite.dao

import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.AdministrativeClass

case class UnaddressedRoadLink(linkId: String, startAddrMValue: Option[Long], endAddrMValue: Option[Long], administrativeClass: AdministrativeClass, roadNumber: Option[Long], roadPartNumber: Option[Long], startMValue: Option[Double], endMValue: Option[Double], geom: Seq[Point])
