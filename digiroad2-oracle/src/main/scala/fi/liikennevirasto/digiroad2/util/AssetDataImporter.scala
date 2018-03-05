package fi.liikennevirasto.digiroad2.util

import java.util.Properties
import javax.sql.DataSource

import com.jolbox.bonecp.{BoneCPConfig, BoneCPDataSource}
import fi.liikennevirasto.digiroad2.asset.{BoundingRectangle, LinkGeomSource, SideCode}
import fi.liikennevirasto.digiroad2.linearasset._
import org.joda.time.format.{DateTimeFormat, PeriodFormat}
import slick.driver.JdbcDriver.backend.{Database, DatabaseDef}
import Database.dynamicSession
import _root_.oracle.sql.STRUCT
import com.github.tototoshi.slick.MySQLJodaSupport._
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.NormalLinkInterface
import fi.liikennevirasto.digiroad2.client.vvh.{VVHClient, VVHRoadlink}
import fi.liikennevirasto.digiroad2.dao.{Queries, Sequences}
import fi.liikennevirasto.digiroad2.dao.Queries._
import fi.liikennevirasto.digiroad2.oracle.{MassQuery, OracleDatabase}
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.AssetDataImporter.{SimpleBusStop, _}
import org.joda.time._
import org.slf4j.LoggerFactory
import slick.jdbc.StaticQuery.interpolation
import slick.jdbc._

import scala.collection.mutable

object
AssetDataImporter {
  case class SimpleBusStop(shelterType: Int,
                           assetId: Option[Long] = None,
                           busStopId: Option[Long],
                           busStopType: Seq[Int],
                           lrmPositionId: Long,
                           validFrom: LocalDate = LocalDate.now,
                           validTo: Option[LocalDate] = None,
                           point: Point,
                           roadLinkId: Long,
                           municipalityCode: Int,
                           bearing: Double)
  case class SimpleRoadLink(id: Long, roadType: Int, roadNumber: Int, roadPartNumber: Int, functionalClass: Int, rStartHn: Int, lStartHn: Int,
                            rEndHn: Int, lEndHn: Int, municipalityNumber: Int, geom: STRUCT)

  case class PropertyWrapper(shelterTypePropertyId: Long, accessibilityPropertyId: Long, administratorPropertyId: Long,
                             busStopAssetTypeId: Long, busStopTypePropertyId: Long, busStopLiViPropertyId: Long)

  sealed trait ImportDataSet {
    def database(): DatabaseDef
  }

  case object TemporaryTables extends ImportDataSet {
    lazy val dataSource: DataSource = {
      val cfg = new BoneCPConfig(OracleDatabase.loadProperties("/import.bonecp.properties"))
      new BoneCPDataSource(cfg)
    }

    def database() = Database.forDataSource(dataSource)
  }

  case object Conversion extends ImportDataSet {
    lazy val dataSource: DataSource = {
      val cfg = new BoneCPConfig(OracleDatabase.loadProperties("/conversion.bonecp.properties"))
      new BoneCPDataSource(cfg)
    }

    def database() = Database.forDataSource(dataSource)
    val roadLinkTable: String = "tielinkki"
    val busStopTable: String = "lineaarilokaatio"
  }

  def humanReadableDurationSince(startTime: DateTime): String = {
    PeriodFormat.getDefault.print(new Period(startTime, DateTime.now()))
  }
}

class AssetDataImporter {
  val logger = LoggerFactory.getLogger(getClass)
  lazy val ds: DataSource = initDataSource

  val Modifier = "dr1conversion"

  def withDynTransaction(f: => Unit): Unit = OracleDatabase.withDynTransaction(f)
  def withDynSession[T](f: => T): T = OracleDatabase.withDynSession(f)

  implicit object SetStruct extends SetParameter[STRUCT] {
    def apply(v: STRUCT, pp: PositionedParameters) {
      pp.setObject(v, java.sql.Types.STRUCT)
    }
  }

  def time[A](f: => A) = {
    val s = System.nanoTime
    val ret = f
    println("time for insert "+(System.nanoTime-s)/1e6+"ms")
    ret
  }

  private def getBatchDrivers(size: Int): List[(Int, Int)] = {
    println(s"""creating batching for $size items""")
    getBatchDrivers(1, size, 500)
  }

  def getBatchDrivers(n: Int, m: Int, step: Int): List[(Int, Int)] = {
    if ((m - n) < step) {
      List((n, m))
    } else {
      val x = ((n to m by step).sliding(2).map(x => (x(0), x(1) - 1))).toList
      x :+ (x.last._2 + 1, m)
    }
  }

  def importEuropeanRoads(conversionDatabase: DatabaseDef, vvhHost: String) = {
    val roads = conversionDatabase.withDynSession {
      sql"""select link_id, eur_nro from eurooppatienumero""".as[(Long, String)].list
    }

    val roadsByLinkId = roads.foldLeft(Map.empty[Long, (Long, String)]) { (m, road) => m + (road._1 -> road) }

    val vvhClient = new VVHClient(vvhHost)
    val vvhLinks = vvhClient.roadLinkData.fetchByLinkIds(roadsByLinkId.keySet)
    val linksByLinkId = vvhLinks.foldLeft(Map.empty[Long, VVHRoadlink]) { (m, link) => m + (link.linkId -> link) }

    val roadsWithLinks = roads.map { road => (road, linksByLinkId.get(road._1)) }

    OracleDatabase.withDynTransaction {
      val assetPS = dynamicSession.prepareStatement("insert into asset (id, asset_type_id, floating, CREATED_DATE, CREATED_BY) values (?, ?, ?, SYSDATE, 'dr1_conversion')")
      val propertyPS = dynamicSession.prepareStatement("insert into text_property_value (id, asset_id, property_id, value_fi) values (?, ?, ?, ?)")
      val lrmPositionPS = dynamicSession.prepareStatement("insert into lrm_position (ID, link_id, SIDE_CODE, start_measure, end_measure) values (?, ?, ?, ?, ?)")
      val assetLinkPS = dynamicSession.prepareStatement("insert into asset_link (asset_id, position_id) values (?, ?)")

      val propertyId = Queries.getPropertyIdByPublicId("eurooppatienumero")

      roadsWithLinks.foreach { case ((linkId, eRoad), link) =>
        val assetId = Sequences.nextPrimaryKeySeqValue

        assetPS.setLong(1, assetId)
        assetPS.setInt(2, 260)
        assetPS.setBoolean(3, link.isEmpty)
        assetPS.addBatch()

        val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue

        lrmPositionPS.setLong(1, lrmPositionId)
        lrmPositionPS.setLong(2, linkId)
        lrmPositionPS.setInt(3, SideCode.BothDirections.value)
        lrmPositionPS.setDouble(4, 0)
        lrmPositionPS.setDouble(5, link.map(_.geometry).map(GeometryUtils.geometryLength).getOrElse(0))
        lrmPositionPS.addBatch()

        assetLinkPS.setLong(1, assetId)
        assetLinkPS.setLong(2, lrmPositionId)
        assetLinkPS.addBatch()

        propertyPS.setLong(1, Sequences.nextPrimaryKeySeqValue)
        propertyPS.setLong(2, assetId)
        propertyPS.setLong(3, propertyId)
        propertyPS.setString(4, eRoad)
        propertyPS.addBatch()
      }

      assetPS.executeBatch()
      lrmPositionPS.executeBatch()
      assetLinkPS.executeBatch()
      propertyPS.executeBatch()

      assetPS.close()
      assetLinkPS.close()
      lrmPositionPS.close()
      propertyPS.close()
    }
  }

  def importProhibitions(conversionDatabase: DatabaseDef, vvhServiceHost: String) = {
    val conversionTypeId = 29
    val exceptionTypeId = 1
    val vvhClient = new VVHClient(vvhServiceHost)
    val typeId = 190

    println("*** Fetching prohibitions from conversion database")
    val startTime = DateTime.now()

    val prohibitions = conversionDatabase.withDynSession {
      sql"""
          select s.segm_id, t.link_id, s.alkum, s.loppum, t.kunta_nro, s.arvo, s.puoli, s.aika
          from segments s
          join tielinkki_ctas t on s.tielinkki_id = t.dr1_id
          where s.tyyppi = $conversionTypeId and s.kaista is null
       """.as[(Long, Long, Double, Double, Int, Int, Int, Option[String])].list
    }

    val exceptions = conversionDatabase.withDynSession {
      sql"""
          select s.segm_id, t.link_id, s.arvo, s.puoli
          from segments s
          join tielinkki_ctas t on s.tielinkki_id = t.dr1_id
          where s.tyyppi = $exceptionTypeId and s.kaista is null
       """.as[(Long, Long, Int, Int)].list
    }

    println(s"*** Fetched ${prohibitions.length} prohibitions from conversion database in ${humanReadableDurationSince(startTime)}")

    val roadLinks = vvhClient.roadLinkData.fetchByLinkIds(prohibitions.map(_._2).toSet)

    val conversionResults = convertToProhibitions(prohibitions, roadLinks, exceptions)
    println(s"*** Importing ${prohibitions.length} prohibitions")

    val insertCount = OracleDatabase.withDynTransaction {
      insertProhibitions(typeId, conversionResults)
    }
    println(s"*** Persisted $insertCount linear assets in ${humanReadableDurationSince(startTime)}")
  }

  def insertProhibitions(typeId: Int, conversionResults: Seq[Either[String, PersistedLinearAsset]]): Int = {
      val assetPS = dynamicSession.prepareStatement("insert into asset (id, asset_type_id, CREATED_DATE, CREATED_BY) values (?, ?, SYSDATE, 'dr1_conversion')")
      val lrmPositionPS = dynamicSession.prepareStatement("insert into lrm_position (ID, link_id, START_MEASURE, END_MEASURE, SIDE_CODE) values (?, ?, ?, ?, ?)")
      val assetLinkPS = dynamicSession.prepareStatement("insert into asset_link (asset_id, position_id) values (?, ?)")
      val valuePS = dynamicSession.prepareStatement("insert into prohibition_value (id, asset_id, type) values (?, ?, ?)")
      val exceptionPS = dynamicSession.prepareStatement("insert into prohibition_exception (id, prohibition_value_id, type) values (?, ?, ?)")
      val validityPeriodPS = dynamicSession.prepareStatement("insert into prohibition_validity_period (id, prohibition_value_id, type, start_hour, end_hour) values (?, ?, ?, ?, ?)")

      conversionResults.foreach {
        case Right(asset) =>
          val assetId = Sequences.nextPrimaryKeySeqValue
          assetPS.setLong(1, assetId)
          assetPS.setInt(2, typeId)
          assetPS.addBatch()

          val lrmPositionId = Sequences.nextLrmPositionPrimaryKeySeqValue
          lrmPositionPS.setLong(1, lrmPositionId)
          lrmPositionPS.setLong(2, asset.linkId)
          lrmPositionPS.setDouble(3, asset.startMeasure)
          lrmPositionPS.setDouble(4, asset.endMeasure)
          lrmPositionPS.setInt(5, asset.sideCode)
          lrmPositionPS.addBatch()

          assetLinkPS.setLong(1, assetId)
          assetLinkPS.setLong(2, lrmPositionId)
          assetLinkPS.addBatch()

          val value: Prohibitions = asset.value.get.asInstanceOf[Prohibitions]
          value.prohibitions.foreach { prohibitionValue =>
            val valueId = Sequences.nextPrimaryKeySeqValue
            valuePS.setLong(1, valueId)
            valuePS.setLong(2, assetId)
            valuePS.setLong(3, prohibitionValue.typeId)
            valuePS.addBatch()
            prohibitionValue.exceptions.foreach { exceptionType =>
              val exceptionId = Sequences.nextPrimaryKeySeqValue
              exceptionPS.setLong(1, exceptionId)
              exceptionPS.setLong(2, valueId)
              exceptionPS.setInt(3, exceptionType)
              exceptionPS.addBatch()
            }
            prohibitionValue.validityPeriods.foreach { validityPeriod =>
              val validityPeriodId = Sequences.nextPrimaryKeySeqValue
              validityPeriodPS.setLong(1, validityPeriodId)
              validityPeriodPS.setLong(2, valueId)
              validityPeriodPS.setInt(3, validityPeriod.days.value)
              validityPeriodPS.setInt(4, validityPeriod.startHour)
              validityPeriodPS.setInt(5, validityPeriod.endHour)
              validityPeriodPS.addBatch()
            }
          }
        case Left(validationError) => println(s"*** $validationError")
      }

      val executedAssetInserts = assetPS.executeBatch().length
      lrmPositionPS.executeBatch()
      assetLinkPS.executeBatch()
      valuePS.executeBatch()
      exceptionPS.executeBatch()
      validityPeriodPS.executeBatch()


      assetPS.close()
      lrmPositionPS.close()
      assetLinkPS.close()
      valuePS.close()
      exceptionPS.close()
      validityPeriodPS.close()
      executedAssetInserts
  }

  private def expandSegments(segments: Seq[(Long, Long, Double, Double, Int, Int, Int, Option[String])], exceptionSideCodes: Seq[Int]): Seq[(Long, Long, Double, Double, Int, Int, Int, Option[String])] = {
    if (segments.forall(_._7 == 1) && exceptionSideCodes.forall(_ == 1)) segments
    else {
      val (bothSided, oneSided) = segments.partition(_._7 == 1)
      val splitSegments = bothSided.flatMap { x => Seq(x.copy(_7 = 2), x.copy(_7 = 3)) }
      splitSegments ++ oneSided
    }
  }

  def expandExceptions(exceptions: Seq[(Long, Long, Int, Int)], prohibitionSideCodes: Seq[(Int)]) = {
    if (exceptions.forall(_._4 == 1) && prohibitionSideCodes.forall(_ == 1)) exceptions
    else {
      val (bothSided, oneSided) = exceptions.partition(_._4 == 1)
      val splitExceptions = bothSided.flatMap { x => Seq(x.copy(_4 = 2), x.copy(_4 = 3)) }
      splitExceptions ++ oneSided
    }
  }

  private def parseProhibitionValues(segments: Seq[(Long, Long, Double, Double, Int, Int, Int, Option[String])], exceptions: Seq[(Long, Long, Int, Int)], linkId: Long, sideCode: Int): Seq[Either[String, ProhibitionValue]] = {
    val timeDomainParser = new TimeDomainParser
    segments.map { segment =>
      val exceptionsForProhibition = exceptions.filter { z => z._2 == linkId && z._4 == sideCode }.map(_._3).toSet

      segment._8 match {
        case None => Right(ProhibitionValue(segment._6, Set.empty, exceptionsForProhibition))
        case Some(timeDomainString) =>
          timeDomainParser.parse(timeDomainString) match {
            case Left(err) => Left(s"${err}. Dropped prohibition ${segment._1}.")
            case Right(periods) => Right(ProhibitionValue(segment._6, periods.toSet, exceptionsForProhibition))
          }
      }
    }
  }

  def convertToProhibitions(prohibitionSegments: Seq[(Long, Long, Double, Double, Int, Int, Int, Option[String])], roadLinks: Seq[VVHRoadlink], exceptions: Seq[(Long, Long, Int, Int)]): Seq[Either[String, PersistedLinearAsset]] = {
    def hasInvalidExceptionType(exception: (Long, Long, Int, Int)): Boolean = {
      !Set(21, 22, 10, 9, 27, 5, 8, 7, 6, 4, 15, 19, 13, 14, 24, 25).contains(exception._3)
    }

    def hasInvalidProhibitionType(prohibition: (Long, Long, Double, Double, Int, Int, Int, Option[String])): Boolean = {
      !Set(3, 2, 23, 12, 11, 26, 10, 9, 27, 5, 8, 7, 6, 4, 15, 19, 13, 14, 24, 25).contains(prohibition._6)
    }
    val (segmentsWithRoadLink, segmentsWithoutRoadLink) = prohibitionSegments.partition { s => roadLinks.exists(_.linkId == s._2) }
    val (segmentsOfInvalidType, validSegments) = segmentsWithRoadLink.partition { s => hasInvalidProhibitionType(s) }
    val segmentsByLinkId = validSegments.groupBy(_._2)
    val (exceptionsWithProhibition, exceptionsWithoutProhibition) = exceptions.partition { x => segmentsByLinkId.keySet.contains(x._2) }
    val (exceptionWithInvalidCode, validExceptions) = exceptionsWithProhibition.partition { x => hasInvalidExceptionType(x) }

    segmentsByLinkId.flatMap { case (linkId, segments) =>
      val roadLinkLength = GeometryUtils.geometryLength(roadLinks.find(_.linkId == linkId).get.geometry)
      val expandedSegments = expandSegments(segments, validExceptions.filter(_._2 == linkId).map(_._4))
      val expandedExceptions = expandExceptions(validExceptions.filter(_._2 == linkId), segments.map(_._7))
      val roadLinkSource = roadLinks.find(_.linkId == linkId).get.linkSource

      expandedSegments.groupBy(_._7).flatMap { case (sideCode, segmentsPerSide) =>
        val prohibitionResults = parseProhibitionValues(segmentsPerSide, expandedExceptions, linkId, sideCode)
        val linearAssets = prohibitionResults.filter(_.isRight).map(_.right.get) match {
          case Nil => Nil
          case prohibitionValues => Seq(Right(PersistedLinearAsset(0l, linkId, sideCode, Some(Prohibitions(prohibitionValues)), 0.0, roadLinkLength, None, None, None, None, false, 190, 0, None, roadLinkSource, None, None)))
        }
        val parseErrors = prohibitionResults.filter(_.isLeft).map(_.left.get).map(Left(_))
        linearAssets ++ parseErrors
      }
    }.toSeq ++
      segmentsWithoutRoadLink.map { s => Left(s"No VVH road link found for mml id ${s._2}. ${s._1} dropped.") } ++
      segmentsOfInvalidType.map { s => Left(s"Invalid type for prohibition. ${s._1} dropped.") } ++
      exceptionsWithoutProhibition.map { ex => Left(s"No prohibition found on mml id ${ex._2}. Dropped exception ${ex._1}.")} ++
      exceptionWithInvalidCode.map { ex => Left(s"Invalid exception. Dropped exception ${ex._1}.")}
  }


  def getTypeProperties = {
    OracleDatabase.withDynSession {
      val shelterTypePropertyId = sql"select p.id from property p where p.public_id = 'katos'".as[Long].first
      val accessibilityPropertyId = sql"select p.id from property p where p.public_id = 'esteettomyys_liikuntarajoitteiselle'".as[Long].first
      val administratorPropertyId = sql"select p.id from property p where p.public_id = 'tietojen_yllapitaja'".as[Long].first
      val busStopTypePropertyId = sql"select p.id from property p where p.public_id = 'pysakin_tyyppi'".as[Long].first
      val busStopLiViPropertyId = sql"select p.id from property p where p.public_id = 'yllapitajan_koodi'".as[Long].first
      val busStopAssetTypeId = sql"select id from asset_type where name = 'Bussipysäkit'".as[Long].first
      PropertyWrapper(shelterTypePropertyId, accessibilityPropertyId, administratorPropertyId,
                      busStopAssetTypeId, busStopTypePropertyId, busStopLiViPropertyId)
    }
  }

  def adjustToNewDigitization(vvhHost: String) = {
    val vvhClient = new VVHClient(vvhHost)
    val municipalities = OracleDatabase.withDynSession { Queries.getMunicipalities }
    val processedLinkIds = mutable.Set[Long]()

    withDynTransaction {
      municipalities.foreach { municipalityCode =>
        val startTime = DateTime.now()

        println(s"*** Fetching from VVH with municipality: $municipalityCode")

        val flippedLinks = vvhClient.roadLinkData.fetchByMunicipality(municipalityCode)
          .filter(isHereFlipped)
          .filterNot(link => processedLinkIds.contains(link.linkId))

        var updatedCount = MassQuery.withIds(flippedLinks.map(_.linkId).toSet) { idTableName =>
          sqlu"""
            update lrm_position pos
            set pos.side_code = 5 - pos.side_code
            where exists(select * from #$idTableName i where i.id = pos.link_id)
            and pos.side_code in (2, 3)
          """.first +
          sqlu"""
            update traffic_direction td
            set td.traffic_direction = 7 - td.traffic_direction
            where exists(select id from #$idTableName i where i.id = td.link_id)
            and td.traffic_direction in (3, 4)
          """.first +
          sqlu"""
            update asset a
            set a.bearing = mod((a.bearing + 180), 360)
            where a.id in (select al.asset_id from asset_link al
                           join lrm_position pos on al.position_id = pos.id
                           join #$idTableName i on i.id = pos.link_id)
            and a.bearing is not null
            and a.asset_type_id in (10, 240)
          """.first
        }

        flippedLinks.foreach { link =>
          val length = GeometryUtils.geometryLength(link.geometry)

          updatedCount += sqlu"""
              update lrm_position
              set end_measure = greatest(0, ${length} - COALESCE(start_measure, 0)),
                  start_measure = greatest(0, ${length} - COALESCE(end_measure, start_measure, 0))
              where link_id = ${link.linkId}
            """.first
        }

        processedLinkIds ++= flippedLinks.map(_.linkId)

        println(s"*** Made $updatedCount updates in ${humanReadableDurationSince(startTime)}")
      }
    }
  }

  private def isHereFlipped(roadLink: VVHRoadlink) = {
    val NotFlipped = 0
    val Flipped = 1
    roadLink.attributes.getOrElse("MTKHEREFLIP", NotFlipped).asInstanceOf[BigInt] == Flipped
  }

  def insertTextPropertyData(propertyId: Long, assetId: Long, text:String) {
    sqlu"""
      insert into text_property_value(id, property_id, asset_id, value_fi, value_sv, created_by)
      values (primary_key_seq.nextval, $propertyId, $assetId, $text, ' ', $Modifier)
    """.execute
  }

def insertNumberPropertyData(propertyId: Long, assetId: Long, value:Int) {
    sqlu"""
      insert into number_property_value(id, property_id, asset_id, value)
      values (primary_key_seq.nextval, $propertyId, $assetId, $value)
    """.execute
  }

  def updateNumberPropertyData(propertyId: Long, assetId: Long, value: Int) {
    sqlu"""
       update number_property_value set value = $value
       where asset_id = $assetId and property_id = $propertyId
    """.execute
  }

  def updateFloating(id: Long, floating: Boolean) {
    sqlu"""
         update asset set floating = $floating where id = $id
    """.execute
  }

  def insertMultipleChoiceValue(propertyId: Long, assetId: Long, value: Int) {
    sqlu"""
      insert into multiple_choice_value(id, property_id, asset_id, enumerated_value_id, modified_by)
      values (primary_key_seq.nextval, $propertyId, $assetId,
        (select id from enumerated_value where value = $value and property_id = $propertyId), $Modifier)
    """.execute
  }

  def insertSingleChoiceValue(propertyId: Long, assetId: Long, value: Int) {
    sqlu"""
      insert into single_choice_value(property_id, asset_id, enumerated_value_id, modified_by)
      values ($propertyId, $assetId, (select id from enumerated_value where value = $value and property_id = $propertyId), $Modifier)
    """.execute
  }

  def getPropertyTypeByPublicId(publicId : String): Long ={
    sql"select p.id from property p where p.public_id = $publicId".as[Long].first
  }

  def getExistingLiviIds(): Seq[String] ={
    withDynSession {
      sql"""
        select  tv.value_fi
        from property p
        inner join text_property_value tv on tv.property_id = p.id
        where public_id = 'yllapitajan_koodi'
      """.as[String].list
    }
  }

  def getFloatingAssetsWithNumberPropertyValue(assetTypeId: Long, publicId: String, municipality: Int) : Seq[(Long, Long, Point, Double, Option[Int])] = {
    implicit val getPoint = GetResult(r => bytesToPoint(r.nextBytes))
    sql"""
      select a.id, lrm.link_id, geometry, lrm.start_measure, np.value
      from
      asset a
      join asset_link al on al.asset_id = a.id
      join lrm_position lrm on al.position_id  = lrm.id
      join property p on a.asset_type_id = p.asset_type_id and p.public_id = $publicId
      left join number_property_value np on np.asset_id = a.id and np.property_id = p.id and p.property_type = 'read_only_number'
      where a.asset_type_id = $assetTypeId and a.floating = 1 and a.municipality_code = $municipality
      """.as[(Long, Long, Point, Double, Option[Int])].list
  }

  def getNonFloatingAssetsWithNumberPropertyValue(assetTypeId: Long, publicId: String, municipality: Int): Seq[(Long, Long, Option[Int])] ={
    sql"""
      select a.id, lrm.link_id, np.value
      from
      asset a
      join asset_link al on al.asset_id = a.id
      join lrm_position lrm on al.position_id  = lrm.id
      join property p on a.asset_type_id = p.asset_type_id and p.public_id = $publicId
      left join number_property_value np on np.asset_id = a.id and np.property_id = p.id and p.property_type = 'read_only_number'
      where a.asset_type_id = $assetTypeId and a.floating = 0 and a.municipality_code = $municipality
      """.as[(Long, Long, Option[Int])].list
  }

  def importRoadAddressData(conversionDatabase: DatabaseDef, vvhClient: VVHClient) = {
    def filler(lrmPos: Seq[(Long, Long, Double, Double)], length: Double) = {
      val filled = lrmPos.exists(x => x._4 >= length)
      filled match {
        case true => lrmPos
        case false =>
          val maxEnd = lrmPos.map(_._4).max
          val (fixthese, good) = lrmPos.partition(_._4 == maxEnd)
          fixthese.map {
            case (xid, xlinkId, xstartM, _) => (xid, xlinkId, xstartM, length)
          } ++ good
      }
    }
    def cutter(lrmPos: Seq[(Long, Long, Double, Double)], length: Double) = {
      val (good, bad) = lrmPos.partition(_._4 < length)
      good ++ bad.map {
        case (id, linkId, startM, endM) => (id, linkId, Math.min(startM, length), Math.min(endM, length))
      }
    }
    val roads = conversionDatabase.withDynSession {
      sql"""select linkid, alku, loppu,
            tie, aosa, ajr,
            ely, tietyyppi,
            jatkuu, aet, let,
            TO_CHAR(alkupvm, 'YYYY-MM-DD'), TO_CHAR(loppupvm, 'YYYY-MM-DD'),
            kayttaja, TO_CHAR(COALESCE(muutospvm, rekisterointipvm), 'YYYY-MM-DD'), id
            from vvh_tieosoite_nyky""".as[(Long, Long, Long, Long, Long, Long, Long, Long, Long, Long, Long, String, Option[String], String, String, Long)].list
    }

    print(s"${DateTime.now()} - ")
    println("Read %d rows from conversion database".format(roads.size))
    val lrmList = roads.map(r => (r._16, r._1, r._2.toDouble, r._3.toDouble)).groupBy(_._2) // linkId -> (id, linkId, startM, endM)
    val addressList = roads.map(r => (r._16, (r._4, r._5, r._6, r._7, r._8, r._9, r._10, r._11, r._12, r._13, r._14, r._15))).toMap

    print(s"${DateTime.now()} - ")
    println("Total of %d link ids".format(lrmList.keys.size))
    val linkIdGroups = lrmList.keys.toSet.grouped(500) // Mapping LinkId -> Id

    val linkLengths = linkIdGroups.flatMap (
      linkIds => vvhClient.roadLinkData.fetchByLinkIds(linkIds).map(roadLink => roadLink.linkId -> GeometryUtils.geometryLength(roadLink.geometry))
    ).toMap
    print(s"${DateTime.now()} - ")
    println("Read %d road links from vvh".format(linkLengths.size))

    val unFilteredLrmPositions = linkLengths.flatMap {
      case (linkId, length) => cutter(filler(lrmList.getOrElse(linkId, List()), length), length)
    }
    val lrmPositions = unFilteredLrmPositions.filterNot(x => x._3 == x._4)

    print(s"${DateTime.now()} - ")
    println("%d zero length segments removed".format(unFilteredLrmPositions.size - lrmPositions.size))

    OracleDatabase.withDynTransaction {
      sqlu"""ALTER TABLE ROAD_ADDRESS DISABLE ALL TRIGGERS""".execute
      sqlu"""DELETE FROM ROAD_ADDRESS""".execute
      sqlu"""DELETE FROM LRM_POSITION WHERE NOT EXISTS (SELECT POSITION_ID FROM ASSET_LINK WHERE POSITION_ID=LRM_POSITION.ID)""".execute
      println(s"${DateTime.now()} - Old address data removed")
      val lrmPositionPS = dynamicSession.prepareStatement("insert into lrm_position (ID, link_id, SIDE_CODE, start_measure, end_measure) values (?, ?, ?, ?, ?)")
      val addressPS = dynamicSession.prepareStatement("insert into ROAD_ADDRESS (id, lrm_position_id, road_number, road_part_number, " +
        "track_code, ely, road_type, discontinuity, START_ADDR_M, END_ADDR_M, start_date, end_date, created_by, " +
        "created_date) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TO_DATE(?, 'YYYY-MM-DD'), TO_DATE(?, 'YYYY-MM-DD'), ?, TO_DATE(?, 'YYYY-MM-DD'))")
      lrmPositions.foreach { case (id, linkId, startM, endM) =>
        val lrmId = Sequences.nextLrmPositionPrimaryKeySeqValue
        val addressId = Sequences.nextViitePrimaryKeySeqValue
        val address = addressList.get(id).head
        val (startAddrM, endAddrM, sideCode) = address._7 < address._8 match {
          case true => (address._7, address._8, SideCode.TowardsDigitizing.value)
          case false => (address._8, address._7, SideCode.AgainstDigitizing.value)
        }
        lrmPositionPS.setLong(1, lrmId)
        lrmPositionPS.setLong(2, linkId)
        lrmPositionPS.setLong(3, sideCode)
        lrmPositionPS.setDouble(4, startM)
        lrmPositionPS.setDouble(5, endM)
        lrmPositionPS.addBatch()
        addressPS.setLong(1, addressId)
        addressPS.setLong(2, lrmId)
        addressPS.setLong(3, address._1)
        addressPS.setLong(4, address._2)
        addressPS.setLong(5, address._3)
        addressPS.setLong(6, address._4)
        addressPS.setLong(7, address._5)
        addressPS.setLong(8, address._6)
        addressPS.setLong(9, startAddrM)
        addressPS.setLong(10, endAddrM)
        addressPS.setString(11, address._9)
        addressPS.setString(12, address._10.getOrElse(""))
        addressPS.setString(13, address._11)
        addressPS.setString(14, address._12)
        addressPS.addBatch()
      }
      lrmPositionPS.executeBatch()
      println(s"${DateTime.now()} - LRM Positions saved")
      addressPS.executeBatch()
      println(s"${DateTime.now()} - Road addresses saved")
      lrmPositionPS.close()
      addressPS.close()
    }

    println(s"${DateTime.now()} - Updating calibration point information")

    OracleDatabase.withDynTransaction {
      // both dates are open-ended or there is overlap (checked with inverse logic)
      sqlu"""UPDATE ROAD_ADDRESS
        SET CALIBRATION_POINTS = 1
        WHERE NOT EXISTS(SELECT 1 FROM ROAD_ADDRESS RA2 WHERE RA2.ID != ROAD_ADDRESS.ID AND
        RA2.ROAD_NUMBER = ROAD_ADDRESS.ROAD_NUMBER AND
        RA2.ROAD_PART_NUMBER = ROAD_ADDRESS.ROAD_PART_NUMBER AND
        RA2.START_ADDR_M = ROAD_ADDRESS.END_ADDR_M AND
        RA2.TRACK_CODE = ROAD_ADDRESS.TRACK_CODE AND
        (ROAD_ADDRESS.END_DATE IS NULL AND RA2.END_DATE IS NULL OR
        NOT (RA2.END_DATE < ROAD_ADDRESS.START_DATE OR RA2.START_DATE > ROAD_ADDRESS.END_DATE)))""".execute
      sqlu"""UPDATE ROAD_ADDRESS
        SET CALIBRATION_POINTS = CALIBRATION_POINTS + 2
          WHERE
            START_ADDR_M = 0 OR
            NOT EXISTS(SELECT 1 FROM ROAD_ADDRESS RA2 WHERE RA2.ID != ROAD_ADDRESS.ID AND
              RA2.ROAD_NUMBER = ROAD_ADDRESS.ROAD_NUMBER AND
              RA2.ROAD_PART_NUMBER = ROAD_ADDRESS.ROAD_PART_NUMBER AND
              RA2.END_ADDR_M = ROAD_ADDRESS.START_ADDR_M AND
              RA2.TRACK_CODE = ROAD_ADDRESS.TRACK_CODE AND
              (ROAD_ADDRESS.END_DATE IS NULL AND RA2.END_DATE IS NULL OR
                NOT (RA2.END_DATE < ROAD_ADDRESS.START_DATE OR RA2.START_DATE > ROAD_ADDRESS.END_DATE)
              )
            )""".execute
      sqlu"""ALTER TABLE ROAD_ADDRESS ENABLE ALL TRIGGERS""".execute
    }
  }

  private[this] def initDataSource: DataSource = {
    Class.forName("oracle.jdbc.driver.OracleDriver")
    val cfg = new BoneCPConfig(localProperties)
    new BoneCPDataSource(cfg)
  }

  lazy val localProperties: Properties = {
    val props = new Properties()
    try {
      props.load(getClass.getResourceAsStream("/bonecp.properties"))
    } catch {
      case e: Exception => throw new RuntimeException("Can't load local.properties for env: " + System.getProperty("env"), e)
    }
    props
  }


    /**
      * Gets municipalitycodes of stops which have updateable masstransitstops
      * returns list of int
      */
    def getMTStopsMunicipalitycodeBothMissing(idAddressFi: Int, idAddressSe: Int) =
    {

      sql"""
              Select distinct MUNICIPALITY_CODE
                            From Asset
                   WHERE
                   Asset_Type_ID=10
                    AND
                    (
                   (ID NOT IN (SELECT ASSET_ID FROM Text_property_value WHERE PROPERTY_ID = $idAddressSe OR PROPERTY_ID = $idAddressFi ))
                    )
               ORDER BY MUNICIPALITY_CODE DESC""".as[(Int)].list
    }

  /**
    * Adds text property to TEXT_PROPERTY_VALUE table. Created for getMassTransitStopAddressesFromVVH
    * to create address information for missing mass transit stops
    *
    * @param assetId
    * @param propertyVal
    * @param vname
    */
    def createTextPropertyValue(assetId: Long, propertyVal: Int, vname : String) = {
      sqlu"""
        INSERT INTO TEXT_PROPERTY_VALUE(ID,ASSET_ID,PROPERTY_ID,VALUE_FI,CREATED_BY)
        VALUES(primary_key_seq.nextval,$assetId,$propertyVal,$vname,'vvh_generated')
      """.execute
    }

    /**
    * Adds text property to TEXT_PROPERTY_VALUE table.
    *
    * @param assetId
    * @param propertyVal
    * @param vname
    */
    def createTextPropertyValue(assetId: Long, propertyVal: Long, vname : String, modifiedBy: String) = {
      sqlu"""
          INSERT INTO TEXT_PROPERTY_VALUE(ID,ASSET_ID,PROPERTY_ID,VALUE_FI,CREATED_BY)
          VALUES(primary_key_seq.nextval,$assetId,$propertyVal,$vname,$modifiedBy)
        """.execute
    }

    /**
      * Add or update text property to TEXT_PROPERTY_VALUE table.
      *
      * @param assetId
      * @param propertyVal
      * @param vname
      */
    def createOrUpdateTextPropertyValue(assetId: Long, propertyVal: Long, vname : String, modifiedBy: String) = {
      val notExist = sql"""select id from text_property_value where asset_id = $assetId and property_id = $propertyVal""".as[Long].firstOption.isEmpty
      if(notExist){
        sqlu"""
          INSERT INTO TEXT_PROPERTY_VALUE(ID,ASSET_ID,PROPERTY_ID,VALUE_FI,CREATED_BY)
          VALUES(primary_key_seq.nextval,$assetId,$propertyVal,$vname,$modifiedBy)
        """.execute
      }else{
        sqlu"update text_property_value set value_fi = $vname, modified_date = sysdate, modified_by = $modifiedBy where asset_id = $assetId and property_id = $propertyVal".execute
      }
    }
}

