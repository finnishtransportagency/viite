package fi.liikennevirasto.viite.util

import fi.liikennevirasto.viite.dao.RoadwayPointDAO
import fi.vaylavirasto.viite.dao.{BaseDAO, SequenceResetterDAO, Sequences}
import fi.vaylavirasto.viite.geometry.Point
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet

// TODO: This class has not been used in a long time and it should be removed if there is no need for it. If this will be used, bear in mind that the code is not tested properly after scalikeJDBC migration.
case class ConversionNode(id: Long, nodeNumber: Long, coordinates: Point, name: Option[String], nodeType: Long, startDate: Option[DateTime], endDate: Option[DateTime], validFrom: Option[DateTime],
                          validTo: Option[DateTime], createdBy: String, registrationDate: Option[DateTime])

case class ConversionNodePoint(id: Long, beforeOrAfter: Long, nodeId: Long, nodeNumber: Long, roadwayNumber: Long, addressMValue: Long,
                               validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: String, createdTime: Option[DateTime])

class NodeImporter extends BaseDAO {

  def  runWithConversionDbReadOnlySession [T] (f: => T): T = DataImporter.Conversion.runWithConversionDbReadOnlySession(f)

  val roadwayPointDAO = new RoadwayPointDAO

  /*
   * Main method to import nodes from conversion database to main database
   * The method fetches all nodes and their points from conversion database
   * and imports them to main database after creating necessary parameters for batch update
   */
  def importNodes(): Unit = {
    println("\nFetching all nodes from conversion database")
    val conversionNodes = fetchNodesFromConversionTable()
    val conversionNodePoints = fetchNodePointsFromConversionTable()

    // group nodes with their points
    val nodesWithPoints = conversionNodes.map(
      conversionNode => (conversionNode, conversionNodePoints.filter(_.nodeId == conversionNode.id))
    )

    // Collect all node parameters for batch update
    val nodeParams = nodesWithPoints.map { case (node, _) =>
      println(s"Processing node with TR id = ${node.id} and node_number = ${node.nodeNumber}")
      createNodeParams(node)
    }

    // Collect all node point parameters for batch update
    val nodePointParams = nodesWithPoints.flatMap { case (node, points) =>
      points.flatMap { point =>
        println(s"Processing node point with TR id = ${point.id} and node_id = ${point.nodeId} for node_number = ${node.nodeNumber}")

        // check if roadway point already exists with same roadway number and address m value
        val existingRoadwayPoint = roadwayPointDAO.fetch(point.roadwayNumber, point.addressMValue)
        // Create roadway point if it does not exist and get its id
        val roadwayPointId = existingRoadwayPoint match {
          case Some(rwp) => rwp.id
          case None =>
            roadwayPointDAO.create(point.roadwayNumber, point.addressMValue, createdBy = "node_import")
        }

        // Create node point parameters and return them
        Seq(createNodePointParams(point, node.nodeNumber, node.endDate, roadwayPointId))
      }
    }

    // Execute batch updates to main database
    if (nodeParams.nonEmpty) {
      batchUpdateNodes(nodeParams)
    }
    if (nodePointParams.nonEmpty) {
      batchUpdateNodePoints(nodePointParams)
    }

    resetNodeNumberSequence()
  }


  private def createNodeParams(conversionNode: ConversionNode): Seq[Any] = {
    val newNodeId = Sequences.nextNodeId

    Seq(
      newNodeId,
      conversionNode.nodeNumber,
      conversionNode.coordinates.x,
      conversionNode.coordinates.y,
      conversionNode.name.getOrElse(""),
      conversionNode.nodeType,
      conversionNode.startDate.orNull,
      conversionNode.endDate.orNull,
      conversionNode.validFrom.orNull,
      conversionNode.createdBy,
      conversionNode.registrationDate.getOrElse(throw new RuntimeException(s"Registration date must be set for node number: ${conversionNode.nodeNumber}"))
    )
  }

  private def batchUpdateNodes(params: Seq[Seq[Any]]): List[Int] = {
    val insertQuery = sql"""
                        INSERT INTO node (id, node_number, coordinates, name, type, start_date, end_date, valid_from, created_by, registration_date)
                        VALUES(?, ?, ST_GeomFromText('POINT('||?||' '||?||' 0.0)', 3067), ?, ?, ?, ?, ?, ?, ?)
                        """

    runBatchUpdateToDb(insertQuery, params)
  }

  private def createNodePointParams(nodePoint: ConversionNodePoint, nodeNumber: Long, nodeEndDate: Option[DateTime], roadwayPointId: Long):  Seq[Any] = {
    Seq(
      Sequences.nextNodePointId,
      nodePoint.beforeOrAfter,
      roadwayPointId,
      nodeNumber,
      nodePoint.validFrom.orNull,
      nodeEndDate.orNull,
      nodePoint.createdBy
    )
  }

  private def batchUpdateNodePoints(params: Seq[Seq[Any]]): List[Int] = {
    val insertQuery = sql"""
                       INSERT INTO node_point (id, before_after, roadway_point_id, node_number, valid_from, valid_to, created_by)
                       VALUES (?, ?, ?, ?, ?, ?, ?)
                       """

    runBatchUpdateToDb(insertQuery, params)
  }

  // Reset the sequence to the maximum node number in the database
  def resetNodeNumberSequence(): Unit = {
    val sequenceResetter = new SequenceResetterDAO()

    // check the maximum node number in the database
    val query = sql"select MAX(NODE_NUMBER) FROM NODE"
    val maxNodeNumberOption = runSelectFirst(query.map(_.long(1)))

    // reset the sequence to the maximum node number + 1 or 1 if no nodes are found
    maxNodeNumberOption match {
      case Some(nodeNumber) =>  //
        sequenceResetter.resetSequenceToNumber("NODE_NUMBER_SEQ", nodeNumber + 1)
      case _ => sequenceResetter.resetSequenceToNumber("NODE_NUMBER_SEQ", 1)
    }
  }

  protected def fetchNodesFromConversionTable(): Seq[ConversionNode] = {
    runWithConversionDbReadOnlySession {
      val query =
        sql"""
            SELECT ID, SOLMUNRO, X, Y, NIMI, SOLMUN_TYYPPI, TO_CHAR(VOIMASSAOLOAIKA_ALKU, 'YYYY-MM-DD hh:mm:ss'), TO_CHAR(VOIMASSAOLOAIKA_LOPPU, 'YYYY-MM-DD hh:mm:ss'),
            TO_CHAR(MUUTOSPVM, 'YYYY-MM-DD hh:mm:ss'), KAYTTAJA, TO_CHAR(REKISTEROINTIPVM, 'YYYY-MM-DD hh:mm:ss') FROM SOLMU
        """
        runSelectQuery(query.map(ConversionNodeScalike.apply))
    }
  }

  protected def fetchNodePointsFromConversionTable(): Seq[ConversionNodePoint] = {
    runWithConversionDbReadOnlySession {
      val query =
        sql"""
            SELECT NP.ID, NP.EJ, NP.ID_SOLMU, N.SOLMUNRO, AP.ID_AJORATA, AP.ETAISYYS,
            TO_CHAR(np.MUUTOSPVM, 'YYYY-MM-DD hh:mm:ss'), NP.KAYTTAJA, TO_CHAR(NP.REKISTEROINTIPVM, 'YYYY-MM-DD hh:mm:ss')
            FROM SOLMUKOHTA NP
            JOIN AJORADAN_PISTE AP ON (ID_TIEOSOITE = AP.ID)
            JOIN SOLMU N ON (ID_SOLMU = N.ID)
            WHERE NP.VOIMASSAOLOAIKA_LOPPU IS NULL OR N.VOIMASSAOLOAIKA_LOPPU IS NOT NULL
        """
        runSelectQuery(query.map(ConversionNodePointScalike.apply))
    }
  }

  object ConversionNodeScalike extends SQLSyntaxSupport[ConversionNode] {
    def apply(rs: WrappedResultSet): ConversionNode = {
      ConversionNode(
        id               = rs.long("id"),
        nodeNumber       = rs.long("solmunro"),
        coordinates      = Point(rs.long("x"), rs.long("y")),
        name             = rs.stringOpt("nimi"),
        nodeType         = rs.long("solmun_tyyppi"),
        startDate        = rs.jodaDateTimeOpt("voimassaoloaika_alku"),
        endDate          = rs.jodaDateTimeOpt("voimassaoloaika_loppu"),
        validFrom        = rs.jodaDateTimeOpt("muutospvm"),
        validTo          = None,
        createdBy        = rs.string("kayttaja"),
        registrationDate = rs.jodaDateTimeOpt("rekisterointipvm")
      )
    }
  }

  object ConversionNodePointScalike extends SQLSyntaxSupport[ConversionNodePoint] {
    def apply(rs: WrappedResultSet): ConversionNodePoint = {
      val beforeOrAfter = rs.string("ej") match {
        case "E" => 1
        case "J" => 2
        case _ => 0
      }

      ConversionNodePoint(
        id              = rs.long("id"),
        beforeOrAfter   = beforeOrAfter,
        nodeId          = rs.long("id_solmu"),
        nodeNumber      = rs.long("solmunro"),
        roadwayNumber   = rs.long("id_ajorata"),
        addressMValue   = rs.long("etaisyys"),
        validFrom       = rs.jodaDateTimeOpt("muutospvm"),
        validTo         = None,
        createdBy       = rs.string("kayttaja"),
        createdTime     = rs.jodaDateTimeOpt("rekisterointipvm")
      )
    }
  }

}

