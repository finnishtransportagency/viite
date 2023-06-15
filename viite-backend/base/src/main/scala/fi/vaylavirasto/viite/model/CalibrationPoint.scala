package fi.vaylavirasto.viite.model

import org.joda.time.DateTime

case class CalibrationPoint(id: Long,
                            roadwayPointId: Long,
                            linkId: String,
                            roadwayNumber: Long,
                            addrM: Long,
                            startOrEnd: CalibrationPointLocation,
                            typeCode: CalibrationPointType,
                            validFrom: Option[DateTime] = None,
                            validTo: Option[DateTime] = None,
                            createdBy: String,
                            createdTime: Option[DateTime] = None
                           ) {
  def this(id: Long, roadwayPointId: Long, linkId: Long,
           roadwayNumber: Long, addrM: Long, startOrEnd: CalibrationPointLocation,
           typeCode: CalibrationPointType, validFrom: Option[DateTime], validTo: Option[DateTime],
           createdBy: String, createdTime: Option[DateTime]
          ) =
    this(id, roadwayPointId, linkId.toString,
      roadwayNumber, addrM, startOrEnd,
      typeCode, validFrom, validTo,
      createdBy, createdTime
    )

}
