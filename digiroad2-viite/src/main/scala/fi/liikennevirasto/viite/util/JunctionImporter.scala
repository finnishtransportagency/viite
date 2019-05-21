package fi.liikennevirasto.viite.util

import java.sql.PreparedStatement

import org.joda.time.format.{DateTimeFormatter, ISODateTimeFormat}
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.viite.dao.{NodeDAO, RoadwayPointDAO}
import org.joda.time._
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._


class JunctionImporter(conversionDatabase: DatabaseDef) {
  val dateFormatter: DateTimeFormatter = ISODateTimeFormat.basicDate()

  val nodeDAO = new NodeDAO

  val roadwayPointDAO = new RoadwayPointDAO

  case class ConversionJunction(id: Long, junctionNumber: Long, nodeNumber: Long, startDate: Option[DateTime],
                                endDate: Option[DateTime], validFrom: Option[DateTime], validTo: Option[DateTime],
                                createdBy: String, createdTime: Option[DateTime])

  case class ConversionJunctionPoint(id: Long, beforeOrAfter: Long, roadwayNumberTR: Long, addressMValueTR: Long, junctionTRId: Long,
                                 startDate: Option[DateTime], endDate: Option[DateTime], validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: String, createdTime: Option[DateTime])

  private def insertJunctionStatement(): PreparedStatement =
    dynamicSession.prepareStatement(sql = "INSERT INTO JUNCTION (ID, JUNCTION_NUMBER, NODE_ID, START_DATE, END_DATE, VALID_FROM, CREATED_BY) VALUES " +
      " (?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?)")

  private def insertJunctionPointStatement(): PreparedStatement =
    dynamicSession.prepareStatement(sql = "INSERT INTO JUNCTION_POINT (ID, BEFORE_AFTER, ROADWAY_POINT_ID, JUNCTION_ID, START_DATE, END_DATE, VALID_FROM, CREATED_BY) VALUES " +
      " (?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?) ")


  def insertJunction(junctionStatement: PreparedStatement, conversionJunction: ConversionJunction, nodeId: Long): Unit ={
    junctionStatement.setLong(1, conversionJunction.id)
    junctionStatement.setLong(2, conversionJunction.junctionNumber)
    junctionStatement.setLong(3, nodeId)
    junctionStatement.setString(4, datePrinter(conversionJunction.startDate))
    junctionStatement.setString(5, datePrinter(conversionJunction.endDate))
    junctionStatement.setString(6, datePrinter(conversionJunction.validFrom))
    junctionStatement.setString(7, conversionJunction.createdBy)
    junctionStatement.addBatch()
  }

  def insertJunctionPoint(junctionPointStatement: PreparedStatement, conversionJunctionPoint: ConversionJunctionPoint, junctionId: Long, roadwayPointId: Long): Unit = {
    junctionPointStatement.setLong(1, conversionJunctionPoint.id)
    junctionPointStatement.setLong(2, conversionJunctionPoint.beforeOrAfter)
    junctionPointStatement.setLong(3, roadwayPointId)
    junctionPointStatement.setLong(4, junctionId)
    junctionPointStatement.setString(5, datePrinter(conversionJunctionPoint.startDate))
    junctionPointStatement.setString(6, datePrinter(conversionJunctionPoint.endDate))
    junctionPointStatement.setString(7, datePrinter(conversionJunctionPoint.validFrom))
    junctionPointStatement.setString(8, conversionJunctionPoint.createdBy)
    junctionPointStatement.addBatch()
  }

  def importJunctions(): Unit = {
    println("\n\n\nFetching all junctions from conversion database")
    val conversionJunctions = fetchJunctionsFromConversionTable()
    val conversionJunctionPoints = fetchJunctionPointsFromConversionTable()
    val junctionPs = insertJunctionStatement()
    val junctionPointPs = insertJunctionPointStatement()

    val junctionsWithPoints = conversionJunctions.map(
      junction =>{
        val junctionPointsForJunction = conversionJunctionPoints.filter(_.junctionTRId == junction.id)
        (junction.copy(id = Sequences.nextJunctionId), junctionPointsForJunction)
      }
    )

    junctionsWithPoints.foreach{
      conversionJunction =>
        println(s"Inserting junction with TR id = ${conversionJunction._1.id} ")
        val nodeId = nodeDAO.fetchIdWithHistory(conversionJunction._1.nodeNumber)
        insertJunction(junctionPs, conversionJunction._1, nodeId.get)

        conversionJunction._2.foreach{
          conversionJunctionPoint =>
            println(s"Inserting junction point with TR id = ${conversionJunctionPoint.id} ")
            val existingRoadwayPoint = roadwayPointDAO.fetch(conversionJunctionPoint.roadwayNumberTR, conversionJunctionPoint.addressMValueTR)
            if(existingRoadwayPoint.isEmpty){
              val newRoadwayPoint = roadwayPointDAO.create(conversionJunctionPoint.roadwayNumberTR, conversionJunctionPoint.addressMValueTR, createdBy = "junction_import")
              insertJunctionPoint(junctionPointPs, conversionJunctionPoint, conversionJunction._1.id, newRoadwayPoint)
            }
            else
              insertJunctionPoint(junctionPointPs, conversionJunctionPoint, conversionJunction._1.id, existingRoadwayPoint.get.id)
        }
    }

    junctionPs.executeBatch()
    junctionPointPs.executeBatch()
    junctionPs.close()
    junctionPointPs.close()
  }

  protected def fetchJunctionsFromConversionTable(): Seq[ConversionJunction] = {
    conversionDatabase.withDynSession {
      sql"""SELECT L.ID, LIITTYMANRO, solmunro, TO_CHAR(L.VOIMASSAOLOAIKA_ALKU, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(L.VOIMASSAOLOAIKA_LOPPU, 'YYYY-MM-DD hh:mm:ss'),
           TO_CHAR(L.MUUTOSPVM, 'YYYY-MM-DD hh:mm:ss'), L.KAYTTAJA, TO_CHAR(L.REKISTEROINTIPVM, 'YYYY-MM-DD hh:mm:ss')
           FROM LIITTYMA L JOIN SOLMU S ON (ID_SOLMU = S.id)  """
        .as[ConversionJunction].list
    }
  }

  protected def fetchJunctionPointsFromConversionTable(): Seq[ConversionJunctionPoint] = {
    conversionDatabase.withDynSession {
      sql"""SELECT JP.ID, JP.EJ, AP.ID_AJORATA, AP.ETAISYYS, JP.ID_LIITTYMA, TO_CHAR(JP.VOIMASSAOLOAIKA_ALKU, 'YYYY-MM-DD hh:mm:ss'),
           TO_CHAR(JP.VOIMASSAOLOAIKA_LOPPU, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(JP.MUUTOSPVM, 'YYYY-MM-DD hh:mm:ss'), JP.KAYTTAJA, TO_CHAR(JP.REKISTEROINTIPVM, 'YYYY-MM-DD hh:mm:ss')
           FROM LIITTYMAKOHTA JP
           JOIN AJORADAN_PISTE AP ON (ID_AJORADAN_PISTE = AP.ID) """
        .as[ConversionJunctionPoint].list
    }
  }


  implicit val getConversionJunction: GetResult[ConversionJunction] = new GetResult[ConversionJunction] {
    def apply(r: PositionedResult): ConversionJunction = {
      val id = r.nextLong()
      val junctionNumber = r.nextLong()
      val nodeNumber = r.nextLong()
      val startDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val endDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val validFrom = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val createdBy = r.nextString()
      val createdTime = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))

      ConversionJunction(id, junctionNumber, nodeNumber, startDate, endDate, validFrom, None, createdBy, createdTime)
    }
  }

  implicit val getConversionNodePoint: GetResult[ConversionJunctionPoint] = new GetResult[ConversionJunctionPoint] {
    def apply(r: PositionedResult): ConversionJunctionPoint = {
      val id = r.nextLong()
      val beforeOrAfterString = r.nextString()
      val roadwayNumberInTR = r.nextLong()
      val addressMValueInTR = r.nextLong()
      val junctionTRId = r.nextLong()
      val startDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val endDate = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val validFrom = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val createdBy = r.nextString()
      val createdTime = r.nextTimestampOption().map(timestamp => new DateTime(timestamp))
      val beforeOrAfter = beforeOrAfterString match {
        case "E" => 1
        case "J" => 2
        case _ => 0
      }
      ConversionJunctionPoint(id, beforeOrAfter, roadwayNumberInTR, addressMValueInTR, junctionTRId, startDate, endDate, validFrom, None, createdBy, createdTime)
    }
  }

  def datePrinter(date: Option[DateTime]): String = {
    date match {
      case Some(dt) => dateFormatter.print(dt)
      case None => ""
    }
  }

}

