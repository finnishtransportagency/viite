package fi.liikennevirasto.viite.util

import java.sql.PreparedStatement
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import fi.liikennevirasto.viite.dao.RoadwayPointDAO
import fi.vaylavirasto.viite.dao.{SequenceResetterDAO, Sequences}
import fi.vaylavirasto.viite.geometry.Point
import org.joda.time._
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._


class NodeImporter(conversionDatabase: DatabaseDef) {
  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  val roadwayPointDAO = new RoadwayPointDAO

  case class ConversionNode(id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: Long, startDate: Option[DateTime], endDate: Option[DateTime], validFrom: Option[DateTime],
                            validTo: Option[DateTime], createdBy: String, registrationDate: Option[DateTime])

  case class ConversionNodePoint(id: Long, beforeOrAfter: Long, nodeId: Long, nodeNumber: Long, roadwayNumberTR: Long, addressMValueTR: Long,
                                 validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: String, createdTime: Option[DateTime])

  private def insertNodeStatement(): PreparedStatement =
    dynamicSession.prepareStatement(sql = "INSERT INTO NODE (ID, NODE_NUMBER, COORDINATES, NAME, TYPE, START_DATE, END_DATE, VALID_FROM, CREATED_BY, REGISTRATION_DATE) VALUES " +
      " (?, ?, ST_GeomFromText('POINT('||?||' '||?||' 0.0)', 3067), ?, ?, ?, ?, ?, ?, ?)")

  private def insertNodePointStatement(): PreparedStatement =
    dynamicSession.prepareStatement(sql = "INSERT INTO NODE_POINT (ID, BEFORE_AFTER, ROADWAY_POINT_ID, NODE_NUMBER, VALID_FROM, VALID_TO, CREATED_BY) VALUES " +
      " (?, ?, ?, ?, ?, ?, ?) ")


  def insertNode(nodeStatement: PreparedStatement, conversionNode: ConversionNode): Unit = {
    nodeStatement.setLong(1, conversionNode.id)
    nodeStatement.setLong(2, conversionNode.nodeNumber)
    nodeStatement.setDouble(3, conversionNode.coordinates.x)
    nodeStatement.setDouble(4, conversionNode.coordinates.y)
    nodeStatement.setString(5, conversionNode.name.getOrElse(""))
    nodeStatement.setLong(6, conversionNode.nodeType)
    if (conversionNode.startDate.isDefined) {
      nodeStatement.setDate(7, new java.sql.Date(conversionNode.startDate.get.getMillis))
    } else {
      nodeStatement.setNull(7, java.sql.Types.DATE)
    }
    if (conversionNode.endDate.isDefined) {
      nodeStatement.setDate(8, new java.sql.Date(conversionNode.endDate.get.getMillis))
    } else {
      nodeStatement.setNull(8, java.sql.Types.DATE)
    }
    if (conversionNode.validFrom.isDefined) {
      nodeStatement.setTimestamp(9, new java.sql.Timestamp(conversionNode.validFrom.get.getMillis))
    } else {
      nodeStatement.setNull(9, java.sql.Types.TIMESTAMP)
    }
    nodeStatement.setString(10, conversionNode.createdBy)
    if (conversionNode.registrationDate.nonEmpty) {
      nodeStatement.setTimestamp(11, new java.sql.Timestamp(conversionNode.registrationDate.get.getMillis))
    } else {
      throw new RuntimeException(s"Registration date must be set for node number: ${conversionNode.nodeNumber}")
    }
    nodeStatement.addBatch()
  }

  def insertNodePoint(nodePointStatement: PreparedStatement, nodePoint: ConversionNodePoint, nodeNumber: Long, nodeEndDate: Option[DateTime], roadwayPointId: Long): Unit = {
    nodePointStatement.setLong(1, Sequences.nextNodePointId)
    nodePointStatement.setLong(2, nodePoint.beforeOrAfter)
    nodePointStatement.setLong(3, roadwayPointId)
    nodePointStatement.setLong(4, nodeNumber)
    if (nodePoint.validFrom.isDefined) {
      nodePointStatement.setTimestamp(5, new java.sql.Timestamp(nodePoint.validFrom.get.getMillis))
    } else {
      nodePointStatement.setNull(5, java.sql.Types.TIMESTAMP)
    }
    if (nodeEndDate.isDefined) {
      nodePointStatement.setTimestamp(6, new java.sql.Timestamp(nodeEndDate.get.getMillis))
    } else {
      nodePointStatement.setNull(6, java.sql.Types.TIMESTAMP)
    }
    nodePointStatement.setString(7, nodePoint.createdBy)
    nodePointStatement.addBatch()
  }

  def importNodes(): Unit = {
    println("\nFetching all nodes from conversion database")
    val conversionNodes = fetchNodesFromConversionTable()
    val conversionNodePoints = fetchNodePointsFromConversionTable()
    val nodePs = insertNodeStatement()
    val nodePointPs = insertNodePointStatement()
    val nodesWithPoints = conversionNodes.map(
      conversionNode => (conversionNode, conversionNodePoints.filter(_.nodeId == conversionNode.id))
    )

    nodesWithPoints.foreach {
      conversionNode =>
        println(s"Inserting node with TR id = ${conversionNode._1.id} and node_number = ${conversionNode._1.nodeNumber}")
        val newNodeId = Sequences.nextNodeId
        insertNode(nodePs, conversionNode._1.copy(id = newNodeId))
        conversionNode._2.foreach {
          conversionNodePoint => {
            val existingRoadwayPoint = roadwayPointDAO.fetch(conversionNodePoint.roadwayNumberTR, conversionNodePoint.addressMValueTR)
            println(s"Inserting node point with TR id = ${conversionNodePoint.id} and node_id = ${conversionNodePoint.nodeId} for node_number = ${conversionNode._1.nodeNumber}")
            if (existingRoadwayPoint.isEmpty) {
              val newRoadwayPoint = roadwayPointDAO.create(conversionNodePoint.roadwayNumberTR, conversionNodePoint.addressMValueTR, createdBy = "node_import")
              insertNodePoint(nodePointPs, conversionNodePoint, conversionNode._1.nodeNumber, conversionNode._1.endDate, newRoadwayPoint)
            } else insertNodePoint(nodePointPs, conversionNodePoint, conversionNode._1.nodeNumber, conversionNode._1.endDate, existingRoadwayPoint.get.id)
          }
        }
    }
    nodePs.executeBatch()
    nodePointPs.executeBatch()
    nodePs.close()
    nodePointPs.close()
    resetNodeNumberSequence()
  }

  def resetNodeNumberSequence(): Unit = {
    val sequenceResetter = new SequenceResetterDAO()
    sql"""select MAX(NODE_NUMBER) FROM NODE""".as[Long].firstOption match {
      case Some(nodeNumber) =>
        sequenceResetter.resetSequenceToNumber("NODE_NUMBER_SEQ", nodeNumber + 1)
      case _ => sequenceResetter.resetSequenceToNumber("NODE_NUMBER_SEQ", 1)
    }
  }

  protected def fetchNodesFromConversionTable(): Seq[ConversionNode] = {
    conversionDatabase.withDynSession {
      sql"""SELECT ID, SOLMUNRO, X, Y, NIMI, SOLMUN_TYYPPI, TO_CHAR(VOIMASSAOLOAIKA_ALKU, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(VOIMASSAOLOAIKA_LOPPU, 'YYYY-MM-DD hh:mm:ss'),
            TO_CHAR(MUUTOSPVM, 'YYYY-MM-DD hh:mm:ss'), KAYTTAJA, TO_CHAR(REKISTEROINTIPVM, 'YYYY-MM-DD hh:mm:ss') FROM SOLMU """
        .as[ConversionNode].list
    }
  }

  protected def fetchNodePointsFromConversionTable(): Seq[ConversionNodePoint] = {
    conversionDatabase.withDynSession {
      sql"""SELECT NP.ID, NP.EJ, NP.ID_SOLMU, N.SOLMUNRO, AP.ID_AJORATA, AP.ETAISYYS,
            TO_CHAR(np.MUUTOSPVM, 'YYYY-MM-DD hh:mm:ss'), NP.KAYTTAJA, TO_CHAR(NP.REKISTEROINTIPVM, 'YYYY-MM-DD hh:mm:ss')
            FROM SOLMUKOHTA NP
            JOIN AJORADAN_PISTE AP ON (ID_TIEOSOITE = AP.ID)
            JOIN SOLMU N ON (ID_SOLMU = N.ID)
            WHERE NP.VOIMASSAOLOAIKA_LOPPU IS NULL OR N.VOIMASSAOLOAIKA_LOPPU IS NOT NULL
      """
        .as[ConversionNodePoint].list
    }
  }


  implicit val getConversionNode: GetResult[ConversionNode] = new GetResult[ConversionNode] {
    def apply(r: PositionedResult): ConversionNode = {
      val id = r.nextLong()
      val nodeNumber = r.nextLong()
      val xValue = r.nextLong()
      val yValue = r.nextLong()
      val name = r.nextStringOption()
      val nodeType = r.nextLong()
      val startDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val endDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val validFrom = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val createdBy = r.nextString()
      val registrationDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      ConversionNode(id, nodeNumber, Point(xValue, yValue), name, nodeType, startDate, endDate, validFrom, None, createdBy, registrationDate)
    }
  }

  implicit val getConversionNodePoint: GetResult[ConversionNodePoint] = new GetResult[ConversionNodePoint] {
    def apply(r: PositionedResult): ConversionNodePoint = {
      val id = r.nextLong()
      val beforeOrAfterString = r.nextString()
      val nodeId = r.nextLong()
      val nodeNumber = r.nextLong()
      val roadwayNumberInTR = r.nextLong()
      val addressMValueInTR = r.nextLong()
      val validFrom = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val createdBy = r.nextString()
      val createdTime = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val beforeOrAfter = beforeOrAfterString match {
        case "E" => 1
        case "J" => 2
        case _ => 0
      }
      ConversionNodePoint(id, beforeOrAfter, nodeId, nodeNumber, roadwayNumberInTR, addressMValueInTR, validFrom, None, createdBy, createdTime)
    }
  }

  def datePrinter(date: Option[DateTime]): String = {
    date match {
      case Some(dt) => dateFormatter.print(dt)
      case None => ""
    }
  }

}

