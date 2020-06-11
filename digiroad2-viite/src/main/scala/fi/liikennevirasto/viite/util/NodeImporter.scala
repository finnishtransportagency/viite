package fi.liikennevirasto.viite.util

import java.sql.PreparedStatement

import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.dao.{SequenceResetterDAO, Sequences}
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite.dao.RoadwayPointDAO
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
    dynamicSession.prepareStatement(sql = "INSERT INTO NODE (ID, NODE_NUMBER, COORDINATES, NAME, TYPE, START_DATE, END_DATE, VALID_FROM, CREATED_BY) VALUES " +
      " (?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?, ?)")

  private def insertNodePointStatement(): PreparedStatement =
    dynamicSession.prepareStatement(sql = "INSERT INTO NODE_POINT (ID, BEFORE_AFTER, ROADWAY_POINT_ID, NODE_NUMBER, VALID_FROM, VALID_TO, CREATED_BY) VALUES " +
      " (?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?) ")


  def insertNode(nodeStatement: PreparedStatement, conversionNode: ConversionNode): Unit = {
    nodeStatement.setLong(1, conversionNode.id)
    nodeStatement.setLong(2, conversionNode.nodeNumber)
    nodeStatement.setObject(3, OracleDatabase.createRoadsJGeometry(Seq(conversionNode.coordinates), dynamicSession.conn, endMValue = 0))
    nodeStatement.setString(4, conversionNode.name.getOrElse(""))
    nodeStatement.setLong(5, conversionNode.nodeType)
    nodeStatement.setString(6, datePrinter(conversionNode.startDate))
    nodeStatement.setString(7, datePrinter(conversionNode.endDate))
    nodeStatement.setString(8, datePrinter(conversionNode.validFrom))
    nodeStatement.setString(9, conversionNode.createdBy)
    nodeStatement.setString(10, datePrinter(conversionNode.registrationDate))
    nodeStatement.addBatch()
  }

  def insertNodePoint(nodePointStatement: PreparedStatement, nodePoint: ConversionNodePoint, nodeNumber: Long, nodeEndDate: Option[DateTime], roadwayPointId: Long): Unit = {
    nodePointStatement.setLong(1, Sequences.nextNodePointId)
    nodePointStatement.setLong(2, nodePoint.beforeOrAfter)
    nodePointStatement.setLong(3, roadwayPointId)
    nodePointStatement.setLong(4, nodeNumber)
    nodePointStatement.setString(5, datePrinter(nodePoint.validFrom))
    nodePointStatement.setString(6, datePrinter(nodeEndDate))
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

