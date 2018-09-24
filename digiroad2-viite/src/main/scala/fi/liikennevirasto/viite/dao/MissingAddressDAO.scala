package fi.liikennevirasto.viite.dao

import fi.liikennevirasto.digiroad2.asset.BoundingRectangle
import fi.liikennevirasto.digiroad2.oracle.{MassQuery, OracleDatabase}
import fi.liikennevirasto.digiroad2.{DigiroadEventBus, Point}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.viite.RoadType
import fi.liikennevirasto.viite.model.Anomaly
import slick.jdbc.{GetResult, PositionedResult, StaticQuery => Q}
import slick.jdbc.StaticQuery.interpolation
import slick.driver.JdbcDriver.backend.Database.dynamicSession

case class MissingRoadAddress(linkId: Long, startAddrMValue: Option[Long], endAddrMValue: Option[Long],
                              roadType: RoadType, roadNumber: Option[Long], roadPartNumber: Option[Long],
                              startMValue: Option[Double], endMValue: Option[Double], anomaly: Anomaly, geom: Seq[Point])

class MissingAddressDAO extends BaseDAO {

  implicit val getMissingRoadAddress: GetResult[MissingRoadAddress] = new GetResult[MissingRoadAddress]{
    def apply(r: PositionedResult) = {

      val linkId = r.nextLong()
      val startAddrM = r.nextLongOption()
      val endAddrM = r.nextLongOption()
      val roadType = RoadType.apply(r.nextInt())
      val roadNumber = r.nextLongOption()
      val roadPartNumber = r.nextLongOption()
      val startMValue = r.nextDoubleOption()
      val endMValue = r.nextDoubleOption()
      val anomaly = r.nextInt()
      val x = r.nextDouble()
      val y = r.nextDouble()
      val x2 = r.nextDouble()
      val y2 = r.nextDouble()

      MissingRoadAddress(linkId, startAddrM, endAddrM, RoadType.UnknownOwnerRoad, roadNumber, roadPartNumber, startMValue, endMValue, Anomaly.apply(anomaly), Seq(Point(x, y), Point(x2, y2)))
    }
  }

  def fetchMissingRoadAddressesByBoundingBox(boundingRectangle: BoundingRectangle): Seq[MissingRoadAddress] = {
    val extendedBoundingRectangle = BoundingRectangle(boundingRectangle.leftBottom + boundingRectangle.diagonal.scale(.15),
      boundingRectangle.rightTop - boundingRectangle.diagonal.scale(.15))
    val filter = OracleDatabase.boundingBoxFilter(extendedBoundingRectangle, "geometry")

    val query =
      s"""
            select link_id, start_addr_m, end_addr_m, road_number, road_part_number, anomaly_code, start_m, end_m,
            (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as X,
            (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as Y,
            (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as X2,
            (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as Y2
            from missing_road_address
            where $filter
          """

    Q.queryNA[(Long, Option[Long], Option[Long], Option[Long], Option[Long], Int, Option[Double], Option[Double], Double, Double, Double, Double)](query).list.map {
      case (linkId, startAddrM, endAddrM, road, roadPart, anomaly, startM, endM, x, y, x2, y2) =>
        MissingRoadAddress(linkId, startAddrM, endAddrM, RoadType.UnknownOwnerRoad, road, roadPart, startM, endM, Anomaly.apply(anomaly), Seq(Point(x, y), Point(x2, y2)))
    }
  }

    def createMissingRoadAddress (mra: MissingRoadAddress): Unit = {
      val (p1, p2) = (mra.geom.head, mra.geom.last)

      sqlu"""
             insert into missing_road_address
             (select ${mra.linkId}, ${mra.startAddrMValue}, ${mra.endAddrMValue},
               ${mra.roadNumber}, ${mra.roadPartNumber}, ${mra.anomaly.value},
               ${mra.startMValue}, ${mra.endMValue},
               MDSYS.SDO_GEOMETRY(4002, 3067, NULL, MDSYS.SDO_ELEM_INFO_ARRAY(1,2,1),
               MDSYS.SDO_ORDINATE_ARRAY(${p1.x},${p1.y},0.0,0.0,${p2.x},${p2.y},0.0,0.0))
                FROM dual WHERE NOT EXISTS (SELECT * FROM MISSING_ROAD_ADDRESS WHERE link_id = ${mra.linkId}) AND
                NOT EXISTS (SELECT * FROM ROADWAY ra
                  WHERE link_id = ${mra.linkId} AND valid_to IS NULL ))
             """.execute
    }

    def createMissingRoadAddress (linkId: Long, start_addr_m: Long, end_addr_m: Long, anomaly_code: Int): Unit = {
      sqlu"""
             insert into missing_road_address (link_id, start_addr_m, end_addr_m,anomaly_code)
             values ($linkId, $start_addr_m, $end_addr_m, $anomaly_code)
             """.execute
    }

    def createMissingRoadAddress (linkId: Long, start_addr_m: Long, end_addr_m: Long, anomaly_code: Int, start_m : Double, end_m : Double): Unit = {
      sqlu"""
             insert into missing_road_address (link_id, start_addr_m, end_addr_m,anomaly_code, start_m, end_m)
             values ($linkId, $start_addr_m, $end_addr_m, $anomaly_code, $start_m, $end_m)
             """.execute
    }

    def expireMissingRoadAddresses (targetLinkIds: Set[Long]): AnyVal = {

      if (targetLinkIds.nonEmpty) {
        val query =
          s"""
            Delete from missing_road_address Where link_id in (${targetLinkIds.mkString(",")})
          """
        Q.updateNA(query).first
      }
    }

    def getMissingRoadAddresses(linkIds: Set[Long]): List[MissingRoadAddress] = {
      if (linkIds.size > 500) {
        getMissingByLinkIdMassQuery(linkIds)
      } else {
        val where = if (linkIds.isEmpty)
          return List()
        else
          s""" where link_id in (${linkIds.mkString(",")})"""

        val query =
          s"""SELECT link_id, start_addr_m, end_addr_m, road_number, road_part_number, start_m, end_m, anomaly_code,
             (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as X,
             (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as Y,
             (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as X2,
             (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as Y2
              FROM missing_road_address $where"""
        Q.queryNA[(Long, Option[Long], Option[Long], Option[Long], Option[Long], Option[Double], Option[Double], Int, Double, Double, Double, Double)](query).list.map {
          case (linkId, startAddrM, endAddrM, road, roadPart, startM, endM, anomaly, x1, y1, x2, y2) =>
            MissingRoadAddress(linkId, startAddrM, endAddrM, RoadType.UnknownOwnerRoad ,road, roadPart, startM, endM, Anomaly.apply(anomaly), Seq(Point(x1, y1),Point(x2, y2)))
        }
      }
    }

    def getMissingByLinkIdMassQuery(linkIds: Set[Long]): List[MissingRoadAddress] = {
      MassQuery.withIds(linkIds) {
        idTableName =>
          val query =
            s"""SELECT link_id, start_addr_m, end_addr_m, road_number, road_part_number, start_m, end_m, anomaly_code,
               (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as X,
               (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 1) as Y,
               (SELECT X FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as X2,
               (SELECT Y FROM TABLE(SDO_UTIL.GETVERTICES(geometry)) t WHERE id = 2) as Y2
              FROM missing_road_address mra join $idTableName i on i.id = mra.link_id"""
          Q.queryNA[(Long, Option[Long], Option[Long], Option[Long], Option[Long], Option[Double], Option[Double], Int, Double, Double, Double, Double)](query).list.map {
            case (linkId, startAddrM, endAddrM, road, roadPart, startM, endM, anomaly, x1, y1, x2, y2) =>
              MissingRoadAddress(linkId, startAddrM, endAddrM, RoadType.UnknownOwnerRoad, road, roadPart, startM, endM, Anomaly.apply(anomaly), Seq(Point(x1, y1),Point(x2, y2)))
          }
      }
    }

}
