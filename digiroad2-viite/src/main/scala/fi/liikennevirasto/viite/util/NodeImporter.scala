package fi.liikennevirasto.viite.util

import java.sql.PreparedStatement
import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.oracle.OracleDatabase
import fi.liikennevirasto.viite._
import org.joda.time._
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._



class NodeImporter(conversionDatabase: DatabaseDef) {
  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  case class ConversionNode(id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: Long, startDate: Option[DateTime], endDate: Option[DateTime], validFrom: Option[DateTime],
                            validTo: Option[DateTime], createdBy: String, createdTime: Option[DateTime])

  private def insertNodeStatement(): PreparedStatement =
    dynamicSession.prepareStatement(sql = "INSERT INTO NODE (ID, NODE_NUMBER, COORDINATES, NAME, TYPE, START_DATE, END_DATE, VALID_FROM, CREATED_BY) VALUES " +
      "(?, ?, ?, ?, ?, ?, ?, ?, ?)")


  def insertNode(nodeStatement: PreparedStatement, conversionNode: ConversionNode): Unit ={
    nodeStatement.setLong(1, Sequences.nextNodeId)
    nodeStatement.setLong(2, conversionNode.nodeNumber)
    nodeStatement.setObject(3, OracleDatabase.createRoadsJGeometry(Seq(conversionNode.coordinates), dynamicSession.conn, endMValue = 0))
    nodeStatement.setString(4, conversionNode.name.getOrElse(""))
    nodeStatement.setLong(5, conversionNode.nodeType)
    nodeStatement.setString(6, datePrinter(conversionNode.startDate))
    nodeStatement.setString(7, datePrinter(conversionNode.endDate))
    nodeStatement.setString(8, datePrinter(conversionNode.validFrom))
    nodeStatement.setString(9, conversionNode.createdBy)
    nodeStatement.addBatch()
  }

  def importNodes(): Unit = {
    println("\nFetching all nodes from conversion database")
    val conversionNodes = fetchNodesFromConversionTable()
    val nodePs = insertNodeStatement()
    conversionNodes.foreach{
      conversionNode =>
        println(s"Inserting node with TR id = ${conversionNode.id} and node_number = ${conversionNode.nodeNumber}")
        insertNode(nodePs, conversionNode)
    }
    nodePs.executeBatch()
    nodePs.close()
  }

  protected def fetchNodesFromConversionTable(): Seq[ConversionNode] = {
    conversionDatabase.withDynSession {
      sql"""SELECT ID SOLMUNRO, X, Y, NIMI, ID_SOLMUN_TYYPPI, TO_CHAR(VOIMASSAOLOAIKA_ALKU, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(VOIMASSAOLOAIKA_LOPPU, 'YYYY-MM-DD hh:mm:ss'),
            TO_CHAR(MUUTOSPVM, 'YYYY-MM-DD hh:mm:ss'), KAYTTAJA, TO_CHAR(REKISTEROINTIPVM, 'YYYY-MM-DD hh:mm:ss') FROM SOLMU """
        .as[ConversionNode].list
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
      val createdTime = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      ConversionNode(id, nodeNumber, Point(xValue, yValue), name, nodeType, startDate, endDate, validFrom, None, createdBy, createdTime)
    }
  }

  def datePrinter(date: Option[DateTime]): String = {
    date match {
      case Some(dt) => dateFormatter.print(dt)
      case None => ""
    }
  }

}

