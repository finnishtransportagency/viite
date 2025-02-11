package fi.liikennevirasto.viite.util

import fi.liikennevirasto.viite.dao.{NodeDAO, RoadwayPointDAO}
import fi.liikennevirasto.viite.util.DataImporter.Conversion.runWithConversionDbReadOnlySession
import fi.vaylavirasto.viite.dao.{BaseDAO, Sequences}
import org.joda.time.DateTime
import scalikejdbc._
import scalikejdbc.jodatime.JodaWrappedResultSet.fromWrappedResultSetToJodaWrappedResultSet


class JunctionImporter extends BaseDAO {

  val nodeDAO = new NodeDAO

  val roadwayPointDAO = new RoadwayPointDAO

  case class ConversionJunction(id: Long, junctionNumber: Long, nodeNumber: Long, startDate: Option[DateTime],
                                endDate: Option[DateTime], validFrom: Option[DateTime], validTo: Option[DateTime],
                                createdBy: String, createdTime: Option[DateTime])

  case class ConversionJunctionPoint(id: Long, beforeOrAfter: Long, roadwayNumberTR: Long, addressMValueTR: Long, junctionTRId: Long,
                                 validFrom: Option[DateTime], validTo: Option[DateTime], createdBy: String, createdTime: Option[DateTime])


  /**
   * Main method to import junctions from conversion database to main database
   * The method fetches all junctions and their points from conversion database
   * and imports them to main database after creating necessary parameters for batch update
   */
  def importJunctions(): Unit = {
    println("\n\n\nFetching all junctions from conversion database")
    val conversionJunctions = fetchJunctionsFromConversionTable()
    val conversionJunctionPoints = fetchJunctionPointsFromConversionTable()

    // group junctions with their points
    val junctionsWithPoints = conversionJunctions.map(
      junction => {
        val junctionPointsForJunction = conversionJunctionPoints.filter(_.junctionTRId == junction.id)
        (junction.copy(id = Sequences.nextJunctionId), junctionPointsForJunction)
      }
    )

    // Collect all junction parameters for batch update
    val junctionParams = junctionsWithPoints.map { case (junction, _) =>
      println(s"Processing junction with TR id = ${junction.id} and junction_number = ${junction.junctionNumber}")
      createJunctionParams(junction)
    }

    // Collect all junction point parameters for batch update
    val junctionPointParams: Seq[Seq[Any]] = junctionsWithPoints.flatMap { case (junction, points) =>
      points.map { point =>
        println(s"Processing junction point with TR id = ${point.id} and junction_id = ${point.junctionTRId}")

        // check if roadway point already exists with same roadway number and address m value
        val existingRoadwayPoint = roadwayPointDAO.fetch(point.roadwayNumberTR, point.addressMValueTR)
        // Create roadway point if it does not exist and get its id
        val roadwayPointId = existingRoadwayPoint match {
          case Some(rwp) => rwp.id
          case None =>
            roadwayPointDAO.create(point.roadwayNumberTR, point.addressMValueTR, createdBy = "junction_import")
        }

        // Create junction point parameters and return them
        createJunctionPointParams(point, junction.id, roadwayPointId)
      }
    }

    // Execute batch updates to main database
    batchUpdateJunctions(junctionParams)
    batchUpdateJunctionPoints(junctionPointParams)

  }

  protected def fetchJunctionsFromConversionTable(): Seq[ConversionJunction] = {
    runWithConversionDbReadOnlySession {
      val query = sql"""
            SELECT l.id, liittymanro, solmunro, TO_CHAR(L.voimassaoloaika_alku, 'YYYY-MM-DD hh:mm:ss'),
                TO_CHAR(l.voimassaoloaika_loppu, 'YYYY-MM-DD hh:mm:ss'),
                TO_CHAR(l.muutospvm, 'YYYY-MM-DD hh:mm:ss'), L.kayttaja, TO_CHAR(l.rekisterointipvm, 'YYYY-MM-DD hh:mm:ss')
            FROM liittyma L JOIN solmu S ON (id_solmu = S.id)
            """
        runSelectQuery(query.map(ConversionJunctionScalike.apply))
    }
  }

  protected def fetchJunctionPointsFromConversionTable(): Seq[ConversionJunctionPoint] = {
    runWithConversionDbReadOnlySession {
      val query = sql"""
           SELECT jp.id, jp.ej, ap.id_ajorata, ap.etaisyys, jp.id_liittyma,
              TO_CHAR(jp.muutospvm, 'YYYY-MM-DD hh:mm:ss'),
              jp.kayttaja, TO_CHAR(jp.rekisterointipvm, 'YYYY-MM-DD hh:mm:ss')
           FROM liittymakohta jp
           JOIN ajoradan_piste ap ON (jp.id_ajoradan_piste = ap.id)
           JOIN liittyma j ON (jp.id_liittyma = j.id)
           WHERE jp.voimassaoloaika_loppu IS NULL OR j.voimassaoloaika_loppu IS NOT NULL
      """
       runSelectQuery(query.map(ConversionJunctionPointScalike.apply))
    }
  }

  // Create junction parameters for batch update
  private def createJunctionParams(conversionJunction: ConversionJunction): Seq[Any] = {
    Seq(
      conversionJunction.id,
      conversionJunction.junctionNumber,
      conversionJunction.nodeNumber,
      conversionJunction.startDate,
      conversionJunction.endDate.orNull,
      conversionJunction.validFrom.get.getMillis,
      conversionJunction.createdBy
    )
  }

  private def batchUpdateJunctions(params: Seq[Seq[Any]]): List[Int] = {
    val insertQuery =
      sql"""
            INSERT INTO junction (id, junction_number, node_number, start_date, end_date, valid_from, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """

    runBatchUpdateToDb(insertQuery, params)
  }

  // Create junction point parameters for batch update
  private def createJunctionPointParams(conversionJunctionPoint: ConversionJunctionPoint, junctionId: Long, roadwayPointId: Long): Seq[Any] = {
    Seq(
      Sequences.nextJunctionPointId,
      conversionJunctionPoint.beforeOrAfter,
      roadwayPointId,
      junctionId,
      conversionJunctionPoint.validFrom.get.getMillis,
      conversionJunctionPoint.createdBy
    )
  }

  private def batchUpdateJunctionPoints(params: Seq[Seq[Any]]): List[Int] = {
    val insertQuery =
      sql"""
            INSERT INTO junction_point (id, before_after, roadway_point_id, junction_id, valid_from, created_by)
            VALUES (?, ?, ?, ?, ?, ?)
            """

    runBatchUpdateToDb(insertQuery, params)
  }

  // Mapper functions for conversion junction and junction point
  object ConversionJunctionScalike extends SQLSyntaxSupport[ConversionJunction] {
    def apply(rs: WrappedResultSet): ConversionJunction = {
      ConversionJunction(
        id             = rs.long("id"),
        junctionNumber = rs.long("liittymanro"),
        nodeNumber     = rs.long("solmunro"),
        startDate      = rs.jodaDateTimeOpt("voimassaoloaika_alku"),
        endDate        = rs.jodaDateTimeOpt("voimassaoloaika_loppu"),
        validFrom      = rs.jodaDateTimeOpt("muutospvm"),
        validTo        = None,
        createdBy      = rs.string("kayttaja"),
        createdTime    = rs.jodaDateTimeOpt("rekisterointipvm")
      )
    }
  }

  object ConversionJunctionPointScalike extends SQLSyntaxSupport[ConversionJunctionPoint] {
    def apply(rs: WrappedResultSet): ConversionJunctionPoint = {
      val beforeOrAfter = rs.string("ej") match {
        case "E" => 1
        case "J" => 2
        case _ => 0
      }

      ConversionJunctionPoint(
        id              = rs.long("id"),
        beforeOrAfter   = beforeOrAfter,
        roadwayNumberTR = rs.long("id_aorata"),
        addressMValueTR = rs.long("etaisyys"),
        junctionTRId    = rs.long("id_liittyma"),
        validFrom       = rs.jodaDateTimeOpt("muutospvm"),
        validTo         = None,
        createdBy       = rs.string("kayttaja"),
        createdTime     = rs.jodaDateTimeOpt("rekisterointipvm")
      )
    }
  }

}

