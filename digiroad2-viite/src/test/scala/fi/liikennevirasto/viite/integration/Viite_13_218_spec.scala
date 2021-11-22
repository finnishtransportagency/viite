package fi.liikennevirasto.viite

import fi.liikennevirasto.GeometryUtils
import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.asset.AdministrativeClass.State
import fi.liikennevirasto.digiroad2.asset.ConstructionType.InUse
import fi.liikennevirasto.digiroad2.asset.LinkGeomSource.FrozenLinkInterface
import fi.liikennevirasto.digiroad2.asset.SideCode.{AgainstDigitizing, TowardsDigitizing}
import fi.liikennevirasto.digiroad2.asset._
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.dao.Sequences
import fi.liikennevirasto.digiroad2.linearasset.RoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.{Track, ViiteProperties}
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.RoadAddressLinkLike
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import fi.liikennevirasto.viite.util._
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession
import slick.jdbc.StaticQuery.interpolation

import scala.collection.immutable.HashMap
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps

class Viite_13_218_spec extends FunSuite with Matchers with BeforeAndAfter {
  val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockDefaultSectionCalculatorStrategy: DefaultSectionCalculatorStrategy = MockitoSugar.mock[DefaultSectionCalculatorStrategy]
  val mockVVHClient: VVHClient = MockitoSugar.mock[VVHClient]
  val mockRoadAddressService: RoadAddressService = MockitoSugar.mock[RoadAddressService]
  val mockRoadwayAddressMapper: RoadwayAddressMapper = MockitoSugar.mock[RoadwayAddressMapper]
  val mockNodesAndJunctionsService: NodesAndJunctionsService = MockitoSugar.mock[NodesAndJunctionsService]
  val mockEventBus: DigiroadEventBus = MockitoSugar.mock[DigiroadEventBus]
  val projectDAO = new ProjectDAO
  val projectLinkDAO = new ProjectLinkDAO
  val roadwayDAO = new RoadwayDAO
  val roadNetworkDAO = new RoadNetworkDAO
  val linearLocationDAO = new LinearLocationDAO
  val roadwayPointDAO = new RoadwayPointDAO
  val nodeDAO = new NodeDAO
  val nodePointDAO = new NodePointDAO
  val junctionPointDAO = new JunctionPointDAO
  val roadwayChangesDAO = new RoadwayChangesDAO
  val projectReservedPartDAO = new ProjectReservedPartDAO
  val roadwayAddressMapper               = new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  val projectValidator: ProjectValidator = new ProjectValidator {
    override val roadAddressService: RoadAddressService = mockRoadAddressService
  }

  /* db digiroad2Context.scala*/
  val junctionDAO_db: JunctionDAO = {
    new JunctionDAO
  }
  val nodesAndJunctionsService_db : NodesAndJunctionsService = {
    new NodesAndJunctionsService(roadwayDAO, roadwayPointDAO, linearLocationDAO, nodeDAO, nodePointDAO, junctionDAO_db, junctionPointDAO, roadwayChangesDAO)
  }
  val vvhClient_db: VVHClient = {
    new VVHClient(ViiteProperties.vvhRestApiEndPoint)
  }

  val eventbus_db: DigiroadEventBus = {
    Class.forName(ViiteProperties.eventBus).newInstance().asInstanceOf[DigiroadEventBus]
  }
  val roadLinkService_db: RoadLinkService = {
    new RoadLinkService(vvhClient_db, eventbus_db, new JsonSerializer, true)
  }
  val roadAddressService_db: RoadAddressService = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO,
    roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, roadwayAddressMapper, eventbus_db, true)

  val projectService_db: ProjectService = new ProjectService(roadAddressService_db, mockRoadLinkService, nodesAndJunctionsService_db, roadwayDAO,
    roadwayPointDAO, linearLocationDAO, projectDAO, projectLinkDAO,
    nodeDAO, nodePointDAO, junctionPointDAO, projectReservedPartDAO, roadwayChangesDAO,
    roadwayAddressMapper, eventbus_db, frozenTimeVVHAPIServiceEnabled = true){
    override def withDynSession[T](f: => T): T = f

    override def withDynTransaction[T](f: => T): T = f
  }

  /* ---db */

  after {
    reset(mockRoadLinkService)
  }

//  def withDynTransaction[T](f: => T): T = OracleDatabase.withDynTransaction(f)
//
//  def runWithRollback[T](f: => T): T = {
//    Database.forDataSource(OracleDatabase.ds).withDynTransaction {
//      val t = f
//      dynamicSession.rollback()
//  t
//}
//  }
   def withDynTransaction[T](f: => T): T = PostGISDatabase.withDynTransaction(f)

  def runWithRollback[T](f: => T): T = {
    Database.forDataSource(PostGISDatabase.ds).withDynTransaction {
      val t = f
      dynamicSession.rollback()
      t
    }
  }

  private def addressToRoadLink(ral: RoadAddress): RoadLink = {
    val geomLength = GeometryUtils.geometryLength(ral.geometry)
    val adminClass = ral.administrativeClass match {
      case AdministrativeClass.State => AdministrativeClass.apply(1)
      case AdministrativeClass.Municipality => AdministrativeClass.apply(2)
      case AdministrativeClass.Private => AdministrativeClass.apply(3)
      case _ => AdministrativeClass.apply(99)
    }
    RoadLink(ral.linkId, ral.geometry, geomLength, adminClass, 1,
      extractTrafficDirection(ral.sideCode, ral.track), LinkType.apply(99), Option(ral.startDate.toString), ral.createdBy, Map(
        "MUNICIPALITYCODE" -> BigInt(749), "VERTICALLEVEL" -> BigInt(1), "SURFACETYPE" -> BigInt(1),
        "ROADNUMBER" -> BigInt(ral.roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)),
      ConstructionType.InUse, ral.linkGeomSource)
  }

  private def extractTrafficDirection(sideCode: SideCode, track: Track): TrafficDirection = {
    (sideCode, track) match {
      case (_, Track.Combined) => TrafficDirection.BothDirections
      case (TowardsDigitizing, Track.RightSide) => TrafficDirection.TowardsDigitizing
      case (TowardsDigitizing, Track.LeftSide) => TrafficDirection.AgainstDigitizing
      case (AgainstDigitizing, Track.RightSide) => TrafficDirection.AgainstDigitizing
      case (AgainstDigitizing, Track.LeftSide) => TrafficDirection.TowardsDigitizing
      case (_, _) => TrafficDirection.UnknownDirection
    }
  }

  private def toRoadLink(ral: RoadAddressLinkLike): RoadLink = {
    RoadLink(ral.linkId, ral.geometry, ral.length, ral.administrativeClass, 1,
      extractTrafficDirection(ral.sideCode, Track.apply(ral.trackCode.toInt)), ral.linkType, ral.modifiedAt, ral.modifiedBy, Map(
        "MUNICIPALITYCODE" -> BigInt(749), "VERTICALLEVEL" -> BigInt(1), "SURFACETYPE" -> BigInt(1),
        "ROADNUMBER" -> BigInt(ral.roadNumber), "ROADPARTNUMBER" -> BigInt(ral.roadPartNumber)),
      ral.constructionType, ral.roadLinkSource)
  }

  /* update errors*/
  def errorPartsToApi(errorParts: projectService_db.projectValidator.ValidationErrorDetails): Map[String, Any] = {
    Map("ids" -> errorParts.affectedIds,
      "errorCode" -> errorParts.validationError.value,
      "errorMessage" -> errorParts.validationError.message,
      "info" -> errorParts.optionalInformation,
      "coordinates" -> errorParts.coordinates,
      "priority" -> errorParts.validationError.priority
    )
  }
  def projectFormedPartToApi(projectId: Option[Long] = None)(formedRoadPart: ProjectReservedPart): Map[String, Any] = {
    Map("roadNumber" -> formedRoadPart.roadNumber,
      "roadPartNumber" -> formedRoadPart.roadPartNumber,
      "id" -> formedRoadPart.id,
      "currentEly" -> formedRoadPart.ely,
      "currentLength" -> formedRoadPart.addressLength,
      "currentDiscontinuity" -> formedRoadPart.discontinuity.map(_.description),
      "newEly" -> formedRoadPart.newEly,
      "newLength" -> formedRoadPart.newLength,
      "newDiscontinuity" -> formedRoadPart.newDiscontinuity.map(_.description),
      "startingLinkId" -> formedRoadPart.startingLinkId,
      "roadAddresses" -> {
        projectId match {
          case None => Seq.empty
          case _ => projectService_db.getRoadAddressesFromFormedRoadPart(formedRoadPart.roadNumber, formedRoadPart.roadPartNumber, projectId.get)
        }
      }
    )
  }
  implicit class CaseClassToString(c: AnyRef) {
    def rowValuesToString: List[List[Any]] = {
      var header = List.empty[Char]
      val fields = (List[List[(String, Any)]]() /: c.getClass.getDeclaredFields) { (a, f) =>
        f.setAccessible(true)
        var x = f.get(c)
        x = if (x.isInstanceOf[Option[AnyRef]] && x.asInstanceOf[Option[AnyRef]].isDefined) f.get(c).asInstanceOf[Option[AnyRef]].get else x
        val z = if (x.isInstanceOf[RoadwayChangeInfo]) {
          val ss   = (Map[String, Any]() /: x.asInstanceOf[RoadwayChangeInfo].source.getClass.getDeclaredFields) { (b, B) => {
            B.setAccessible(true)
            if (header.size < 11) header = header ++ B.getName
            val value    = B.get(x.asInstanceOf[RoadwayChangeInfo].source)
            val colValue = value match {
              case None => Some("")
              case _ => value
            }
            b + (B.getName -> colValue)
          }
          }.asInstanceOf[HashMap.HashTrieMap[String, Option[Any]]].toList.map(t => {
            t._1 -> (if (t._2.isDefined) t._2.get else t._2)
          })
          val sss  = if (ss.find(_._1 == "startAddressM").get._2.toString.isEmpty) ss.map(t => {
            (t._1, "")
          }) else ss
          val ssss = sss.map(t => {
            if (t._1 == "endRoadPartNumber" && t._2.toString.nonEmpty) ("length", sss.find(_._1 == "endAddressM").get._2.asInstanceOf[Long] - sss.find(_._1 == "startAddressM").get._2.asInstanceOf[Long]) else t
          })
          val s    = List(ssss.head, ssss(6), ssss(5), ssss(2), ssss(8), ssss(3), ssss(7), ssss(1), ssss(4))

          val tt   = (Map[String, Any]() /: x.asInstanceOf[RoadwayChangeInfo].target.getClass.getDeclaredFields) { (b, B) => {
            B.setAccessible(true)
            val value    = B.get(x.asInstanceOf[RoadwayChangeInfo].target)
            val colValue = value match {
              case None => Some("")
              case _ => value
            }
            b + (B.getName -> colValue)
          }
          }.asInstanceOf[HashMap.HashTrieMap[String, Option[Any]]].toList.map(t => {
            t._1 -> (if (t._2.isDefined) t._2.get else t._2)
          })
          val ttt  = if (tt.find(_._1 == "startAddressM").get._2.toString.isEmpty) tt.map(t => {
            (t._1, "")
          }) else tt
          val tttt = ttt.map(t => {
            if (t._1 == "endRoadPartNumber" && t._2.toString.nonEmpty)
              ("length", ttt.find(_._1 == "endAddressM").get._2.asInstanceOf[Long] - ttt.find(_._1 == "startAddressM").get._2.asInstanceOf[Long])
            else
              t
          })
          val t    = List(tttt.head, tttt(6), tttt(5), tttt(2), tttt(8), tttt(3), tttt(7), tttt(1), tttt(4))

          val T: Map[String, Any] = Map("changeType" -> x.asInstanceOf[RoadwayChangeInfo].changeType)
          val R: Map[String, Any] = Map("reversed" -> x.asInstanceOf[RoadwayChangeInfo].reversed)

          List(T.toList ++ s ++ R.toList, Map("" -> "").toList ++ t ++ Map("" -> "").toList)
        } else List()
        if (z.isEmpty) a else z
      }
      fields.map(_.map(_._2.toString))
    }

    def headerFieldsToString: String = {
      val fields = (Map[String, Any]() /: c.getClass.getDeclaredFields) { (a, f) =>
        f.setAccessible(true)
        var x = f.get(c)
        x = if (x.isInstanceOf[Option[AnyRef]] && x.asInstanceOf[Option[AnyRef]].isDefined) f.get(c).asInstanceOf[Option[AnyRef]].get else x
        val z = if (x.isInstanceOf[RoadwayChangeInfo]) (Map[String, Any]() /: x.asInstanceOf[RoadwayChangeInfo].source.getClass.getDeclaredFields) { (b, B) => {
          B.setAccessible(true)
          b + (B.getName -> "")
        }
        }.asInstanceOf[HashMap.HashTrieMap[String, Option[Any]]].toList.map(t => {
          t._1 -> t._1
        }) else List()
        if (z.isEmpty) a else a ++ z
      }
      val fks    = fields.keys.toList
      s"${List(fks.head, fks(6), "length", fks(2), "roadPartNumber", fks(3), fks(7), fks(1), fks(4)).mkString(" | ")}"
    }
  }

  def formatTable(table: Seq[Seq[Any]]): String = {
    val x          = table.head.map(_.asInstanceOf[List[String]].map(_.length + 2))
    val colWidths  = x.transpose.map(_.max)
    val rows       = table.head.map(_.asInstanceOf[List[String]].zip(colWidths).map { case (item, size) => (" %-" + (size - 1) + "s").format(item) }.mkString("|", "|", "|"))
    val separator  = colWidths.map("-" * _).mkString("+", "+", "+")
    val zippedRows = rows.tail.zipWithIndex
    val valueRows  = zippedRows.tail.foldLeft(List(zippedRows.head._1)) { (a, b) => {
      if (b._2 % 2 != 0) a ++ List(b._1, separator) else a ++ List(b._1)
    }
    }
    (separator +: rows.head +: separator +: valueRows).mkString("\n")
  }

  def prettyPrintLog(roadwayChanges: List[ProjectRoadwayChange]): Unit = {
    roadwayChanges.groupBy(a => {
      (a.projectId, a.projectName, a.projectStartDate, a.ely)
    }).foreach(g => {
      val changes                                                = g._2
      val t1                                                     = changes.head.headerFieldsToString
      val headers                                                = Seq("changeType") ++ t1.split('|').toSeq ++ Seq("reversed") ++ t1.split('|').toSeq
      val C                                                      = changes.map(c => {
        c.rowValuesToString
      })
      println(formatTable(Seq(Seq(headers.take(11)) ++ C.flatten)))
    })
  }

  test("Test road_13_218") {
     { runWithRollback {
          /* Make room to db. */
//        val roadwayDeletePS = dynamicSession.prepareStatement("""DELETE FROM ROADWAY WHERE roadway_number in (6045540,126019218,126019231,126019743,126019932,126027676,148122023,148122186,148127797,148128053,283720652)""")
//        val roadwayDeleteLPS = dynamicSession.prepareStatement("""DELETE FROM linear_location WHERE link_id in (7330434,11910590,11910502,2438638,3227503,2438650,2438849,2440601,2440603,3225290,2438582,12017341,2438830,2438647,2440602,3225295,3225166,2440640,3227484,12017340,3227478,2438847,11910505,11910588,3225291,11910572,7330427,2440593,2440641,2438850,11910587,2438837,3227469,3227468,2440623,11910500,2440637,11478941,3227482,2440644,2440625,3227486,3225257,11910586,11478926,3227480,2438851,11910540,2438653,11910585,2438662,3227541,11910568,11910533,2439668,2438732,2438668,7094558,3227544,2440628)""")
//        val roadwayDeleteRPS = dynamicSession.prepareStatement("""DELETE FROM roadway_point WHERE roadway_number in (6045540,126019218,126019231,126019743,126019932,126027676,148122023,148122186,148127797,148128053,283720652)""")
//        val roadwayDeleteCPS = dynamicSession.prepareStatement("""DELETE FROM calibration_point WHERE link_id in (7330434,11910590,11910502,2438638,3227503,2438650,2438849,2440601,2440603,3225290,2438582,12017341,2438830,2438647,2440602,3225295,3225166,2440640,3227484,12017340,3227478,2438847,11910505,11910588,3225291,11910572,7330427,2440593,2440641,2438850,11910587,2438837,3227469,3227468,2440623,11910500,2440637,11478941,3227482,2440644,2440625,3227486,3225257,11910586,11478926,3227480,2438851,11910540,2438653,11910585,2438662,3227541,11910568,11910533,2439668,2438732,2438668,7094558,3227544,2440628)""")
//
//        roadwayDeletePS.executeBatch()
//        roadwayDeletePS.close()
//
//        roadwayDeleteLPS.executeBatch()
//        roadwayDeleteLPS.close()
//
//        roadwayDeleteRPS.executeBatch()
//        roadwayDeleteRPS.close()
//
//        roadwayDeleteCPS.executeBatch()
//        roadwayDeleteCPS.close()

        /* Insert data to test db. A long format..
           Junctions and nodes with points may be dropped. */
        val creator = "13_218_test_code"
        val newLinks = Seq(
          RoadLink(11910497,List(Point(512267.421,6838792.474,102.50800000000163), Point(512265.142,6838796.858,102.49899999999616), Point(512260.53,6838805.726,102.43899999999849), Point(512255.918,6838814.595,102.37399999999616), Point(512251.306,6838823.463,102.29499999999825), Point(512246.694,6838832.331,102.24300000000221), Point(512242.082,6838841.2,102.21099999999569), Point(512237.471,6838850.068,102.11199999999371), Point(512232.859,6838858.937,102.00900000000547), Point(512228.247,6838867.805,101.98600000000442), Point(512223.636,6838876.674,101.98399999999674), Point(512219.023,6838885.543,101.87600000000384), Point(512214.411,6838894.412,101.83199999999488), Point(512212.728,6838897.649,101.80999999999767), Point(512209.972,6838908.207,101.84200000000419)),129.458,State,99,TrafficDirection.AgainstDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999769025L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512267.421, y -> 6838792.474, z -> 102.50800000000163, m -> 0), Map(x -> 512265.142, y -> 6838796.858, z -> 102.49899999999616, m -> 4.94100000000617), Map(x -> 512260.53, y -> 6838805.726, z -> 102.43899999999849, m -> 14.936600000000908), Map(x -> 512255.918, y -> 6838814.595, z -> 102.37399999999616, m -> 24.933099999994738), Map(x -> 512251.306, y -> 6838823.463, z -> 102.29499999999825, m -> 34.92870000000403), Map(x -> 512246.694, y -> 6838832.331, z -> 102.24300000000221, m -> 44.924299999998766), Map(x -> 512242.082, y -> 6838841.2, z -> 102.21099999999569, m -> 54.9207000000024), Map(x -> 512237.471, y -> 6838850.068, z -> 102.11199999999371, m -> 64.91590000000724), Map(x -> 512232.859, y -> 6838858.937, z -> 102.00900000000547, m -> 74.91240000000107), Map(x -> 512228.247, y -> 6838867.805, z -> 101.98600000000442, m -> 84.90799999999581), Map(x -> 512223.636, y -> 6838876.674, z -> 101.98399999999674, m -> 94.903999999995), Map(x -> 512219.023, y -> 6838885.543, z -> 101.87600000000384, m -> 104.90089999999327), Map(x -> 512214.411, y -> 6838894.412, z -> 101.83199999999488, m -> 114.89740000000165), Map(x -> 512212.728, y -> 6838897.649, z -> 101.80999999999767, m -> 118.54580000000715), Map(x -> 512209.972, y -> 6838908.207, z -> 101.84200000000419, m -> 129.45759999999427))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512267.421 6838792.474 102.50800000000163 0, 512265.142 6838796.858 102.49899999999616 4.94100000000617, 512260.53 6838805.726 102.43899999999849 14.936600000000908, 512255.918 6838814.595 102.37399999999616 24.933099999994738, 512251.306 6838823.463 102.29499999999825 34.92870000000403, 512246.694 6838832.331 102.24300000000221 44.924299999998766, 512242.082 6838841.2 102.21099999999569 54.9207000000024, 512237.471 6838850.068 102.11199999999371 64.91590000000724, 512232.859 6838858.937 102.00900000000547 74.91240000000107, 512228.247 6838867.805 101.98600000000442 84.90799999999581, 512223.636 6838876.674 101.98399999999674 94.903999999995, 512219.023 6838885.543 101.87600000000384 104.90089999999327, 512214.411 6838894.412 101.83199999999488 114.89740000000165, 512212.728 6838897.649 101.80999999999767 118.54580000000715, 512209.972 6838908.207 101.84200000000419 129.45759999999427)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910501,List(Point(512244.316,6838851.527,102.22900000000664), Point(512240.643,6838858.47,102.17500000000291), Point(512237.21,6838864.801,102.16999999999825), Point(512233.455,6838871.881,102.1140000000014), Point(512228.734,6838880.786,102.11199999999371), Point(512226.332,6838885.298,102.00800000000163), Point(512225.179,6838887.139,102.04899999999907), Point(512219.873,6838895.609,101.94599999999627), Point(512216.978,6838900.231,101.92999999999302), Point(512214.039,6838903.695,101.8969999999972), Point(512209.972,6838908.207,101.84200000000419)),66.499,State,99,TrafficDirection.TowardsDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999769031L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512244.316, y -> 6838851.527, z -> 102.22900000000664, m -> 0), Map(x -> 512240.643, y -> 6838858.47, z -> 102.17500000000291, m -> 7.854699999996228), Map(x -> 512237.21, y -> 6838864.801, z -> 102.16999999999825, m -> 15.056599999996251), Map(x -> 512233.455, y -> 6838871.881, z -> 102.1140000000014, m -> 23.070699999996577), Map(x -> 512228.734, y -> 6838880.786, z -> 102.11199999999371, m -> 33.14969999999448), Map(x -> 512226.332, y -> 6838885.298, z -> 102.00800000000163, m -> 38.2612999999983), Map(x -> 512225.179, y -> 6838887.139, z -> 102.04899999999907, m -> 40.433499999999185), Map(x -> 512219.873, y -> 6838895.609, z -> 101.94599999999627, m -> 50.42829999999958), Map(x -> 512216.978, y -> 6838900.231, z -> 101.92999999999302, m -> 55.88199999999779), Map(x -> 512214.039, y -> 6838903.695, z -> 101.8969999999972, m -> 60.42479999999341), Map(x -> 512209.972, y -> 6838908.207, z -> 101.84200000000419, m -> 66.49929999999586))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512244.316 6838851.527 102.22900000000664 0, 512240.643 6838858.47 102.17500000000291 7.854699999996228, 512237.21 6838864.801 102.16999999999825 15.056599999996251, 512233.455 6838871.881 102.1140000000014 23.070699999996577, 512228.734 6838880.786 102.11199999999371 33.14969999999448, 512226.332 6838885.298 102.00800000000163 38.2612999999983, 512225.179 6838887.139 102.04899999999907 40.433499999999185, 512219.873 6838895.609 101.94599999999627 50.42829999999958, 512216.978 6838900.231 101.92999999999302 55.88199999999779, 512214.039 6838903.695 101.8969999999972 60.42479999999341, 512209.972 6838908.207 101.84200000000419 66.49929999999586)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910509,List(Point(512476.259,6838479.304,103.22500000000582), Point(512469.723,6838482.604,103.06299999999464), Point(512469.446,6838482.79,103.05599999999686), Point(512463.159,6838490.128,102.76600000000326), Point(512456.655,6838497.719,102.55499999999302), Point(512450.151,6838505.309,102.39299999999639), Point(512443.647,6838512.9,102.22299999999814), Point(512437.143,6838520.491,102.05400000000373), Point(512430.639,6838528.081,101.89299999999639), Point(512424.135,6838535.672,101.80599999999686), Point(512417.631,6838543.263,101.72999999999593), Point(512411.128,6838550.853,101.6929999999993), Point(512404.623,6838558.444,101.69700000000012), Point(512399.524,6838564.395,101.74000000000524), Point(512398.123,6838566.038,101.68600000000151), Point(512391.737,6838573.728,101.69800000000396), Point(512385.52,6838581.555,101.7039999999979), Point(512379.473,6838589.515,101.66599999999744), Point(512369.283,6838604.408,101.65200000000186), Point(512365.37,6838610.901,101.59399999999732)),172.893,State,99,TrafficDirection.AgainstDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999878004L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512476.259, y -> 6838479.304, z -> 103.22500000000582, m -> 0), Map(x -> 512469.723, y -> 6838482.604, z -> 103.06299999999464, m -> 7.321800000005169), Map(x -> 512469.446, y -> 6838482.79, z -> 103.05599999999686, m -> 7.655499999993481), Map(x -> 512463.159, y -> 6838490.128, z -> 102.76600000000326, m -> 17.31840000000375), Map(x -> 512456.655, y -> 6838497.719, z -> 102.55499999999302, m -> 27.31470000000263), Map(x -> 512450.151, y -> 6838505.309, z -> 102.39299999999639, m -> 37.31020000000717), Map(x -> 512443.647, y -> 6838512.9, z -> 102.22299999999814, m -> 47.306500000006054), Map(x -> 512437.143, y -> 6838520.491, z -> 102.05400000000373, m -> 57.302700000000186), Map(x -> 512430.639, y -> 6838528.081, z -> 101.89299999999639, m -> 67.29820000000473), Map(x -> 512424.135, y -> 6838535.672, z -> 101.80599999999686, m -> 77.29450000000361), Map(x -> 512417.631, y -> 6838543.263, z -> 101.72999999999593, m -> 87.29080000000249), Map(x -> 512411.128, y -> 6838550.853, z -> 101.6929999999993, m -> 97.28560000000289), Map(x -> 512404.623, y -> 6838558.444, z -> 101.69700000000012, m -> 107.28250000000116), Map(x -> 512399.524, y -> 6838564.395, z -> 101.74000000000524, m -> 115.11930000000575), Map(x -> 512398.123, y -> 6838566.038, z -> 101.68600000000151, m -> 117.27850000000035), Map(x -> 512391.737, y -> 6838573.728, z -> 101.69800000000396, m -> 127.27430000000459), Map(x -> 512385.52, y -> 6838581.555, z -> 101.7039999999979, m -> 137.27000000000407), Map(x -> 512379.473, y -> 6838589.515, z -> 101.66599999999744, m -> 147.26639999999315), Map(x -> 512369.283, y -> 6838604.408, z -> 101.65200000000186, m -> 165.31179999999586), Map(x -> 512365.37, y -> 6838610.901, z -> 101.59399999999732, m -> 172.8926999999967))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512476.259 6838479.304 103.22500000000582 0, 512469.723 6838482.604 103.06299999999464 7.321800000005169, 512469.446 6838482.79 103.05599999999686 7.655499999993481, 512463.159 6838490.128 102.76600000000326 17.31840000000375, 512456.655 6838497.719 102.55499999999302 27.31470000000263, 512450.151 6838505.309 102.39299999999639 37.31020000000717, 512443.647 6838512.9 102.22299999999814 47.306500000006054, 512437.143 6838520.491 102.05400000000373 57.302700000000186, 512430.639 6838528.081 101.89299999999639 67.29820000000473, 512424.135 6838535.672 101.80599999999686 77.29450000000361, 512417.631 6838543.263 101.72999999999593 87.29080000000249, 512411.128 6838550.853 101.6929999999993 97.28560000000289, 512404.623 6838558.444 101.69700000000012 107.28250000000116, 512399.524 6838564.395 101.74000000000524 115.11930000000575, 512398.123 6838566.038 101.68600000000151 117.27850000000035, 512391.737 6838573.728 101.69800000000396 127.27430000000459, 512385.52 6838581.555 101.7039999999979 137.27000000000407, 512379.473 6838589.515 101.66599999999744 147.26639999999315, 512369.283 6838604.408 101.65200000000186 165.31179999999586, 512365.37 6838610.901 101.59399999999732 172.8926999999967)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910511,List(Point(512476.259,6838479.304,103.22500000000582), Point(512474.424,6838486.31,103.09399999999732), Point(512470.254,6838492.565,102.83900000000722), Point(512463.439,6838500.463,102.59399999999732), Point(512462.083,6838502.209,102.52700000000186), Point(512458.844,6838506.084,102.41300000000047), Point(512455.589,6838509.806,102.29700000000594), Point(512449.075,6838517.389,102.07200000000012), Point(512447.741,6838518.966,102.09200000000419), Point(512442.626,6838525.026,101.83599999999569), Point(512436.179,6838532.664,101.78599999999278), Point(512429.731,6838540.303,101.63099999999395), Point(512423.283,6838547.941,101.47599999999511), Point(512416.835,6838555.58,101.45100000000093), Point(512410.388,6838563.219,101.40499999999884), Point(512408.112,6838565.916,101.39100000000326), Point(512403.972,6838570.885,101.47400000000198), Point(512397.704,6838578.67,101.47999999999593), Point(512391.593,6838586.581,101.4539999999979), Point(512385.644,6838594.613,101.3969999999972), Point(512379.858,6838602.764,101.45699999999488), Point(512374.237,6838611.031,101.40700000000652), Point(512372.244,6838613.972,101.36299999999756)),170.898,State,99,TrafficDirection.TowardsDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999878064L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512476.259, y -> 6838479.304, z -> 103.22500000000582, m -> 0), Map(x -> 512474.424, y -> 6838486.31, z -> 103.09399999999732, m -> 7.2422999999980675), Map(x -> 512470.254, y -> 6838492.565, z -> 102.83900000000722, m -> 14.759900000004563), Map(x -> 512463.439, y -> 6838500.463, z -> 102.59399999999732, m -> 25.191699999995762), Map(x -> 512462.083, y -> 6838502.209, z -> 102.52700000000186, m -> 27.40240000000631), Map(x -> 512458.844, y -> 6838506.084, z -> 102.41300000000047, m -> 32.452799999999115), Map(x -> 512455.589, y -> 6838509.806, z -> 102.29700000000594, m -> 37.39740000000165), Map(x -> 512449.075, y -> 6838517.389, z -> 102.07200000000012, m -> 47.39410000000498), Map(x -> 512447.741, y -> 6838518.966, z -> 102.09200000000419, m -> 49.459600000001956), Map(x -> 512442.626, y -> 6838525.026, z -> 101.83599999999569, m -> 57.38969999999972), Map(x -> 512436.179, y -> 6838532.664, z -> 101.78599999999278, m -> 67.38490000000456), Map(x -> 512429.731, y -> 6838540.303, z -> 101.63099999999395, m -> 77.3813999999984), Map(x -> 512423.283, y -> 6838547.941, z -> 101.47599999999511, m -> 87.37720000000263), Map(x -> 512416.835, y -> 6838555.58, z -> 101.45100000000093, m -> 97.37380000000121), Map(x -> 512410.388, y -> 6838563.219, z -> 101.40499999999884, m -> 107.36969999999565), Map(x -> 512408.112, y -> 6838565.916, z -> 101.39100000000326, m -> 110.89870000000519), Map(x -> 512403.972, y -> 6838570.885, z -> 101.47400000000198, m -> 117.36629999999423), Map(x -> 512397.704, y -> 6838578.67, z -> 101.47999999999593, m -> 127.36100000000442), Map(x -> 512391.593, y -> 6838586.581, z -> 101.4539999999979, m -> 137.35749999999825), Map(x -> 512385.644, y -> 6838594.613, z -> 101.3969999999972, m -> 147.35259999999835), Map(x -> 512379.858, y -> 6838602.764, z -> 101.45699999999488, m -> 157.34849999999278), Map(x -> 512374.237, y -> 6838611.031, z -> 101.40700000000652, m -> 167.3454000000056), Map(x -> 512372.244, y -> 6838613.972, z -> 101.36299999999756, m -> 170.8981000000058))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512476.259 6838479.304 103.22500000000582 0, 512474.424 6838486.31 103.09399999999732 7.2422999999980675, 512470.254 6838492.565 102.83900000000722 14.759900000004563, 512463.439 6838500.463 102.59399999999732 25.191699999995762, 512462.083 6838502.209 102.52700000000186 27.40240000000631, 512458.844 6838506.084 102.41300000000047 32.452799999999115, 512455.589 6838509.806 102.29700000000594 37.39740000000165, 512449.075 6838517.389 102.07200000000012 47.39410000000498, 512447.741 6838518.966 102.09200000000419 49.459600000001956, 512442.626 6838525.026 101.83599999999569 57.38969999999972, 512436.179 6838532.664 101.78599999999278 67.38490000000456, 512429.731 6838540.303 101.63099999999395 77.3813999999984, 512423.283 6838547.941 101.47599999999511 87.37720000000263, 512416.835 6838555.58 101.45100000000093 97.37380000000121, 512410.388 6838563.219 101.40499999999884 107.36969999999565, 512408.112 6838565.916 101.39100000000326 110.89870000000519, 512403.972 6838570.885 101.47400000000198 117.36629999999423, 512397.704 6838578.67 101.47999999999593 127.36100000000442, 512391.593 6838586.581 101.4539999999979 137.35749999999825, 512385.644 6838594.613 101.3969999999972 147.35259999999835, 512379.858 6838602.764 101.45699999999488 157.34849999999278, 512374.237 6838611.031 101.40700000000652 167.3454000000056, 512372.244 6838613.972 101.36299999999756 170.8981000000058)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910527,List(Point(512279.32,6838745.083,101.78399999999965), Point(512280.764,6838748.184,102.28100000000268), Point(512281.492,6838749.957,102.46799999999348), Point(512281.897,6838751.39,102.51399999999558), Point(512282.096,6838753.216,102.52999999999884), Point(512282.065,6838754.61,102.596000000005), Point(512281.863,6838758.142,102.58599999999569), Point(512281.258,6838762.482,102.67200000000594), Point(512279.845,6838766.822,102.6420000000071), Point(512277.826,6838771.868,102.67500000000291), Point(512274.365,6838779.12,102.63999999999942), Point(512271.067,6838785.463,102.57300000000396)),43.161,State,99,TrafficDirection.AgainstDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999769064L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512279.32, y -> 6838745.083, z -> 101.78399999999965, m -> 0), Map(x -> 512280.764, y -> 6838748.184, z -> 102.28100000000268, m -> 3.420700000002398), Map(x -> 512281.492, y -> 6838749.957, z -> 102.46799999999348, m -> 5.337400000003981), Map(x -> 512281.897, y -> 6838751.39, z -> 102.51399999999558, m -> 6.826499999995576), Map(x -> 512282.096, y -> 6838753.216, z -> 102.52999999999884, m -> 8.663300000000163), Map(x -> 512282.065, y -> 6838754.61, z -> 102.596000000005, m -> 10.057700000004843), Map(x -> 512281.863, y -> 6838758.142, z -> 102.58599999999569, m -> 13.595400000005611), Map(x -> 512281.258, y -> 6838762.482, z -> 102.67200000000594, m -> 17.9774000000034), Map(x -> 512279.845, y -> 6838766.822, z -> 102.6420000000071, m -> 22.541599999996834), Map(x -> 512277.826, y -> 6838771.868, z -> 102.67500000000291, m -> 27.976500000004307), Map(x -> 512274.365, y -> 6838779.12, z -> 102.63999999999942, m -> 36.012100000007194), Map(x -> 512271.067, y -> 6838785.463, z -> 102.57300000000396, m -> 43.16130000000703))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512279.32 6838745.083 101.78399999999965 0, 512280.764 6838748.184 102.28100000000268 3.420700000002398, 512281.492 6838749.957 102.46799999999348 5.337400000003981, 512281.897 6838751.39 102.51399999999558 6.826499999995576, 512282.096 6838753.216 102.52999999999884 8.663300000000163, 512282.065 6838754.61 102.596000000005 10.057700000004843, 512281.863 6838758.142 102.58599999999569 13.595400000005611, 512281.258 6838762.482 102.67200000000594 17.9774000000034, 512279.845 6838766.822 102.6420000000071 22.541599999996834, 512277.826 6838771.868 102.67500000000291 27.976500000004307, 512274.365 6838779.12 102.63999999999942 36.012100000007194, 512271.067 6838785.463 102.57300000000396 43.16130000000703)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910530,List(Point(512317.14,6838754.684,101.96099999999569), Point(512311.861,6838757.541,102.0969999999943), Point(512305.984,6838761.473,101.98200000000361), Point(512300.231,6838766.216,101.55599999999686), Point(512297.694,6838768.455,101.21199999999953), Point(512290.226,6838775.093,102.0789999999979), Point(512283.484,6838782.466,102.53399999999965), Point(512278.172,6838789.637,102.53500000000349)),52.82,State,99,TrafficDirection.TowardsDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999769105L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512317.14, y -> 6838754.684, z -> 101.96099999999569, m -> 0), Map(x -> 512311.861, y -> 6838757.541, z -> 102.0969999999943, m -> 6.002500000002328), Map(x -> 512305.984, y -> 6838761.473, z -> 101.98200000000361, m -> 13.073600000003353), Map(x -> 512300.231, y -> 6838766.216, z -> 101.55599999999686, m -> 20.52959999999439), Map(x -> 512297.694, y -> 6838768.455, z -> 101.21199999999953, m -> 23.913400000004913), Map(x -> 512290.226, y -> 6838775.093, z -> 102.0789999999979, m -> 33.905100000003586), Map(x -> 512283.484, y -> 6838782.466, z -> 102.53399999999965, m -> 43.89579999999842), Map(x -> 512278.172, y -> 6838789.637, z -> 102.53500000000349, m -> 52.820000000006985))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512317.14 6838754.684 101.96099999999569 0, 512311.861 6838757.541 102.0969999999943 6.002500000002328, 512305.984 6838761.473 101.98200000000361 13.073600000003353, 512300.231 6838766.216 101.55599999999686 20.52959999999439, 512297.694 6838768.455 101.21199999999953 23.913400000004913, 512290.226 6838775.093 102.0789999999979 33.905100000003586, 512283.484 6838782.466 102.53399999999965 43.89579999999842, 512278.172 6838789.637 102.53500000000349 52.820000000006985)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910544,List(Point(512274.223,6838795.509,102.55400000000373), Point(512271.222,6838800.05,102.55100000000675)),5.443,State,99,TrafficDirection.TowardsDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999769111L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512274.223, y -> 6838795.509, z -> 102.55400000000373, m -> 0), Map(x -> 512271.222, y -> 6838800.05, z -> 102.55100000000675, m -> 5.4429999999993015))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512274.223 6838795.509 102.55400000000373 0, 512271.222 6838800.05 102.55100000000675 5.4429999999993015)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910546,List(Point(512278.172,6838789.637,102.53500000000349), Point(512274.223,6838795.509,102.55400000000373)),7.076,State,99,TrafficDirection.TowardsDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999769121L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512278.172, y -> 6838789.637, z -> 102.53500000000349, m -> 0), Map(x -> 512274.223, y -> 6838795.509, z -> 102.55400000000373, m -> 7.076400000005378))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512278.172 6838789.637 102.53500000000349 0, 512274.223 6838795.509 102.55400000000373 7.076400000005378)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910547,List(Point(512271.067,6838785.463,102.57300000000396), Point(512269.753,6838787.989,103.0), Point(512267.421,6838792.474,102.50800000000163)),7.902,State,99,TrafficDirection.AgainstDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999769054L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512271.067, y -> 6838785.463, z -> 102.57300000000396, m -> 0), Map(x -> 512269.753, y -> 6838787.989, z -> 103, m -> 2.847299999993993), Map(x -> 512267.421, y -> 6838792.474, z -> 102.50800000000163, m -> 7.90240000000631))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512271.067 6838785.463 102.57300000000396 0, 512269.753 6838787.989 103 2.847299999993993, 512267.421 6838792.474 102.50800000000163 7.90240000000631)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910567,List(Point(512329.755,6838665.507,101.92600000000675), Point(512326.997,6838674.426,102.03100000000268), Point(512320.337,6838681.872,102.11800000000221), Point(512317.157,6838684.885,102.05100000000675), Point(512312.885,6838688.528,102.1030000000028), Point(512308.765,6838691.579,102.0460000000021), Point(512303.249,6838695.748,101.87699999999313), Point(512298.129,6838699.121,101.55899999999383), Point(512290.787,6838703.765,101.99499999999534)),56.18,State,99,TrafficDirection.AgainstDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999877963L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512329.755, y -> 6838665.507, z -> 101.92600000000675, m -> 0), Map(x -> 512326.997, y -> 6838674.426, z -> 102.03100000000268, m -> 9.335699999995995), Map(x -> 512320.337, y -> 6838681.872, z -> 102.11800000000221, m -> 19.325599999996484), Map(x -> 512317.157, y -> 6838684.885, z -> 102.05100000000675, m -> 23.706300000005285), Map(x -> 512312.885, y -> 6838688.528, z -> 102.1030000000028, m -> 29.320699999996577), Map(x -> 512308.765, y -> 6838691.579, z -> 102.0460000000021, m -> 34.44740000000456), Map(x -> 512303.249, y -> 6838695.748, z -> 101.87699999999313, m -> 41.361699999994016), Map(x -> 512298.129, y -> 6838699.121, z -> 101.55899999999383, m -> 47.492800000007264), Map(x -> 512290.787, y -> 6838703.765, z -> 101.99499999999534, m -> 56.180300000007264))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512329.755 6838665.507 101.92600000000675 0, 512326.997 6838674.426 102.03100000000268 9.335699999995995, 512320.337 6838681.872 102.11800000000221 19.325599999996484, 512317.157 6838684.885 102.05100000000675 23.706300000005285, 512312.885 6838688.528 102.1030000000028 29.320699999996577, 512308.765 6838691.579 102.0460000000021 34.44740000000456, 512303.249 6838695.748 101.87699999999313 41.361699999994016, 512298.129 6838699.121 101.55899999999383 47.492800000007264, 512290.787 6838703.765 101.99499999999534 56.180300000007264)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910569,List(Point(512339.611,6838671.001,101.69599999999627), Point(512334.865,6838680.575,101.83599999999569), Point(512330.257,6838689.446,101.99199999999837), Point(512326.799,6838696.099,102.13300000000163), Point(512325.774,6838698.375,102.20500000000175), Point(512324.825,6838701.767,102.29700000000594), Point(512324.95,6838705.951,102.33900000000722), Point(512325.901,6838709.577,102.31500000000233), Point(512327.657,6838714.361,101.625)),47.229,State,99,TrafficDirection.TowardsDigitizing,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999878082L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512339.611, y -> 6838671.001, z -> 101.69599999999627, m -> 0), Map(x -> 512334.865, y -> 6838680.575, z -> 101.83599999999569, m -> 10.685800000006566), Map(x -> 512330.257, y -> 6838689.446, z -> 101.99199999999837, m -> 20.682199999995646), Map(x -> 512326.799, y -> 6838696.099, z -> 102.13300000000163, m -> 28.180200000002515), Map(x -> 512325.774, y -> 6838698.375, z -> 102.20500000000175, m -> 30.676399999996647), Map(x -> 512324.825, y -> 6838701.767, z -> 102.29700000000594, m -> 34.19860000000335), Map(x -> 512324.95, y -> 6838705.951, z -> 102.33900000000722, m -> 38.384500000000116), Map(x -> 512325.901, y -> 6838709.577, z -> 102.31500000000233, m -> 42.13310000000638), Map(x -> 512327.657, y -> 6838714.361, z -> 101.625, m -> 47.22920000000158))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512339.611 6838671.001 101.69599999999627 0, 512334.865 6838680.575 101.83599999999569 10.685800000006566, 512330.257 6838689.446 101.99199999999837 20.682199999995646, 512326.799 6838696.099 102.13300000000163 28.180200000002515, 512325.774 6838698.375 102.20500000000175 30.676399999996647, 512324.825 6838701.767 102.29700000000594 34.19860000000335, 512324.95 6838705.951 102.33900000000722 38.384500000000116, 512325.901 6838709.577 102.31500000000233 42.13310000000638, 512327.657 6838714.361 101.625 47.22920000000158)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface),
          RoadLink(11910589,List(Point(512477.964,6838476.83,103.28299999999581), Point(512476.259,6838479.304,103.22500000000582)),3.005,State,99,TrafficDirection.BothDirections,UnknownLinkType,Some("31.03.2021 02:00:14"),None,Map("MUNICIPALITYCODE" -> BigInt(491L), "VALIDFROM" -> BigInt(1597795200000L), "MTKID" -> BigInt(1999947538L), "ROADNAME_FI" -> "Jyväskyläntie", "points" -> "List(Map(x -> 512477.964, y -> 6838476.83, z -> 103.28299999999581, m -> 0), Map(x -> 512476.259, y -> 6838479.304, z -> 103.22500000000582, m -> 3.0046000000002095))", "ROADPARTNUMBER" -> BigInt(218L), "CONSTRUCTIONTYPE" -> BigInt(0L), "MTKCLASS" -> BigInt(12121L), "CREATED_DATE" -> BigInt(1599089451000L), "LAST_EDITED_DATE" -> BigInt(1617145214000L), "geometryWKT" -> "LINESTRING ZM (512477.964 6838476.83 103.28299999999581 0, 512476.259 6838479.304 103.22500000000582 3.0046000000002095)", "ROADNUMBER" -> BigInt(13L)),InUse,FrozenLinkInterface)
        )




        val roadwayPS = dynamicSession.prepareStatement(
    """
          insert into ROADWAY (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,
          start_date,end_date,created_by,administrative_class,ely,terminated,valid_from)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)

        import java.text.SimpleDateFormat
        val DATE_FORMAT = "yyyy-MM-dd"
        val DATE_TIME_FORMAT = "yyyy-MM-dd hh:mm:ss"
        val DATE_TIME_FORMAT_LONG = "yyyy-MM-dd hh:mm:ss.S"
        val dateFormat = new SimpleDateFormat(DATE_FORMAT)
        val dateTimeFormat = new SimpleDateFormat(DATE_TIME_FORMAT)
        val dateTimeFormatLong = new SimpleDateFormat(DATE_TIME_FORMAT_LONG)

       val roadways_13_218 = List(
        List(126019218,13,218,0,0,19,0,5,"1996-01-01","2009-12-31",creator,1,8,0,"2015-10-21 12:10:00.000"),
        List(126019218,13,218,0,0,19,0,5,"2010-01-01","",creator,1,8,0,"2015-10-26 12:10:00.000"),
        List(148122186,13,218,0,19,1561,0,5,"2010-01-01","",creator,1,8,0,"2016-03-08 12:03:00.000"),
        List(148122186,13,218,0,19,1561,0,5,"1996-01-01","2009-12-31",creator,1,8,0,"2016-03-08 12:03:00.000"),
        List(126019231,13,218,0,1561,1717,0,5,"1996-01-01","2009-12-31",creator,1,8,0,"2016-03-08 12:03:00.000"),
        List(126019231,13,218,0,1561,1717,0,5,"2010-01-01","2016-02-29",creator,1,8,0,"2016-03-08 12:03:00.000"),
        List(126019231,13,218,1,1561,1717,0,5,"2016-03-01","",creator,1,8,0,"2016-03-30 12:03:00.000"),
        List(148127797,13,218,2,1561,1717,0,5,"2016-03-01","",creator,1,8,0,"2016-03-08 12:03:00.000"),
        List(148122023,13,218,1,1717,1868,0,5,"2016-03-01","",creator,1,8,0,"2016-03-30 12:03:00.000"),
        List(148128053,13,218,2,1717,1868,0,5,"2016-03-01","",creator,1,8,0,"2016-03-08 12:03:00.000"),
        List(148122023,13,218,0,1717,1868,0,5,"2010-01-01","2016-02-29",creator,1,8,0,"2016-03-08 12:03:00.000"),
        List(148122023,13,218,0,1717,1868,0,5,"1996-01-01","2009-12-31",creator,1,8,0,"2016-03-08 12:03:00.000"),
        List(126019743,13,218,0,1868,2249,0,5,"1996-01-01","2009-12-31",creator,1,8,0,"2016-03-08 12:03:00.000"),
        List(126019743,13,218,0,1868,2249,0,5,"2010-01-01","",creator,1,8,0,"2016-03-08 12:03:00.000"),
        List(126019932,13,218,0,2249,2536,0,2,"2015-10-01","",creator,1,8,0,"2015-10-26 12:10:00.000"),
        List(126019932,13,218,0,2249,2536,0,5,"2010-01-01","2015-09-30",creator,1,8,0,"2015-10-21 12:10:00.000"),
        List(126019932,13,218,0,2249,2536,0,5,"1996-01-01","2009-12-31",creator,1,8,0,"2015-10-21 12:10:00.000"),
        List(126027676,13,218,0,2536,2561,0,2,"2010-01-01","2015-10-01",creator,1,8,1,"2015-10-21 12:10:00.000"),
        List(126027676,13,218,0,2536,2561,0,2,"1996-01-01","2009-12-31",creator,1,8,2,"2015-10-21 12:10:00.000"),
        List(6045540,13,218,0,0,7345,1,5,"1964-01-01","1995-12-31",creator,1,9,0,"2020-05-13 12:05:00.000"),
        List(283720652,13,218,0,7345,7396,1,5,"1964-01-01","1995-12-31",creator,1,9,0,"2020-05-13 12:05:00.000")
       )

       val plIds = Sequences.fetchRoadwayIds(roadways_13_218.size)
        roadways_13_218.zipWithIndex.foreach { case (address,i) =>
          roadwayPS.setLong(1, plIds(i))
          roadwayPS.setLong(2, address.head.asInstanceOf[Number].longValue)//roadway_number
          roadwayPS.setLong(3, address(1).asInstanceOf[Number].longValue)//road_number
          roadwayPS.setLong(4, address(2).asInstanceOf[Number].longValue)//road_part_number
          roadwayPS.setInt(5, address(3).asInstanceOf[Number].intValue())//Track
          roadwayPS.setLong(6, address(4).asInstanceOf[Number].longValue)//start_addr_m
          roadwayPS.setLong(7, address(5).asInstanceOf[Number].longValue)//end_addr_m
          roadwayPS.setInt(8, address(6).asInstanceOf[Number].intValue())//Reversed
          roadwayPS.setInt(9, address(7).asInstanceOf[Number].intValue())//discontinuity
          roadwayPS.setDate(10, new java.sql.Date(dateFormat.parse(address(8).toString).getTime))//start_date
          if (address(9).toString.nonEmpty) {
            roadwayPS.setDate(11, new java.sql.Date(dateFormat.parse(address(9).toString).getTime))//end_date
          } else {
            roadwayPS.setNull(11, java.sql.Types.DATE)
          }
          roadwayPS.setString(12, address(10).toString) //created_by
          roadwayPS.setInt(13, address(11).asInstanceOf[Number].intValue()) //administrative_class
          roadwayPS.setLong(14, address(12).asInstanceOf[Number].longValue) //ely
          roadwayPS.setInt(15, address(13).asInstanceOf[Number].intValue()) // terminated
          roadwayPS.setDate(16, new java.sql.Date(dateTimeFormatLong.parse(address(14).toString).getTime)) //valid_from
          roadwayPS.addBatch()
        }
        roadwayPS.executeBatch()
        roadwayPS.close()

        val Li = List(
          List(7330434,4,1513389783000L),
          List(11910590,4,1599089451000L),
          List(11910502,4,1599089451000L),
          List(3227503,4,1513385872000L),
          List(3225290,4,1533071237000L),
          List(12017341,4,1602802820000L),
          List(3225295,4,1533071237000L),
          List(3225166,4,1533071237000L),
          List(3227484,4,1513385872000L),
          List(12017340,4,1602802820000L),
          List(3227478,4,1513385872000L),
          List(11910505,4,1599089451000L),
          List(11910588,4,1599089451000L),
          List(3225291,4,1533071237000L),
          List(11910572,4,1599089451000L),
          List(7330427,4,1513389783000L),
          List(11910587,4,1599089451000L),
          List(3227469,4,1513385872000L),
          List(3227468,4,1513385872000L),
          List(11910500,4,1599089451000L),
          List(3227482,4,1513385872000L),
          List(3227486,4,1513385872000L),
          List(3225257,4,1513389783000L),
          List(11910586,4,1599089451000L),
          List(3227480,4,1513385872000L),
          List(11910540,4,1599089451000L),
          List(11910585,4,1599089451000L),
          List(3227541,4,1513385872000L),
          List(11910568,4,1599089451000L),
          List(11910533,4,1599089451000L),
          List(7094558,4,1533071237000L),
          List(3227544,4,1513385872000L)
        )

        val linkPs = dynamicSession.prepareStatement(
          """insert into Link (ID, source, adjusted_timestamp)
             values (?, ?, ?)""".stripMargin)

        Li.zipWithIndex.foreach { case (link,i) =>
          linkPs.setLong(1, link.head.asInstanceOf[Number].longValue)
          linkPs.setLong(2, link(1).asInstanceOf[Number].longValue)
          linkPs.setLong(3, link(2).asInstanceOf[Number].longValue)
          linkPs.addBatch()
        }

        linkPs.executeBatch()
        linkPs.close()

        val linearlocations = List(
          List(126019218,1,3225257,3.062,22.456,3,"511790.071 6840314.779 0 0, 511786.541 6840333.849 0 19.394","2015-10-26 12:10:00.000",creator),
          List(126019231,1,3227478,0.000,131.954,3,"512288.7 6838743.26 0 0, 512234.416 6838863.059 0 131.954","2016-03-30 12:03:00.000",creator),
          List(126019231,2,3227484,0.000,15.908,3,"512295.534 6838728.895 0 0, 512288.7 6838743.26 0 15.908","2016-03-30 12:03:00.000",creator),
          List(126019231,3,3227486,1.921,8.646,3,"512298.63 6838722.925 0 0, 512295.534 6838728.895 0 6.725","2016-03-30 12:03:00.000",creator),
          List(126019743,1,12017340,0.000,153.238,3,"512476.259 6838479.304 0 0, 512378.721 6838597.418 0 153.238","2016-03-08 12:03:00.000",creator),
          List(126019743,2,12017341,0.000,3.005,3,"512477.964 6838476.83 0 0, 512476.259 6838479.304 0 3.005","2016-03-08 12:03:00.000",creator),
          List(126019743,3,11910590,0.000,175.113,3,"512584.149 6838337.794 0 0, 512477.964 6838476.83 0 175.113","2016-03-08 12:03:00.000",creator),
          List(126019743,4,3227503,5.056,54.602,3,"512611.021 6838296.168 0 0, 512584.149 6838337.794 0 49.546","2016-03-08 12:03:00.000",creator),
          List(126019932,1,3227503,0.000,5.056,3,"512613.763 6838291.92 0 0, 512611.021 6838296.168 0 5.056","2015-10-26 12:10:00.000",creator),
          List(126019932,2,3227468,0.000,61.692,3,"512642.831 6838237.545 0 0, 512613.763 6838291.92 0 61.692","2015-10-26 12:10:00.000",creator),
          List(126019932,3,3227469,0.000,83.172,3,"512680.892 6838163.593 0 0, 512642.831 6838237.545 0 83.172","2015-10-26 12:10:00.000",creator),
          List(126019932,4,3227544,0.000,50.669,3,"512703.961 6838118.484 0 0, 512680.892 6838163.593 0 50.669","2015-10-26 12:10:00.000",creator),
          List(126019932,5,3227541,0.000,85.657,3,"512730.945 6838037.532 0 0, 512703.961 6838118.484 0 85.657","2015-10-26 12:10:00.000",creator),
          List(148122023,1,3227486,0.000,1.921,3,"512299.514 6838721.22 0 0, 512298.63 6838722.925 0 1.921","2016-03-30 12:03:00.000",creator),
          List(148122023,2,11910568,0.000,63.405,3,"512329.755 6838665.507 0 0, 512299.514 6838721.22 0 63.405","2016-03-30 12:03:00.000",creator),
          List(148122023,3,11910585,0.000,65.218,3,"512365.37 6838610.901 0 0, 512329.755 6838665.507 0 65.218","2016-03-30 12:03:00.000",creator),
          List(148122023,4,11910587,0.000,18.975,3,"512378.721 6838597.418 0 0, 512365.37 6838610.901 0 18.975","2016-03-30 12:03:00.000",creator),
          List(148122186,1,3225257,0.000,3.062,3,"511790.61 6840311.765 0 0, 511790.071 6840314.779 0 3.062","2016-03-08 12:03:00.000",creator),
          List(148122186,2,7330427,0.000,93.514,3,"511805.651 6840219.469 0 0, 511790.61 6840311.765 0 93.514","2016-03-08 12:03:00.000",creator),
          List(148122186,3,7330434,0.000,64.403,3,"511814.67 6840155.702 0 0, 511805.651 6840219.469 0 64.403","2016-03-08 12:03:00.000",creator),
          List(148122186,4,7094558,0.000,662.726,3,"511867.808 6839499.022 0 0, 511814.67 6840155.702 0 662.726","2016-03-08 12:03:00.000",creator),
          List(148122186,5,3225290,0.000,37.832,3,"511880.659 6839463.44 0 0, 511867.808 6839499.022 0 37.832","2016-03-08 12:03:00.000",creator),
          List(148122186,6,3225291,0.000,31.193,3,"511891.647 6839434.246 0 0, 511880.659 6839463.44 0 31.193","2016-03-08 12:03:00.000",creator),
          List(148122186,7,3225295,0.000,117.061,3,"511943.035 6839329.147 0 0, 511891.647 6839434.246 0 117.061","2016-03-08 12:03:00.000",creator),
          List(148122186,8,3225166,0.000,8.547,3,"511947.249 6839321.711 0 0, 511943.035 6839329.147 0 8.547","2016-03-08 12:03:00.000",creator),
          List(148122186,9,11910500,0.000,490.206,3,"512209.972 6838908.207 0 0, 511947.249 6839321.711 0 490.206","2016-03-08 12:03:00.000",creator),
          List(148122186,10,11910502,0.000,51.344,3,"512234.416 6838863.059 0 0, 512209.972 6838908.207 0 51.344","2016-03-08 12:03:00.000",creator),
          List(148127797,1,11910505,0.000,15.199,3,"512244.316 6838851.527 0 0, 512234.416 6838863.059 0 15.199","2016-03-08 12:03:00.000",creator),
          List(148127797,2,11910540,0.000,58.087,3,"512271.222 6838800.05 0 0, 512244.316 6838851.527 0 58.087","2016-03-08 12:03:00.000",creator),
          List(148127797,3,11910533,0.000,59.053,3,"512297.968 6838747.401 0 0, 512271.222 6838800.05 0 59.053","2016-03-08 12:03:00.000",creator),
          List(148127797,4,3227482,0.000,15.460,3,"512304.761 6838733.513 0 0, 512297.968 6838747.401 0 15.46","2016-03-08 12:03:00.000",creator),
          List(148127797,5,3227480,2.005,9.021,3,"512308.174 6838727.383 0 0, 512304.761 6838733.513 0 7.016","2016-03-08 12:03:00.000",creator),
          List(148128053,1,3227480,0.000,2.005,3,"512309.149 6838725.631 0 0, 512308.174 6838727.383 0 2.005","2016-03-08 12:03:00.000",creator),
          List(148128053,2,11910572,0.000,62.549,3,"512339.611 6838671.001 0 0, 512309.149 6838725.631 0 62.549","2016-03-08 12:03:00.000",creator),
          List(148128053,3,11910586,0.000,65.706,3,"512372.244 6838613.972 0 0, 512339.611 6838671.001 0 65.706","2016-03-08 12:03:00.000",creator),
          List(148128053,4,11910588,0.000,17.783,3,"512378.721 6838597.418 0 0, 512372.244 6838613.972 0 17.783","2016-03-08 12:03:00.000",creator)
        )

        val newIds = Sequences.fetchLinearLocationIds(linearlocations.size)
        val lps = dynamicSession.prepareStatement(
          """insert into LINEAR_LOCATION (id, ROADWAY_NUMBER, order_number, link_id, start_measure, end_measure, SIDE, geometry, valid_from, created_by)
      values (?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 3067), ?, ?)""".stripMargin)

        linearlocations.zipWithIndex.foreach { case (location,i) =>
          lps.setLong(1, newIds(i))
          lps.setLong(2, location.head.asInstanceOf[Number].longValue)//roadway_number
          lps.setLong(3, location(1).asInstanceOf[Number].longValue)//order_number
          lps.setLong(4, location(2).asInstanceOf[Number].longValue)//link_id
          lps.setDouble(5, location(3).asInstanceOf[Number].doubleValue())//start_measure
          lps.setDouble(6, location(4).asInstanceOf[Number].doubleValue())//end_measure
          lps.setInt(7, location(5).asInstanceOf[Number].intValue())//SIDE
          lps.setString(8, s"""LINESTRING(${location(6)})""") //geometry
          lps.setDate(9, new java.sql.Date(dateTimeFormatLong.parse(location(7).toString).getTime))//valid_from
          lps.setString(10, location(8).toString) //created_by
          lps.addBatch()
        }

        lps.executeBatch()
        lps.close()

       val roadwayPoints = List(
         List(126019218,0,creator,creator),
         List(148122186,114,creator,creator),
         List(148122186,147,creator,creator),
         List(148122186,178,creator,creator),
         List(148122186,833,creator,creator),
         List(126019231,1561,creator,creator),
         List(148122186,1561,creator,creator),
         List(148127797,1561,creator,creator),
         List(148127797,1710,creator,creator),
         List(126019231,1710,creator,creator),
         List(126019231,1715,creator,creator),
         List(148128053,1719,creator,creator),
         List(148122023,1719,creator,creator),
         List(148122023,1868,creator,creator),
         List(148128053,1868,creator,creator),
         List(126019743,1868,creator,creator),
         List(126019932,2316,creator,creator),
         List(126019932,2320,creator,creator),
         List(126019932,2435,creator,creator),
         List(126019932,2536,creator,creator)
        )
       val rpIdsAndAddresses = new ListBuffer[(Long, Long)]()
       val newRPIds = (1 to roadwayPoints.size).map(_ => Sequences.nextRoadwayPointId)
       val rpPs = dynamicSession.prepareStatement(
          """insert into roadway_point (ID, ROADWAY_NUMBER, ADDR_M, CREATED_BY, MODIFIED_BY)
             values (?, ?, ?, ?, ?)""".stripMargin)

        roadwayPoints.zipWithIndex.foreach { case (location,i) =>
          rpIdsAndAddresses.append((newRPIds(i), location(1).asInstanceOf[Number].longValue))
          rpPs.setLong(1, newRPIds(i))
          rpPs.setLong(2, location.head.asInstanceOf[Number].longValue)
          rpPs.setLong(3, location(1).asInstanceOf[Number].longValue)
          rpPs.setString(4, location(2).toString)
          rpPs.setString(5, location(3).toString)
          rpPs.addBatch()
        }

        rpPs.executeBatch()
        rpPs.close()

        val calibrationPoints = List(
          List(64046,3225257,0,2,creator,126019218,0),
          List(68761,7330427,1,2,creator,148122186,114),
          List(68761,7330434,0,2,creator,148122186,114),
          List(68762,7094558,0,2,creator,148122186,178),
          List(68762,7330434,1,2,creator,148122186,178),
          List(68763,3225290,0,2,creator,148122186,833),
          List(68763,7094558,1,2,creator,148122186,833),
          List(64599,3227478,0,2,creator,126019231,1561),
          List(68764,11910502,1,2,creator,148122186,1561),
          List(70539,11910505,0,2,creator,148127797,1561),
          List(70540,3227480,0,2,creator,148127797,1710),
          List(70540,3227482,1,2,creator,148127797,1710),
          List(64600,3227484,1,2,creator,126019231,1710),
          List(64600,3227486,0,2,creator,126019231,1710),
          List(70189,3227480,1,2,creator,148128053,1719),
          List(68960,3227486,1,2,creator,148122023,1719),
          List(68960,11910568,0,2,creator,148122023,1719),
          List(70189,11910572,0,2,creator,148128053,1719),
          List(68961,11910587,1,2,creator,148122023,1868),
          List(70190,11910588,1,2,creator,148128053,1868),
          List(63779,12017340,0,2,creator,126019743,1868),
          List(64573,3227468,1,2,creator,126019932,2316),
          List(64573,3227469,0,2,creator,126019932,2316),
          List(64574,3227541,1,2,creator,126019932,2536)
        )

        val newCPIds = (1 to calibrationPoints.size).map(_ => Sequences.nextCalibrationPointId).toList
        val cpPs = dynamicSession.prepareStatement(
          """INSERT INTO CALIBRATION_POINT (ID, ROADWAY_POINT_ID, LINK_ID, START_END, TYPE, CREATED_BY)
             VALUES (?, ?, ?, ?, ?, ?)""".stripMargin)
        calibrationPoints.zipWithIndex.foreach { case (cp,i) =>
          cpPs.setLong(1, newCPIds(i))
          cpPs.setLong(2, roadwayPointDAO.fetch(cp(5).asInstanceOf[Number].longValue,cp(6).asInstanceOf[Number].longValue).get.id)
          cpPs.setLong(3, cp(1).asInstanceOf[Number].longValue)
          cpPs.setInt(4, cp(2).asInstanceOf[Number].intValue())
          cpPs.setInt(5, cp(3).asInstanceOf[Number].intValue())
          cpPs.setString(6, cp(4).toString)
          cpPs.addBatch()
        }

        cpPs.executeBatch()
        cpPs.close()

        val nodes = List(
          List(83619,"511868","6839500","Lentokentänkatu",1,"2017-12-11","a009928","2017-12-11 11:12:47","2017-12-11 11:12:46"),
          List(32431,"512303","6838727","Karikko",10,"1989-01-01","u001464","2020-09-14 05:09:17","2006-01-17 12:01:00"),
          List(32467,"511789"," 6840320","Tusku",5,"1989-01-01","HARMB2020","2020-02-10 05:02:01","2006-01-17 03:01:04"),
          List(83494,"512694","6838140","Pitkäjärven etl (23)",5,"2015-10-01","HARMB2020","2020-10-15 09:10:01","2015-10-23 12:10:00")
        )
        val newNodeIds = (1 to nodes.size).map(_ => Sequences.nextNodeId).toList
        val nodePs = dynamicSession.prepareStatement(
          """INSERT INTO node (ID, node_number, coordinates,name,type,start_date,created_by,valid_from,registration_date )
                     VALUES (?, ?, ST_GeomFromText(?, 3067), ?, ?, ?, ?, ?, ?)""".stripMargin)

        nodes.zipWithIndex.foreach { case (location,i) =>
          nodePs.setLong(1, newNodeIds(i))
          nodePs.setLong(2, location.head.asInstanceOf[Number].longValue)
          nodePs.setString(3, s"""POINT(${location(1)} ${location(2)})""") //geometry
          nodePs.setString(4, location(3).toString)
          nodePs.setLong(5, location(4).asInstanceOf[Number].longValue)
          nodePs.setDate(6, new java.sql.Date(dateFormat.parse(location(5).toString).getTime))
          nodePs.setString(7, location(6).toString)
          nodePs.setDate(8, new java.sql.Date(dateTimeFormat.parse(location(7).toString).getTime))
          nodePs.setDate(9, new java.sql.Date(dateTimeFormat.parse(location(8).toString).getTime))
          nodePs.addBatch()
        }

        nodePs.executeBatch()
        nodePs.close()

        val nodePoints = List(
          List(2,126019218,0,"2015-10-21 02:10:58","a009928",32467,1),
          List(1,148122186,833,"2018-08-03 12:08:51","a009928",83619,2),
          List(2,148122186,833,"2018-08-03 12:08:51","a009928",83619,2),
          List(2,126019231,1715,"2020-11-26 08:11:16","HARM2020",32431,2),
          List(1,126019231,1715,"2020-11-26 08:11:16","HARM2020",32431,2),
          List(1,126019932,2536,"2020-10-15 09:10:01","HARMB2020",83494,1)
        )
        val newNpIds = (1 to nodePoints.size).map(_ => Sequences.nextNodePointId).toList
        val npPs = dynamicSession.prepareStatement(
          """INSERT INTO node_point (ID, before_after, roadway_point_id, valid_from, CREATED_BY, node_number, type)
             VALUES (?, ?, ?, ?, ?, ?, ?)""".stripMargin)
        nodePoints.zipWithIndex.foreach { case (np,i) =>
          npPs.setLong(1, newNpIds(i))
          npPs.setLong(2, np.head.asInstanceOf[Number].longValue)
          npPs.setLong(3, roadwayPointDAO.fetch(np(1).asInstanceOf[Number].longValue, np(2).asInstanceOf[Number].longValue).get.id)
          npPs.setDate(4, new java.sql.Date(dateTimeFormat.parse(np(3).toString).getTime))
          npPs.setString(5, np(4).toString)
          npPs.setLong(6, np(5).asInstanceOf[Number].longValue)
          npPs.setLong(7, np(6).asInstanceOf[Number].longValue)
          npPs.addBatch()
        }

        npPs.executeBatch()
        npPs.close()

        val junctions = List(
          List(3, "2003-06-30",null,"HARM2020","2020-11-26 08:11:21",32467),
          List(4, "1989-01-01",null,"HARM2020","2020-11-26 08:11:21",32431),
          List(1, "2017-12-11",null,"HARM2020","2020-11-26 08:11:21",83619),
          List(1, "1989-01-01",null,"HARM2020","2020-11-26 08:11:21",32431),
          List(5, "2015-10-01",null,"HARM2020","2020-11-26 08:11:21",83494),
          List(4, "1994-12-31","2003-06-29","TIER2598","2018-11-12 12:11:49",32418),
          List(4, "1989-11-01","1994-12-30","TIER2598","2018-11-12 12:11:49",32418),
          List(3, "1989-01-01",null,"HARM2020","2020-11-26 08:11:21",32431),
          List(5, "2003-06-30","2015-10-01","TIER2598","2018-11-12 12:11:49",33700),
          List(1, "1989-01-01","2003-06-29","TIER2598","2018-11-12 12:11:49",32467),
          List(2, "1989-01-01",null,"HARM2020","2020-11-26 08:11:21",32431),
          List(4, "2003-06-30",null,"HARM2020","2020-11-26 08:11:21",32467),
          List(8, "2015-10-01",null,"HARM2020","2020-11-26 08:11:21",83494)
        )
        val newJunctionIds = (1 to junctions.size).map(_ => Sequences.nextJunctionId).toList
        val junctionPs = dynamicSession.prepareStatement(
          """INSERT INTO junction (ID, junction_number, start_date, end_date, created_by, valid_from, node_number)
                             VALUES (?, ?, ?, ?, ?, ?, ?)""".stripMargin)
        junctions.zipWithIndex.foreach { case (j,i) =>
          junctionPs.setLong(1, newJunctionIds(i))
          junctionPs.setLong(2, j.head.asInstanceOf[Number].longValue)
          junctionPs.setDate(3, new java.sql.Date(dateFormat.parse(j(1).toString).getTime))
          junctionPs.setDate(4, if (j(2) != null) new java.sql.Date(dateFormat.parse(j(2).toString).getTime) else null)
          junctionPs.setString(5, j(3).toString)
          junctionPs.setDate(6, new java.sql.Date(dateTimeFormat.parse(j(4).toString).getTime))
          junctionPs.setLong(7, j(5).asInstanceOf[Number].longValue)
          junctionPs.addBatch()
        }

        junctionPs.executeBatch()
        junctionPs.close()

        val junctionPoints = List(
          List(1,148122186,114,4,32467),
          List(2,148122186,114,4,32467),
          List(1,148122186,178,3,32467),
          List(2,148122186,178,3,32467),
          List(1,148122186,833,1,83619),
          List(2,148122186,833,1,83619),
          List(1,148127797,1710,2,32431),
          List(1,126019231,1710,4,32431),
          List(2,148127797,1710,2,32431),
          List(2,126019231,1710,4,32431),
          List(1,148122023,1719,1,32431),
          List(2,148122023,1719,1,32431),
          List(2,148128053,1719,3,32431),
          List(1,148128053,1719,3,32431),
          List(2,126019932,2316,5,83494),
          List(1,126019932,2316,5,83494),
          List(1,126019932,2536,8,83494)
        )

        val newJunctionPointIds = (1 to junctionPoints.size).map(_ => Sequences.nextJunctionPointId).toList
        val junctionPointPs = dynamicSession.prepareStatement(
          """INSERT INTO junction_point (ID, before_after, roadway_point_id, junction_id, valid_from, created_by )
                     VALUES (?, ?, ?, ?, ?, ?)""".stripMargin)
        junctionPoints.zipWithIndex.foreach { case (jp,i) =>
          junctionPointPs.setLong(1, newJunctionPointIds(i))
          junctionPointPs.setLong(2, jp.head.asInstanceOf[Number].longValue)
          junctionPointPs.setLong(3, roadwayPointDAO.fetch(jp(1).asInstanceOf[Number].longValue,jp(2).asInstanceOf[Number].longValue).get.id)
          val t = junctionDAO_db.fetchJunctionByNodeNumber(jp(4).asInstanceOf[Number].longValue)
          junctionPointPs.setLong(4, junctionDAO_db.fetchJunctionByNodeNumber(jp(4).asInstanceOf[Number].longValue).find(_.junctionNumber.get == jp(3).asInstanceOf[Number].longValue).get.id)
          junctionPointPs.setDate(5, new java.sql.Date(dateTimeFormat.parse("2020-11-26 08:11:21").getTime))
          junctionPointPs.setString(6, creator)
          junctionPointPs.addBatch()
        }

        junctionPointPs.executeBatch()
        junctionPointPs.close()


        val test_road_number = 13
        val test_road_part_number = 218

        val road_13_218 = roadwayAddressMapper.getRoadAddressesByLinearLocation(linearLocationDAO.fetchByRoadways(roadwayDAO.fetchAllBySection(test_road_number, test_road_part_number).map(_.roadwayNumber)
                                                                                                                            .toSet)).sortBy(_.startAddrMValue).toList
        
        val reservedRoadPart_1 = ProjectReservedPart(
          road_13_218.head.id,
          road_13_218.head.roadNumber,
          road_13_218.head.roadPartNumber,
          Some(road_13_218.head.endAddrMValue),
          Some(road_13_218.head.discontinuity),
          Some(road_13_218.head.ely),
          newLength = None,
          newDiscontinuity = None,
          newEly = None,
          startingLinkId = Some(road_13_218.head.linkId))

        var links = road_13_218.map(addressToRoadLink)
        val links2 = links.groupBy(_.linkId).partition(_._2.size == 1)
        links = links2._1.values.flatten.toList ++ links2._2.map(p => p._2.head.copy(geometry = p._2.flatMap(_.geometry).distinct, length = p._2.map(_.length).sum))

        when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[Long]])).thenReturn(Seq())
        when(mockRoadLinkService.getRoadLinksByLinkIdsFromVVH(any[Set[Long]])).thenAnswer(new Answer[Seq[RoadLink]] {
          override def answer(i: InvocationOnMock): Seq[RoadLink] = {
            (links ++ newLinks).filter(l => {
              i.getArgument[Seq[Long]](0).toList.contains(l.linkId)
            })
          }
        })

        val rap = Project(0,
          ProjectState.apply(1),
          "13_218",
          "test_code",
          DateTime.now(),
          "test_code",
          DateTime.now(),
          DateTime.now(),
          null,
          List(reservedRoadPart_1),
          Seq(),
          None, Some(ProjectCoordinates(512315, 6838732, 8))
        )

        val projectSaved      = projectService_db.createRoadLinkProject(rap)

        case class Test_config(
                                track_to_test: List[ProjectLink],
                                discontinuity: Int,
                                linkStatus   : LinkStatus
                              )

        case class Test_terminated_config(
                                           track_to_test: List[ProjectLink],
                                           linkStatus   : LinkStatus
                                         )
        val first_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          x.track == Track(0) && x.endAddrMValue <= 1510
        }).toList

        val road_tracks_to_test_1 = List(
          Test_config(first_road_part_to_update, 5, LinkStatus.UnChanged)
        )

        for (test_track <- road_tracks_to_test_1) {
          projectService_db.updateProjectLinks(
            projectSaved.id,
            test_track.track_to_test.map(_.id).toSet,
            List(),
            linkStatus = test_track.linkStatus,
            userName = projectSaved.name,
            newRoadNumber = test_track.track_to_test.head.roadNumber,
            newRoadPartNumber = test_track.track_to_test.head.roadPartNumber,
            newTrackCode = test_track.track_to_test.head.track.value,
            userDefinedEndAddressM = None,
            administrativeClass = test_track.track_to_test.head.administrativeClass.value,
            discontinuity = test_track.discontinuity,
            ely = Some(test_track.track_to_test.head.ely),
            reversed = false,
            roadName = Some("Kokkola-Nuijamaa"),
            coordinates = projectSaved.coordinates
          )
        }

        val terminateLinkIds = List(11910502, 11910505, 3227478, 3227484, 3227486, 11910568, 11910533, 3227482, 3227480, 11910572, 11910587, 11910588, 12017340, 12017341)
        val links_to_terminate = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          terminateLinkIds.contains(x.linkId)
        }).toList

        val road_tracks_to_test_2 = List(
          Test_terminated_config(links_to_terminate, LinkStatus.Terminated)
        )

        for (test_track <- road_tracks_to_test_2) {
          projectService_db.updateProjectLinks(
            projectSaved.id,
            test_track.track_to_test.map(_.id).toSet,
            List(),
            linkStatus = test_track.linkStatus,
            userName = projectSaved.name,
            newRoadNumber = test_track.track_to_test.head.roadNumber,
            newRoadPartNumber = test_track.track_to_test.head.roadPartNumber,
            newTrackCode = test_track.track_to_test.head.track.value,
            userDefinedEndAddressM = None,
            administrativeClass = test_track.track_to_test.head.administrativeClass.value,
            discontinuity = test_track.track_to_test.last.discontinuity.value,
            ely = Some(test_track.track_to_test.head.ely),
            reversed = false,
            roadName = test_track.track_to_test.last.roadName,
            coordinates = projectSaved.coordinates
          )
        }

        case class New_links_config(
                                     coordinates           : Option[ProjectCoordinates],
                                     discontinuity         : Discontinuity,
                                     ids                   : Set[Long],
                                     linkIds               : Seq[Long],
                                     linkStatus            : LinkStatus,
                                     projectId             : Long,
                                     roadEly               : Long,
                                     roadLinkSource        : LinkGeomSource,
                                     roadName              : Option[String],
                                     roadNumber            : Long,
                                     roadPartNumber        : Long,
                                     administrativeClass              : AdministrativeClass,
                                     trackCode             : Track,
                                     userDefinedEndAddressM: Option[Int]
                                   )

        val new_links = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.MinorDiscontinuity,
          ids = Set(),
          linkIds = Seq(11910497, 11910547, 11910527),
          linkStatus = LinkStatus.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.RightSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links.linkIds, new_links.projectId, new_links.roadNumber, new_links.roadPartNumber,
          new_links.trackCode, new_links.discontinuity, new_links.administrativeClass,
          new_links.roadLinkSource, new_links.roadEly, projectSaved.createdBy, new_links.roadName.get,
          new_links.coordinates)

        val new_links_2 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910567),
          linkStatus = LinkStatus.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.RightSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_2.linkIds, new_links_2.projectId, new_links_2.roadNumber, new_links_2.roadPartNumber,
          new_links_2.trackCode, new_links_2.discontinuity, new_links_2.administrativeClass,
          new_links_2.roadLinkSource, new_links_2.roadEly, projectSaved.createdBy, new_links_2.roadName.get,
          new_links_2.coordinates)

        val transfer_1_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          x.track == Track(1) && x.linkId == 11910585
        }).toList
        val transfer_1       = List(
          Test_config(transfer_1_links, 5, LinkStatus.Transfer)
        )

        for (test_track <- transfer_1) {
          projectService_db.updateProjectLinks(
            projectSaved.id,
            test_track.track_to_test.map(_.id).toSet,
            List(),
            linkStatus = test_track.linkStatus,
            userName = projectSaved.name,
            newRoadNumber = test_track.track_to_test.head.roadNumber,
            newRoadPartNumber = test_track.track_to_test.head.roadPartNumber,
            newTrackCode = test_track.track_to_test.head.track.value,
            userDefinedEndAddressM = None,
            administrativeClass = test_track.track_to_test.head.administrativeClass.value,
            discontinuity = test_track.discontinuity,
            ely = Some(test_track.track_to_test.head.ely),
            reversed = false,
            roadName = Some("Kokkola-Nuijamaa"),
            coordinates = projectSaved.coordinates
          )
        }

        val new_links_3 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910509),
          linkStatus = LinkStatus.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.RightSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_3.linkIds, new_links_3.projectId, new_links_3.roadNumber, new_links_3.roadPartNumber,
          new_links_3.trackCode, new_links_3.discontinuity, new_links_3.administrativeClass,
          new_links_3.roadLinkSource, new_links_3.roadEly, projectSaved.createdBy, new_links_3.roadName.get,
          new_links_3.coordinates)

        val new_links_4 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910589),
          linkStatus = LinkStatus.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.Combined,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_4.linkIds, new_links_4.projectId, new_links_4.roadNumber, new_links_4.roadPartNumber,
          new_links_4.trackCode, new_links_4.discontinuity, new_links_4.administrativeClass,
          new_links_4.roadLinkSource, new_links_4.roadEly, projectSaved.createdBy, new_links_4.roadName.get,
          new_links_4.coordinates)


        val transfer_2_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          List(11910590, 3227503, 3227468, 3227469, 3227544, 3227541).contains(x.linkId)
        }).toList
        val transfer_2       = List(
          Test_config(transfer_2_links, 2, LinkStatus.Transfer)
        )

        for (test_track <- transfer_2) {
          projectService_db.updateProjectLinks(
            projectSaved.id,
            test_track.track_to_test.map(_.id).toSet,
            List(),
            linkStatus = test_track.linkStatus,
            userName = projectSaved.name,
            newRoadNumber = test_track.track_to_test.head.roadNumber,
            newRoadPartNumber = test_track.track_to_test.head.roadPartNumber,
            newTrackCode = test_track.track_to_test.head.track.value,
            userDefinedEndAddressM = None,
            administrativeClass = test_track.track_to_test.head.administrativeClass.value,
            discontinuity = test_track.discontinuity,
            ely = Some(test_track.track_to_test.head.ely),
            reversed = false,
            roadName = Some("Kokkola-Nuijamaa"),
            coordinates = projectSaved.coordinates
          )
        }

        val new_links_5 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910501),
          linkStatus = LinkStatus.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.LeftSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_5.linkIds, new_links_5.projectId, new_links_5.roadNumber, new_links_5.roadPartNumber,
          new_links_5.trackCode, new_links_5.discontinuity, new_links_5.administrativeClass,
          new_links_5.roadLinkSource, new_links_5.roadEly, projectSaved.createdBy, new_links_5.roadName.get,
          new_links_5.coordinates)


        val transfer_3_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          x.linkId == 11910540
        }).toList
        val transfer_3       = List(
          Test_config(transfer_3_links, 5, LinkStatus.Transfer)
        )

        for (test_track <- transfer_3) {
          projectService_db.updateProjectLinks(
            projectSaved.id,
            test_track.track_to_test.map(_.id).toSet,
            List(),
            linkStatus = test_track.linkStatus,
            userName = projectSaved.name,
            newRoadNumber = test_track.track_to_test.head.roadNumber,
            newRoadPartNumber = test_track.track_to_test.head.roadPartNumber,
            newTrackCode = test_track.track_to_test.head.track.value,
            userDefinedEndAddressM = None,
            administrativeClass = test_track.track_to_test.head.administrativeClass.value,
            discontinuity = test_track.discontinuity,
            ely = Some(test_track.track_to_test.head.ely),
            reversed = false,
            roadName = Some("Kokkola-Nuijamaa"),
            coordinates = projectSaved.coordinates
          )
        }

        val new_links_6 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.MinorDiscontinuity,
          ids = Set(),
          linkIds = Seq(11910530, 11910544, 11910546),
          linkStatus = LinkStatus.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.LeftSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_6.linkIds, new_links_6.projectId, new_links_6.roadNumber, new_links_6.roadPartNumber,
          new_links_6.trackCode, new_links_6.discontinuity, new_links_6.administrativeClass,
          new_links_6.roadLinkSource, new_links_6.roadEly, projectSaved.createdBy, new_links_6.roadName.get,
          new_links_6.coordinates)

        val new_links_7 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910569),
          linkStatus = LinkStatus.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.LeftSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_7.linkIds, new_links_7.projectId, new_links_7.roadNumber, new_links_7.roadPartNumber,
          new_links_7.trackCode, new_links_7.discontinuity, new_links_7.administrativeClass,
          new_links_7.roadLinkSource, new_links_7.roadEly, projectSaved.createdBy, new_links_7.roadName.get,
          new_links_7.coordinates)

        val new_links_8 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910511),
          linkStatus = LinkStatus.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.LeftSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_8.linkIds, new_links_8.projectId, new_links_8.roadNumber, new_links_8.roadPartNumber,
          new_links_8.trackCode, new_links_8.discontinuity, new_links_8.administrativeClass,
          new_links_8.roadLinkSource, new_links_8.roadEly, projectSaved.createdBy, new_links_8.roadName.get,
          new_links_8.coordinates)


        val transfer_4_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          x.track == Track(2) && x.linkId == 11910586
        }).toList
        val transfer_4       = List(
          Test_config(transfer_4_links, 5, LinkStatus.Transfer)
        )

        for (test_track <- transfer_4) {
          projectService_db.updateProjectLinks(
            projectSaved.id,
            test_track.track_to_test.map(_.id).toSet,
            List(),
            linkStatus = test_track.linkStatus,
            userName = projectSaved.name,
            newRoadNumber = test_track.track_to_test.head.roadNumber,
            newRoadPartNumber = test_track.track_to_test.head.roadPartNumber,
            newTrackCode = test_track.track_to_test.head.track.value,
            userDefinedEndAddressM = None,
            administrativeClass = test_track.track_to_test.head.administrativeClass.value,
            discontinuity = test_track.discontinuity,
            ely = Some(test_track.track_to_test.head.ely),
            reversed = false,
            roadName = Some("Kokkola-Nuijamaa"),
            coordinates = projectSaved.coordinates
          )
        }

        val all_projectlinks = projectService_db.getProjectLinks(projectSaved.id)

        all_projectlinks.filter(pl => Seq(11910530, 11910544, 11910546).contains(pl.linkId)).exists(pl => pl.discontinuity == Discontinuity.MinorDiscontinuity) shouldBe true
        all_projectlinks.filter(pl => Seq(11910497, 11910547, 11910527).contains(pl.linkId)).exists(pl => pl.discontinuity == Discontinuity.MinorDiscontinuity) shouldBe true

//        withDynTransaction {
                  projectService_db.recalculateProjectLinks(projectSaved.id, projectSaved.modifiedBy)
//                }
        val afterCalculatedProjectlinks = projectService_db.getProjectLinks(projectSaved.id)
        val calculatedProjectlinks      = afterCalculatedProjectlinks.filterNot(_.status == LinkStatus.Terminated)

        val leftSide = calculatedProjectlinks.filterNot(_.track == Track.RightSide).sortBy(_.startAddrMValue)
        val rightSide = calculatedProjectlinks.filterNot(_.track == Track.LeftSide).sortBy(_.startAddrMValue)

        def continuosAddresses(t: Seq[ProjectLink]) = {
          t.sortBy(_.startAddrMValue).tail.foldLeft(t.head) { (cur, next) =>
            assert(next.startAddrMValue <= next.endAddrMValue)
            assert(cur.endAddrMValue == next.startAddrMValue)
            next
          }
        }

        continuosAddresses(leftSide)
        continuosAddresses(rightSide)

         /* Create change table */
       val (changeProject, warningMessage) = projectService_db.getChangeProject(projectSaved.id)
        println("Change table warning messages:")
        if (warningMessage.isDefined) {
          println(warningMessage)
        } else println("No warnings.\n")

        println("CHANGE TABLE")

        val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(Set(projectSaved.id))
        prettyPrintLog(roadwayChanges)

        // Check Change table target
        val two_track_nonterminated_targets = changeProject.get.changeInfoSeq.filter(changeInfo => List(1,2,3).contains(changeInfo.changeType.value)).map(changeInfo => changeInfo.target)
        val two_track_nonterminated_sources = changeProject.get.changeInfoSeq.filter(changeInfo => List(1,3,5).contains(changeInfo.changeType.value)).map(_.source)
        val two_track_unchanged_and_transfers = changeProject.get.changeInfoSeq.filter(changeInfo => List(1,3).contains(changeInfo.changeType.value))

        // Cross check source/target lengths
        two_track_unchanged_and_transfers.foreach(t => (t.source.endAddressM.get - t.source.startAddressM.get) should be (t.target.endAddressM.get - t.target.startAddressM.get))

        // Value checks
        two_track_nonterminated_sources.foreach(rcs => {
          rcs.trackCode.get.toInt should (be >= 0 and be <= 2)
          rcs.roadNumber shouldBe Some(test_road_number)
          rcs.startRoadPartNumber shouldBe Some(test_road_part_number)
          rcs.endRoadPartNumber shouldBe Some(test_road_part_number)
        })

        /* Check two tracks has equal start and end addresses on both tracks and even count of two track lines. */
        /* Disabled: changes are */
//        val two_track_groups = two_track_nonterminated_sources.filterNot(_.trackCode.get == 0).groupBy(t => t.startAddressM).values
//        two_track_groups.foreach(two_track_pair => {
//          two_track_pair.size should be(2)
//          two_track_pair.head.trackCode.get should not be two_track_pair.last.trackCode.get
//          two_track_pair.head.startAddressM.get should be(two_track_pair.last.startAddressM.get)
//          two_track_pair.head.endAddressM.get should be(two_track_pair.last.endAddressM.get)
//        }
//        )

        /* Check two track addresses are continuous on each track. */
        def check_two_track_continuous(x: Seq[RoadwayChangeSection]) = {
          Seq(Track.LeftSide, Track.RightSide).foreach(track => {
            val trackAddresses = x.filterNot(_.trackCode.get == track.value).sortBy(_.startAddressM.get).map(rcs => {
              (rcs.startAddressM.get, rcs.endAddressM.get)
            })
            trackAddresses.tail.foldLeft(trackAddresses.head._2) { (cur, next) =>
              assert(next._1 < next._2) // StartAddress < EndAddress
              assert(cur == next._1) // Prev endAddress = next startAddress
              next._2
            }
          })
        }

        check_two_track_continuous(two_track_nonterminated_sources)
        check_two_track_continuous(two_track_nonterminated_targets)

        projectDAO.updateProjectStatus(projectSaved.id, ProjectState.UpdatingToRoadNetwork)
        projectService_db.updateRoadwaysAndLinearLocationsWithProjectLinks(projectSaved.id)
        val roadways = roadwayDAO.fetchAllByRoadAndPart(test_road_number,test_road_part_number, withHistory = true).toList
        val linearLocations = linearLocationDAO.fetchByRoadways(roadways.map(_.roadwayNumber).toSet).toList

        /* Check Roadways and linearlocations have a match. */
        val currentRws = roadways.filterNot(r => r.endDate.isDefined || r.validTo.isDefined)
        val linearLocationGrps = linearLocations.groupBy(_.roadwayNumber)
        currentRws.forall(r => linearLocationGrps.contains(r.roadwayNumber)) shouldBe true

        /* Check one linearlocation for each roadway, link id pair. */
        val linearLocs = linearLocationDAO.fetchByRoadways(currentRws.map(_.roadwayNumber).toSet).toList.groupBy(ll => (ll.roadwayNumber, ll.linkId))
        linearLocs.foreach(ll => assert(ll._2.size == 1))

        val roadwaysByLinkSource = linearLocationDAO.fetchByRoadways(currentRws.map(_.roadwayNumber).toSet).groupBy(_.linkGeomSource)
        val regularLinkSource = LinkGeomSource.FrozenLinkInterface
        val regular = if (roadwaysByLinkSource.contains(regularLinkSource)) roadwaysByLinkSource(regularLinkSource) else Seq()

        def continuosRoadways(t: Seq[Roadway]) = {
          t.sortBy(_.startAddrMValue).tail.foldLeft(t.head) { (cur, next) =>
            assert(next.startAddrMValue <= next.endAddrMValue)
            assert(cur.endAddrMValue == next.startAddrMValue)
            next
          }
        }

        val currentRwsLeftSide = currentRws.filterNot(_.track == Track.LeftSide)
        val currentRwsRightSide = currentRws.filterNot(_.track == Track.RightSide)

        continuosRoadways(currentRwsLeftSide)
        continuosRoadways(currentRwsRightSide)

        val addresses = currentRws.flatMap(r => {
          roadwayAddressMapper.mapRoadAddresses(r, regular)
        })

        def continuosRoadAddressses(t: Seq[RoadAddress]) = {
          t.sortBy(_.startAddrMValue).tail.foldLeft(t.head) { (cur, next) =>
            assert(next.startAddrMValue <= next.endAddrMValue)
            assert(cur.endAddrMValue == next.startAddrMValue)
            next
          }
        }
        /* Check roadAddresses formed correctly. */
        continuosRoadAddressses(addresses.filterNot(_.track == Track.LeftSide).sortBy(_.startAddrMValue))
        continuosRoadAddressses(addresses.filterNot(_.track == Track.RightSide).sortBy(_.startAddrMValue))

        /* Less well tested part below. */

        val calIds = currentRws.flatMap(crw => CalibrationPointDAO.fetchIdByRoadwayNumberSection(crw.roadwayNumber, 0, 5000))
        val cals = calIds.map(cpid => CalibrationPointDAO.fetch(cpid))

        /* Current roadways should not have any expired calibrations points. */
        cals.forall(_.validTo.isEmpty) shouldBe true

        val CPIds = calibrationPoints.map(cp => cp(1))
        val terminatedWithoutOriginalCPs = terminateLinkIds.diff(CPIds)

        /* Terminated links without calibrationpoints before should not have any calibrationpoints after. */
        terminatedWithoutOriginalCPs.diff(CPIds) should have size 2

        cals.filterNot(_.typeCode == CalibrationPointDAO.CalibrationPointType.JunctionPointCP) //puuttuu cp alusta ja roadwaypoint myös

        val currentRoadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(currentRws.map(_.roadwayNumber).distinct)

        val x = currentRoadwayPoints.filter(c => cals.map(_.roadwayPointId).contains(c.id) )
        //val y = currentRoadwayPoints.filterNot(c => cals.map(_.roadwayPointId).contains(c.id) )

        /* Check correct roadwaynumbers. */
        cals.forall(cal => {
          val t1 = x.find(_.id == cal.roadwayPointId)
          t1.isDefined && t1.get.roadwayNumber == cal.roadwayNumber
        } ) shouldBe true

        val roadAddressCals = cals.filter(_.typeCode == CalibrationPointDAO.CalibrationPointType.RoadAddressCP).groupBy(_.addrM)
        roadAddressCals.minBy(_._1)._1 shouldBe currentRws.minBy(_.startAddrMValue).startAddrMValue
        roadAddressCals.maxBy(_._1)._1 shouldBe currentRws.maxBy(_.endAddrMValue).endAddrMValue

        println("All good! :)")
      } /* Rollback */
    }
  }
  test("Test road 167_1") {
    runWithRollback {
      sqlu"""INSERT INTO public.link (id,"source",adjusted_timestamp,created_time) VALUES
            (2532907,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (2526477,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (11238297,4,1608073892000,'2021-09-03 10:53:26.219471'),
            (12031623,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031638,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031706,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2526030,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526147,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (11535724,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (12030943,4,1607036416000,'2021-09-03 10:53:26.219471'),
            (2532902,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (12038618,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12030926,4,1603839624000,'2021-09-03 10:53:26.219471'),
            (12031703,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031587,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2526472,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2532817,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (6935767,4,1481065208000,'2021-09-03 10:53:26.219471'),
            (11535725,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (2525983,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526026,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2532906,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (2526163,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12038619,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031656,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2532903,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (2526395,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12030942,4,1607036416000,'2021-09-03 10:53:26.219471'),
            (12031594,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2525999,4,1446727681000,'2021-09-03 10:53:26.219471'),
            (11342949,4,1559689217000,'2021-09-03 10:53:26.219471'),
            (2526616,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2532897,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (12031673,4,1608073892000,'2021-09-03 10:53:26.219471'),
            (2526527,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12030924,4,1603839624000,'2021-09-03 10:53:26.219471'),
            (2526624,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526656,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (2532905,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (11897533,4,1597186817000,'2021-09-03 10:53:26.219471'),
            (11364575,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2532896,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (2526385,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12031637,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (11342954,4,1559689217000,'2021-09-03 10:53:26.219471'),
            (2526038,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526133,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12031669,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2526383,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12031709,4,1608073892000,'2021-09-03 10:53:26.219471'),
            (2526247,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526652,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (11364577,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (6935785,4,1481065208000,'2021-09-03 10:53:26.219471'),
            (2532816,4,1587596417000,'2021-09-03 10:53:26.219471'),
            (12031633,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031020,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031030,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031015,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12030961,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12030937,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2525978,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12031718,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031715,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2525317,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2525349,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2525965,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526402,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2525984,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526164,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2525988,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526249,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (6935786,4,1481065208000,'2021-09-03 10:53:26.219471'),
            (12030953,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2526000,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526491,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526484,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526623,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526516,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12030957,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2526031,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526039,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526027,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526384,4,1446727681000,'2021-09-03 10:53:26.219471'),
            (6935769,4,1481065208000,'2021-09-03 10:53:26.219471'),
            (12031017,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2526145,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12031022,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2526526,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526059,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526615,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (2526134,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12031032,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2526391,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12030959,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (2526386,4,1446398762000,'2021-09-03 10:53:26.219471'),
            (12031399,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031394,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031402,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031429,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031386,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031597,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031418,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031591,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031411,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031435,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031427,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031613,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031414,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031646,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031603,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031653,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031433,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031416,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031641,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031132,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031419,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031385,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031622,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031657,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031424,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031618,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031420,4,1613432159000,'2021-09-03 10:53:26.219471'),
            (12031388,4,1613432159000,'2021-09-03 10:53:26.219471')""".execute

      sqlu"""INSERT INTO linear_location (id,roadway_number,order_number,link_id,start_measure,end_measure,side,geometry,valid_from,valid_to,created_by,created_time) VALUES
            (406260,48863145,1,11897533,0.000,72.672,3,'SRID=3067;LINESTRING ZM(427605.528 6761809.626 0 0, 427622.364 6761880.299 0 72.672)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406261,48863145,2,2525983,0.000,106.621,3,'SRID=3067;LINESTRING ZM(427580.032 6761706.116 0 0, 427605.528 6761809.626 0 106.621)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406262,48863145,3,2525999,0.000,114.230,3,'SRID=3067;LINESTRING ZM(427557.6 6761594.127 0 0, 427580.032 6761706.116 0 114.23)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406263,48863145,4,11342949,0.000,9.888,3,'SRID=3067;LINESTRING ZM(427555.466 6761584.472 0 0, 427557.6 6761594.127 0 9.888)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406264,48863145,5,11342954,0.000,101.939,3,'SRID=3067;LINESTRING ZM(427534.552 6761484.705 0 0, 427555.466 6761584.472 0 101.939)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406265,48863145,6,2526030,0.000,101.788,3,'SRID=3067;LINESTRING ZM(427512.502 6761385.336 0 0, 427534.552 6761484.705 0 101.788)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406266,48863145,7,2526038,0.000,81.885,3,'SRID=3067;LINESTRING ZM(427496.014 6761305.142 0 0, 427512.502 6761385.336 0 81.885)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406267,48863145,8,2526026,0.000,180.239,3,'SRID=3067;LINESTRING ZM(427426.175 6761139.73 0 0, 427496.014 6761305.142 0 180.239)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406268,48863145,9,2526147,0.000,7.715,3,'SRID=3067;LINESTRING ZM(427423.421 6761132.523 0 0, 427426.175 6761139.73 0 7.715)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406269,48863145,10,2526133,0.000,103.570,3,'SRID=3067;LINESTRING ZM(427411.552 6761030.601 0 0, 427423.421 6761132.523 0 103.57)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406270,48863145,11,2526163,0.000,13.440,3,'SRID=3067;LINESTRING ZM(427412.224 6761017.178 0 0, 427411.552 6761030.601 0 13.44)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406271,48863145,12,6935767,0.000,64.309,3,'SRID=3067;LINESTRING ZM(427413.626 6760952.971 0 0, 427412.224 6761017.178 0 64.309)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406272,48863145,13,6935785,0.000,22.427,3,'SRID=3067;LINESTRING ZM(427411.401 6760930.657 0 0, 427413.626 6760952.971 0 22.427)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406273,48863145,14,2526383,0.000,64.789,3,'SRID=3067;LINESTRING ZM(427403.675 6760866.333 0 0, 427411.401 6760930.657 0 64.789)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406274,48863145,15,2526385,0.000,54.900,3,'SRID=3067;LINESTRING ZM(427390.848 6760812.957 0 0, 427403.675 6760866.333 0 54.9)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406275,48863145,16,2526395,0.000,178.704,3,'SRID=3067;LINESTRING ZM(427355.523 6760637.821 0 0, 427390.848 6760812.957 0 178.704)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406276,48863145,17,2526477,0.000,172.454,3,'SRID=3067;LINESTRING ZM(427328.091 6760467.716 0 0, 427355.523 6760637.821 0 172.454)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406277,48863145,18,2526472,0.000,72.911,3,'SRID=3067;LINESTRING ZM(427316.839 6760395.721 0 0, 427328.091 6760467.716 0 72.911)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406278,48863145,19,2526247,0.000,246.483,3,'SRID=3067;LINESTRING ZM(427275.152 6760152.795 0 0, 427316.839 6760395.721 0 246.483)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406279,48863145,20,2526527,0.000,251.007,3,'SRID=3067;LINESTRING ZM(427229.834 6759905.935 0 0, 427275.152 6760152.795 0 251.007)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406280,48863145,21,2526624,0.000,19.100,3,'SRID=3067;LINESTRING ZM(427227.224 6759887.017 0 0, 427229.834 6759905.935 0 19.1)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406281,48863145,22,2526616,0.000,116.481,3,'SRID=3067;LINESTRING ZM(427207.736 6759772.178 0 0, 427227.224 6759887.017 0 116.481)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (406282,48863145,23,2526656,74.442,78.412,3,'SRID=3067;LINESTRING ZM(427207.061 6759768.266 0 0, 427207.736 6759772.178 0 3.97)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405967,48863316,1,2526656,0.000,74.442,3,'SRID=3067;LINESTRING ZM(427194.09 6759694.967 0 0, 427207.061 6759768.266 0 74.442)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405968,48863316,2,2532816,0.000,447.670,3,'SRID=3067;LINESTRING ZM(427146.462 6759249.951 0 0, 427194.09 6759694.967 0 447.67)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405969,48863316,3,2532905,0.000,10.975,3,'SRID=3067;LINESTRING ZM(427144.966 6759239.078 0 0, 427146.462 6759249.951 0 10.975)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405970,48863316,4,2532902,0.000,10.812,3,'SRID=3067;LINESTRING ZM(427143.491 6759228.367 0 0, 427144.966 6759239.078 0 10.812)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405971,48863316,5,2532896,0.000,71.474,3,'SRID=3067;LINESTRING ZM(427132.707 6759157.714 0 0, 427143.491 6759228.367 0 71.474)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405972,48863316,6,11535724,0.000,83.104,3,'SRID=3067;LINESTRING ZM(427116.104 6759076.296 0 0, 427132.707 6759157.714 0 83.104)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405973,48863316,7,12030924,0.000,46.221,3,'SRID=3067;LINESTRING ZM(427104.893 6759031.455 0 0, 427116.104 6759076.296 0 46.221)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405974,48863316,8,12030942,7.849,79.469,3,'SRID=3067;LINESTRING ZM(427085.592 6758962.497 0 0, 427104.893 6759031.455 0 71.62)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403704,48863580,1,2526652,0.000,87.748,3,'SRID=3067;LINESTRING ZM(427205.801 6759678.743 0 0, 427221.144 6759765.133 0 87.748)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403705,48863580,2,2532817,0.000,432.962,3,'SRID=3067;LINESTRING ZM(427159.358 6759248.352 0 0, 427205.801 6759678.743 0 432.962)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403706,48863580,3,2532906,0.000,10.506,3,'SRID=3067;LINESTRING ZM(427157.767 6759237.967 0 0, 427159.358 6759248.352 0 10.506)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403707,48863580,4,2532903,0.000,11.624,3,'SRID=3067;LINESTRING ZM(427156.001 6759226.478 0 0, 427157.767 6759237.967 0 11.624)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403708,48863580,5,2532897,0.000,77.182,3,'SRID=3067;LINESTRING ZM(427143.59 6759150.306 0 0, 427156.001 6759226.478 0 77.182)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403709,48863580,6,2532907,0.000,37.893,3,'SRID=3067;LINESTRING ZM(427136.472 6759113.088 0 0, 427143.59 6759150.306 0 37.893)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403710,48863580,7,11535725,0.000,39.947,3,'SRID=3067;LINESTRING ZM(427127.807 6759074.093 0 0, 427136.472 6759113.088 0 39.947)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403711,48863580,8,12030926,0.000,45.249,3,'SRID=3067;LINESTRING ZM(427116.357 6759030.317 0 0, 427127.807 6759074.093 0 45.249)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403712,48863580,9,12030943,7.937,81.355,3,'SRID=3067;LINESTRING ZM(427096.753 6758959.565 0 0, 427116.357 6759030.317 0 73.418)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405239,48863700,1,12030942,0.000,7.849,3,'SRID=3067;LINESTRING ZM(427082.889 6758955.128 0 0, 427085.592 6758962.497 0 7.849)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405333,48864204,1,12031594,0.000,226.899,3,'SRID=3067;LINESTRING ZM(426436.467 6757001.992 0 0, 426569.919 6757184.724 0 226.899)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405334,48864204,2,12031623,0.000,36.607,3,'SRID=3067;LINESTRING ZM(426412.123 6756974.653 0 0, 426436.467 6757001.992 0 36.607)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405335,48864204,3,11364575,0.000,20.398,3,'SRID=3067;LINESTRING ZM(426398.538 6756959.437 0 0, 426412.123 6756974.653 0 20.398)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405336,48864204,4,11364577,0.000,17.666,3,'SRID=3067;LINESTRING ZM(426386.772 6756946.259 0 0, 426398.538 6756959.437 0 17.666)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405337,48864204,5,12031587,0.000,426.228,3,'SRID=3067;LINESTRING ZM(426092.114 6756638.319 0 0, 426386.772 6756946.259 0 426.228)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (405338,48864204,6,12031703,185.292,200.316,3,'SRID=3067;LINESTRING ZM(426082.369 6756626.885 0 0, 426092.114 6756638.319 0 15.023)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403189,48864415,1,12031706,0.000,52.713,3,'SRID=3067;LINESTRING ZM(425990.061 6756545.651 0 0, 426024.803 6756585.179 0 52.713)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403190,48864415,2,12031709,0.000,62.569,3,'SRID=3067;LINESTRING ZM(425952.879 6756495.335 0 0, 425990.061 6756545.651 0 62.569)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403191,48864415,3,11238297,0.000,9.643,3,'SRID=3067;LINESTRING ZM(425947.656 6756487.231 0 0, 425952.879 6756495.335 0 9.643)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403192,48864415,4,12031673,0.000,27.042,3,'SRID=3067;LINESTRING ZM(425933.297 6756464.316 0 0, 425947.656 6756487.231 0 27.042)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (403193,48864415,5,12031669,0.000,37.429,3,'SRID=3067;LINESTRING ZM(425916.837 6756430.705 0 0, 425933.297 6756464.316 0 37.429)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (402889,48864590,1,12031637,0.000,255.884,3,'SRID=3067;LINESTRING ZM(426685.976 6757773.418 0 0, 426733.072 6758024.765 0 255.884)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (402890,48864590,2,12031633,0.000,140.747,3,'SRID=3067;LINESTRING ZM(426671.284 6757633.451 0 0, 426685.976 6757773.418 0 140.747)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (402891,48864590,3,12031638,0.000,132.370,3,'SRID=3067;LINESTRING ZM(426659.362 6757501.627 0 0, 426671.284 6757633.451 0 132.37)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (402892,48864590,4,12031656,0.000,4.238,3,'SRID=3067;LINESTRING ZM(426658.726 6757497.437 0 0, 426659.362 6757501.627 0 4.238)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (402893,48864590,5,12038619,0.000,22.503,3,'SRID=3067;LINESTRING ZM(426655.262 6757475.202 0 0, 426658.726 6757497.437 0 22.503)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (402894,48864590,6,12038618,0.000,44.078,3,'SRID=3067;LINESTRING ZM(426645.944 6757432.121 0 0, 426655.262 6757475.202 0 44.078)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (402895,48864590,7,12031594,226.899,486.784,3,'SRID=3067;LINESTRING ZM(426569.919 6757184.724 0 0, 426645.944 6757432.121 0 259.885)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (433722,148121596,1,12030937,0.000,87.157,3,'SRID=3067;LINESTRING ZM(427056.761 6758871.985 0 0, 427082.889 6758955.128 0 87.157)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (433723,148121596,2,12030961,0.000,9.193,3,'SRID=3067;LINESTRING ZM(427054.134 6758863.175 0 0, 427056.761 6758871.985 0 9.193)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (433724,148121596,3,12031015,0.000,51.102,3,'SRID=3067;LINESTRING ZM(427039.172 6758814.32 0 0, 427054.134 6758863.175 0 51.102)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (433725,148121596,4,12031020,0.000,32.438,3,'SRID=3067;LINESTRING ZM(427028.149 6758783.813 0 0, 427039.172 6758814.32 0 32.438)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (433726,148121596,5,12031030,27.364,78.037,3,'SRID=3067;LINESTRING ZM(427009.933 6758736.529 0 0, 427028.149 6758783.813 0 50.673)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (437378,148121997,1,12031703,120.190,185.292,3,'SRID=3067;LINESTRING ZM(426039.116 6756578.254 0 0, 426082.369 6756626.885 0 65.102)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (437569,148122184,1,2525317,0.000,15.834,3,'SRID=3067;LINESTRING ZM(427520.06 6762389.191 0 0, 427508.266 6762399.741 0 15.834)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (437570,148122184,2,2525349,0.000,240.530,3,'SRID=3067;LINESTRING ZM(427594.807 6762161.196 0 0, 427520.06 6762389.191 0 240.53)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (437571,148122184,3,2525978,0.000,182.865,3,'SRID=3067;LINESTRING ZM(427629.717 6761981.88 0 0, 427594.807 6762161.196 0 182.865)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (437572,148122184,4,2525965,0.000,102.119,3,'SRID=3067;LINESTRING ZM(427622.364 6761880.299 0 0, 427629.717 6761981.88 0 102.119)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (439425,148127680,1,12030943,0.000,7.937,3,'SRID=3067;LINESTRING ZM(427094.634 6758951.916 0 0, 427096.753 6758959.565 0 7.937)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (440100,148127681,1,12031703,0.000,120.190,3,'SRID=3067;LINESTRING ZM(425965.217 6756483.729 0 0, 426039.116 6756578.254 0 120.19)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (440101,148127681,2,12031718,0.000,6.098,3,'SRID=3067;LINESTRING ZM(425962.082 6756478.499 0 0, 425965.217 6756483.729 0 6.098)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (440102,148127681,3,12031715,0.000,63.176,3,'SRID=3067;LINESTRING ZM(425931.364 6756423.31 0 0, 425962.082 6756478.499 0 63.176)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444095,148127788,1,2525988,0.000,73.197,3,'SRID=3067;LINESTRING ZM(427613.2 6761807.864 0 0, 427622.364 6761880.299 0 73.197)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444096,148127788,2,2525984,0.000,106.307,3,'SRID=3067;LINESTRING ZM(427593.613 6761703.387 0 0, 427613.2 6761807.864 0 106.307)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444097,148127788,3,2526000,0.000,114.165,3,'SRID=3067;LINESTRING ZM(427569.624 6761591.776 0 0, 427593.613 6761703.387 0 114.165)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444098,148127788,4,2526059,0.000,111.365,3,'SRID=3067;LINESTRING ZM(427547.92 6761482.547 0 0, 427569.624 6761591.776 0 111.365)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444099,148127788,5,2526031,0.000,103.437,3,'SRID=3067;LINESTRING ZM(427524.593 6761381.776 0 0, 427547.92 6761482.547 0 103.437)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444100,148127788,6,2526039,0.000,80.330,3,'SRID=3067;LINESTRING ZM(427508.087 6761303.163 0 0, 427524.593 6761381.776 0 80.33)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444101,148127788,7,2526027,0.000,178.432,3,'SRID=3067;LINESTRING ZM(427438.949 6761139.206 0 0, 427508.087 6761303.163 0 178.432)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444102,148127788,8,2526145,0.000,9.878,3,'SRID=3067;LINESTRING ZM(427435.505 6761129.979 0 0, 427438.949 6761139.206 0 9.878)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444103,148127788,9,2526134,0.000,102.311,3,'SRID=3067;LINESTRING ZM(427422.508 6761029.723 0 0, 427435.505 6761129.979 0 102.311)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444104,148127788,10,2526164,0.000,12.177,3,'SRID=3067;LINESTRING ZM(427423.696 6761017.604 0 0, 427422.508 6761029.723 0 12.177)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444105,148127788,11,6935769,0.000,66.294,3,'SRID=3067;LINESTRING ZM(427423.389 6760951.339 0 0, 427423.696 6761017.604 0 66.294)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444106,148127788,12,6935786,0.000,21.437,3,'SRID=3067;LINESTRING ZM(427421.735 6760929.966 0 0, 427423.389 6760951.339 0 21.437)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444107,148127788,13,2526384,0.000,64.798,3,'SRID=3067;LINESTRING ZM(427413.393 6760865.734 0 0, 427421.735 6760929.966 0 64.798)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444108,148127788,14,2526386,0.000,56.578,3,'SRID=3067;LINESTRING ZM(427403.036 6760810.16 0 0, 427413.393 6760865.734 0 56.578)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444109,148127788,15,2526391,0.000,117.218,3,'SRID=3067;LINESTRING ZM(427380.098 6760695.345 0 0, 427403.036 6760810.16 0 117.218)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444110,148127788,16,2526402,0.000,61.101,3,'SRID=3067;LINESTRING ZM(427367.69 6760635.522 0 0, 427380.098 6760695.345 0 61.101)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444111,148127788,17,2526491,0.000,87.965,3,'SRID=3067;LINESTRING ZM(427351.279 6760549.115 0 0, 427367.69 6760635.522 0 87.965)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444112,148127788,18,2526484,0.000,85.168,3,'SRID=3067;LINESTRING ZM(427339.445 6760464.783 0 0, 427351.279 6760549.115 0 85.168)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444113,148127788,19,2526516,0.000,70.987,3,'SRID=3067;LINESTRING ZM(427329.293 6760394.532 0 0, 427339.445 6760464.783 0 70.987)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444114,148127788,20,2526249,0.000,248.171,3,'SRID=3067;LINESTRING ZM(427286.799 6760150.048 0 0, 427329.293 6760394.532 0 248.171)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444115,148127788,21,2526526,0.000,249.380,3,'SRID=3067;LINESTRING ZM(427244.418 6759904.317 0 0, 427286.799 6760150.048 0 249.38)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444116,148127788,22,2526623,0.000,19.798,3,'SRID=3067;LINESTRING ZM(427240.745 6759884.867 0 0, 427244.418 6759904.317 0 19.798)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444117,148127788,23,2526615,0.000,116.421,3,'SRID=3067;LINESTRING ZM(427222.092 6759769.971 0 0, 427240.745 6759884.867 0 116.421)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (444118,148127788,24,2526652,87.748,92.678,3,'SRID=3067;LINESTRING ZM(427221.144 6759765.133 0 0, 427222.092 6759769.971 0 4.93)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (441615,148128280,1,12030953,0.000,54.385,3,'SRID=3067;LINESTRING ZM(427077.819 6758900.196 0 0, 427094.634 6758951.916 0 54.385)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (441616,148128280,2,12030957,0.000,33.234,3,'SRID=3067;LINESTRING ZM(427067.876 6758868.484 0 0, 427077.819 6758900.196 0 33.234)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (441617,148128280,3,12030959,0.000,9.937,3,'SRID=3067;LINESTRING ZM(427064.836 6758859.023 0 0, 427067.876 6758868.484 0 9.937)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (441618,148128280,4,12031017,0.000,45.709,3,'SRID=3067;LINESTRING ZM(427052.171 6758815.105 0 0, 427064.836 6758859.023 0 45.709)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (441619,148128280,5,12031022,0.000,31.532,3,'SRID=3067;LINESTRING ZM(427042.478 6758785.102 0 0, 427052.171 6758815.105 0 31.532)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (441620,148128280,6,12031032,22.298,78.042,3,'SRID=3067;LINESTRING ZM(427022.535 6758733.05 0 0, 427042.478 6758785.102 0 55.744)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (459297,191749399,1,12031399,0.000,57.197,3,'SRID=3067;LINESTRING ZM(426803.954 6758291.276 0 0, 426825.85 6758344.113 0 57.197)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (459298,191749399,2,12031402,0.000,108.083,3,'SRID=3067;LINESTRING ZM(426768.606 6758189.168 0 0, 426803.954 6758291.276 0 108.083)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (459299,191749399,3,12031429,33.119,34.123,3,'SRID=3067;LINESTRING ZM(426768.291 6758188.214 0 0, 426768.606 6758189.168 0 1.005)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (459520,191749978,1,12031394,1.998,167.833,3,'SRID=3067;LINESTRING ZM(426781.074 6758185.48 0 0, 426838.924 6758340.857 0 165.835)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469955,258681071,1,12031429,0.000,10.036,3,'SRID=3067;LINESTRING ZM(426758.142 6758156.689 0 0, 426761.167 6758166.258 0 10.036)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469956,258681071,2,12031433,0.000,6.965,3,'SRID=3067;LINESTRING ZM(426756.072 6758150.039 0 0, 426758.142 6758156.689 0 6.965)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469957,258681071,3,12031424,0.000,54.575,3,'SRID=3067;LINESTRING ZM(426739.748 6758097.988 0 0, 426756.072 6758150.039 0 54.575)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469958,258681071,4,12031132,391.470,462.193,3,'SRID=3067;LINESTRING ZM(426722.721 6758029.369 0 0, 426739.748 6758097.988 0 70.723)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469184,258681080,1,12031427,0.000,11.044,3,'SRID=3067;LINESTRING ZM(426770.605 6758153.001 0 0, 426773.939 6758163.53 0 11.044)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469185,258681080,2,12031435,0.000,6.963,3,'SRID=3067;LINESTRING ZM(426768.534 6758146.353 0 0, 426770.605 6758153.001 0 6.963)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469186,258681080,3,12031420,0.000,85.715,3,'SRID=3067;LINESTRING ZM(426743.664 6758064.354 0 0, 426768.534 6758146.353 0 85.715)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469187,258681080,4,12031637,255.884,296.866,3,'SRID=3067;LINESTRING ZM(426733.072 6758024.765 0 0, 426743.664 6758064.354 0 40.981)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470019,258705202,1,12031597,0.000,133.932,3,'SRID=3067;LINESTRING ZM(426482.643 6757077.218 0 0, 426557.092 6757188.229 0 133.932)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470020,258705202,2,12031603,0.000,128.211,3,'SRID=3067;LINESTRING ZM(426399.96 6756979.243 0 0, 426482.643 6757077.218 0 128.211)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470021,258705202,3,12031622,0.000,8.671,3,'SRID=3067;LINESTRING ZM(426394.139 6756972.816 0 0, 426399.96 6756979.243 0 8.671)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470022,258705202,4,12031618,0.000,25.304,3,'SRID=3067;LINESTRING ZM(426377.206 6756954.014 0 0, 426394.139 6756972.816 0 25.304)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470023,258705202,5,12031613,0.000,150.175,3,'SRID=3067;LINESTRING ZM(426276.194 6756842.892 0 0, 426377.206 6756954.014 0 150.175)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470024,258705202,6,12031591,0.000,275.496,3,'SRID=3067;LINESTRING ZM(426084.306 6756645.215 0 0, 426276.194 6756842.892 0 275.496)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470025,258705202,7,12031706,117.361,137.253,3,'SRID=3067;LINESTRING ZM(426069.911 6756631.489 0 0, 426084.306 6756645.215 0 19.892)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470390,258705205,1,12031706,52.713,117.361,3,'SRID=3067;LINESTRING ZM(426024.803 6756585.179 0 0, 426069.911 6756631.489 0 64.648)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (467868,258705208,1,12031030,0.000,27.364,3,'SRID=3067;LINESTRING ZM(426999.601 6758711.191 0 0, 427009.933 6758736.529 0 27.364)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (467869,258705208,2,12031385,0.000,148.075,3,'SRID=3067;LINESTRING ZM(426937.714 6758576.707 0 0, 426999.601 6758711.191 0 148.075)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (467870,258705208,3,12031388,0.000,14.763,3,'SRID=3067;LINESTRING ZM(426931.002 6758563.558 0 0, 426937.714 6758576.707 0 14.763)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (467871,258705208,4,12031414,0.000,85.396,3,'SRID=3067;LINESTRING ZM(426891.717 6758487.737 0 0, 426931.002 6758563.558 0 85.396)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (467872,258705208,5,12031419,0.000,6.436,3,'SRID=3067;LINESTRING ZM(426888.847 6758481.976 0 0, 426891.717 6758487.737 0 6.436)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (467873,258705208,6,12031411,0.000,93.424,3,'SRID=3067;LINESTRING ZM(426848.493 6758397.728 0 0, 426888.847 6758481.976 0 93.424)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (467874,258705208,7,12031399,57.197,115.397,3,'SRID=3067;LINESTRING ZM(426825.85 6758344.113 0 0, 426848.493 6758397.728 0 58.2)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470109,258705231,1,12031032,0.000,22.298,3,'SRID=3067;LINESTRING ZM(427014.159 6758712.385 0 0, 427022.535 6758733.05 0 22.298)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470110,258705231,2,12031386,0.000,161.619,3,'SRID=3067;LINESTRING ZM(426946.647 6758565.59 0 0, 427014.159 6758712.385 0 161.619)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470111,258705231,3,12031416,0.000,94.028,3,'SRID=3067;LINESTRING ZM(426903.422 6758482.089 0 0, 426946.647 6758565.59 0 94.028)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470112,258705231,4,12031418,0.000,6.443,3,'SRID=3067;LINESTRING ZM(426900.548 6758476.323 0 0, 426903.422 6758482.089 0 6.443)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (470113,258705231,5,12031394,167.833,316.685,3,'SRID=3067;LINESTRING ZM(426838.924 6758340.857 0 0, 426900.548 6758476.323 0 148.851)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469480,258705234,1,12031132,0.000,391.470,3,'SRID=3067;LINESTRING ZM(426659.156 6757643.498 0 0, 426722.721 6758029.369 0 391.47)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469481,258705234,2,12031646,0.000,75.785,3,'SRID=3067;LINESTRING ZM(426651.146 6757568.139 0 0, 426659.156 6757643.498 0 75.785)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469482,258705234,3,12031641,0.000,64.561,3,'SRID=3067;LINESTRING ZM(426643.054 6757504.095 0 0, 426651.146 6757568.139 0 64.561)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469483,258705234,4,12031657,0.000,4.236,3,'SRID=3067;LINESTRING ZM(426642.418 6757499.907 0 0, 426643.054 6757504.095 0 4.236)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469484,258705234,5,12031653,0.000,65.988,3,'SRID=3067;LINESTRING ZM(426632.024 6757434.743 0 0, 426642.418 6757499.907 0 65.988)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (469485,258705234,6,12031597,133.932,392.801,3,'SRID=3067;LINESTRING ZM(426557.092 6757188.229 0 0, 426632.024 6757434.743 0 258.869)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (471812,258707763,1,12031429,10.036,33.119,3,'SRID=3067;LINESTRING ZM(426761.167 6758166.258 0 0, 426768.291 6758188.214 0 23.083)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (481945,306168997,1,12031394,0.000,1.998,3,'SRID=3067;LINESTRING ZM(426780.446 6758183.583 0 0, 426781.074 6758185.48 0 1.998)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471'),
            (481946,306168997,2,12031427,11.044,32.127,3,'SRID=3067;LINESTRING ZM(426773.939 6758163.53 0 0, 426780.446 6758183.583 0 21.082)'::geometry,'2020-12-17 12:12:00',NULL,'import','2021-09-03 10:53:26.219471')""".execute

      sqlu"""INSERT INTO roadway (id,roadway_number,road_number,road_part_number,track,start_addr_m,end_addr_m,reversed,discontinuity,start_date,end_date,created_by,created_time,administrative_class,ely,terminated,valid_from,valid_to) VALUES
            (60705,48864590,167,1,2,4527,5389,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (60762,48864415,167,1,1,6197,6387,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (60858,48863580,167,1,2,2700,3522,0,5,'2011-10-01',NULL,'import','2021-09-03 10:53:26.219471',2,1,0,'2020-12-17 12:12:00',NULL),
            (61102,48863700,167,1,1,3522,3530,0,5,'2016-03-01',NULL,'import','2021-09-03 10:53:26.219471',2,1,0,'2020-12-17 12:12:00',NULL),
            (61119,48864204,167,1,2,5389,6132,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (61204,48863316,167,1,1,2700,3522,0,5,'2011-10-01',NULL,'import','2021-09-03 10:53:26.219471',2,1,0,'2020-12-17 12:12:00',NULL),
            (61241,48863145,167,1,1,525,2700,0,5,'2016-03-01',NULL,'import','2021-09-03 10:53:26.219471',2,1,0,'2020-12-17 12:12:00',NULL),
            (70209,148121596,167,1,1,3530,3760,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (72013,148121997,167,1,2,6132,6197,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (72057,148122184,167,1,0,0,525,0,5,'2010-01-01',NULL,'import','2021-09-03 10:53:26.219471',2,1,0,'2020-12-17 12:12:00',NULL),
            (72455,148127680,167,1,2,3522,3530,0,5,'2016-03-01',NULL,'import','2021-09-03 10:53:26.219471',2,1,0,'2020-12-17 12:12:00',NULL),
            (72613,148127681,167,1,2,6197,6387,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (74305,148128280,167,1,2,3530,3760,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (74952,148127788,167,1,2,525,2700,0,5,'2016-03-01',NULL,'import','2021-09-03 10:53:26.219471',2,1,0,'2020-12-17 12:12:00',NULL),
            (80266,191749399,167,1,1,4195,4361,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (80322,191749978,167,1,2,4195,4361,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (83162,258705208,167,1,1,3760,4195,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (83554,258681080,167,1,2,4384,4527,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (83659,258705234,167,1,1,4527,5389,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (83769,258681071,167,1,1,4384,4527,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (83782,258705202,167,1,1,5389,6132,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (83803,258705231,167,1,2,3760,4195,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (83886,258705205,167,1,1,6132,6197,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (84673,258707763,167,1,1,4361,4384,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL),
            (89359,306168997,167,1,2,4361,4384,0,5,'2020-12-08',NULL,'import','2021-09-03 10:53:26.219471',3,1,0,'2020-12-17 12:12:00',NULL)""".execute

      sqlu"""INSERT INTO project (id,state,"name",created_by,created_date,modified_by,modified_date,add_info,start_date,status_info,tr_id,coord_x,coord_y,zoom) VALUES
      (2000,1,'167_1','silari','2021-11-15 11:23:42.432965','silari','2021-11-15 11:23:42.432965','','2021-11-15','',NULL,427103.082,6758992.538,12)""".execute
      sqlu"""INSERT INTO project_reserved_road_part (id, road_number, road_part_number, project_id, created_by) VALUES(7021, 167, 1, 2000, 'silari')""".execute

      sqlu"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES
        (142701,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:34:59.359','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,267.149,11364553,1607036416000,4,'SRID=3067;LINESTRING Z(426650.135 6757508.684 71.71799999999348, 426677.048 6757707.239 72.21300000000338, 426685.976 6757773.418 72.57799999999406)'::geometry,0,0,-1000,0,0,0,0),
        (142166,2000,0,5,167,1,0,15,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:40:07.917',1,2,72057,437569,NULL,1,0,3,0.000,15.834,2525317,1446398762000,4,'SRID=3067;LINESTRING Z(427520.06 6762389.191 94.61199999999371, 427513.569 6762394.618 94.64599999999336, 427508.266 6762399.741 94.49900418830154)'::geometry,0,15,148122184,2,2,2,2),
        (142248,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:31:21.961','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,197.885,11265383,1607036416000,4,'SRID=3067;LINESTRING Z(426902.898 6758490.25 78.88300000000163, 426948.548 6758586.217 78.27300000000105, 426988.523 6758668.649 76.94199999999546)'::geometry,0,0,-1000,0,0,0,0),
        (142247,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:31:21.961','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,226.129,11256829,1607036416000,4,'SRID=3067;LINESTRING Z(426988.523 6758668.649 76.94199999999546, 426990.148 6758672.018 76.94000000000233, 427038.368 6758798.681 76.87699999999313, 427056.013 6758854.215 76.85000000000582, 427063.798 6758881.722 76.83699999999953)'::geometry,0,0,-1000,0,0,0,0),
        (142245,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:31:21.961','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,18.660,11265394,1607036416000,4,'SRID=3067;LINESTRING Z(426894.884 6758473.399 78.86500000000524, 426902.898 6758490.25 78.88300000000163)'::geometry,0,0,-1000,0,0,0,0),
        (142703,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:34:59.359','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,13.575,11265423,1607036416000,4,'SRID=3067;LINESTRING Z(426648.358 6757495.226 71.69599999999627, 426650.135 6757508.684 71.71799999999348)'::geometry,0,0,-1000,0,0,0,0),
        (142702,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:34:59.359','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,484.573,12031602,1607036416000,4,'SRID=3067;LINESTRING Z(426436.467 6757001.992 79.12600000000384, 426519.884 6757111.699 76.93700000000536, 426567.11 6757189.903 74.16400000000431, 426594.777 6757252.355 72.56699999999546, 426609.766 6757290.959 72.46400000000722, 426628.447 6757367.302 71.98600000000442, 426633.091 6757390.523 71.86599999999453, 426636.678 6757411.438 71.87099999999919, 426638.71 6757433.527 71.846000000005)'::geometry,0,0,-1000,0,0,0,0),
        (142604,2000,1,4,167,1,0,0,'silari','silari','2021-11-15 11:33:36.973','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,105.184,12031404,1607036416000,4,'SRID=3067;LINESTRING Z(426765.163 6758193.601 79.16800000000512, 426779.359 6758224.248 79.57000000000698, 426796.561 6758272.714 79.7719999999972, 426803.954 6758291.276 79.81100000000151)'::geometry,0,0,-1000,0,0,0,0),
        (142249,2000,2,4,167,1,0,0,'silari','silari','2021-11-15 11:31:21.961','2021-11-15 11:33:50.354',2,1,NULL,NULL,NULL,1,0,9,0.000,310.306,11265387,1607036416000,4,'SRID=3067;LINESTRING Z(426777.11 6758186.578 79.28100000000268, 426784.12 6758208.759 79.46799999999348, 426795.732 6758242.993 79.69700000000012, 426809.329 6758278.882 79.87099999999919, 426882.51 6758447.376 79.29099999999744, 426894.884 6758473.399 78.86500000000524)'::geometry,0,0,0,0,0,0,0),
        (142250,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:36:45.551','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,81.996,12031647,1607036416000,4,'SRID=3067;LINESTRING Z(426646.326 6757562.512 71.84500000000116, 426659.156 6757643.498 72.49700000000303)'::geometry,0,0,-1000,0,0,0,0),
        (142251,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:36:45.551','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,53.701,2535131,1607036416000,4,'SRID=3067;LINESTRING Z(426641.376 6757509.05 71.73500000000058, 426643.826 6757529.991 71.75, 426646.326 6757562.512 71.84500000000116)'::geometry,0,0,-1000,0,0,0,0),
        (142252,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:36:45.551','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,12.884,2535147,1607036416000,4,'SRID=3067;LINESTRING Z(426639.979 6757496.242 71.76900000000023, 426641.376 6757509.05 71.73500000000058)'::geometry,0,0,-1000,0,0,0,0),
        (142253,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:36:45.551','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,61.935,12031655,1607036416000,4,'SRID=3067;LINESTRING Z(426629.422 6757435.221 71.84399999999732, 426636.424 6757478.215 71.77700000000186, 426639.979 6757496.242 71.76900000000023)'::geometry,0,0,-1000,0,0,0,0),
        (142254,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:36:45.551','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,392.015,12031600,1607036416000,4,'SRID=3067;LINESTRING Z(426482.643 6757077.218 78.46799999999348, 426499.149 6757099.817 77.9539999999979, 426528.439 6757143.083 76.77700000000186, 426549.867 6757179.241 75.70500000000175, 426562.684 6757204.755 75.03599999999278, 426575.107 6757232.049 74.4210000000021, 426586.734 6757259.18 73.89800000000105, 426594.258 6757279.918 73.5280000000057, 426602.557 6757304.483 73.18899999999849, 426611.595 6757336.315 72.7219999999943, 426620.929 6757374.894 72.32399999999325, 426629.422 6757435.221 71.84399999999732)'::geometry,0,0,-1000,0,0,0,0),
        (142601,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:33:21.887','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,11.782,11238116,1607036416000,4,'SRID=3067;LINESTRING Z(426766.028 6758158.288 78.81699999999546, 426766.714 6758160.044 79.14800000000105, 426770.972 6758168.978 79.20299999999406)'::geometry,0,0,-1000,0,0,0,0),
        (142602,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:33:21.887','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,10.695,11238120,1607036416000,4,'SRID=3067;LINESTRING Z(426762.835 6758148.081 76.72500000000582, 426766.028 6758158.288 78.81699999999546)'::geometry,0,0,-1000,0,0,0,0),
        (142603,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:33:21.887','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,85.927,12031423,1607036416000,4,'SRID=3067;LINESTRING Z(426743.664 6758064.354 77.92900000000373, 426754.112 6758108.374 78.50599999999395, 426761.518 6758139.059 78.89800000000105, 426762.835 6758148.081 76.72500000000582)'::geometry,0,0,-1000,0,0,0,0),
        (142246,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:31:21.961','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,75.870,11256827,1607036416000,4,'SRID=3067;LINESTRING Z(427063.798 6758881.722 76.83699999999953, 427088.45 6758953.475 77.2390000000014)'::geometry,0,0,-1000,0,0,0,0),
        (142312,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:39:30.264','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,76.750,12031714,1607036416000,4,'SRID=3067;LINESTRING Z(425990.061 6756545.651 81.09299999999348, 426018.066 6756572.396 80.56100000000151, 426043.653 6756600.525 80.1530000000057)'::geometry,0,0,-1000,0,0,0,0),
        (142311,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:39:30.264','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,60.414,12031708,1607036416000,4,'SRID=3067;LINESTRING Z(426043.653 6756600.525 80.1530000000057, 426084.306 6756645.215 79.54499999999825)'::geometry,0,0,-1000,0,0,0,0),
        (142310,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:36:55.689','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,16.840,11364572,1607036416000,4,'SRID=3067;LINESTRING Z(426379.765 6756951.466 79.26300000000629, 426391.132 6756963.891 79.39500000000407)'::geometry,0,0,-1000,0,0,0,0),
        (142309,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:36:55.689','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,22.378,11364574,1607036416000,4,'SRID=3067;LINESTRING Z(426391.132 6756963.891 79.39500000000407, 426406.215 6756980.422 79.44800000000396)'::geometry,0,0,-1000,0,0,0,0),
        (142308,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:36:55.689','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,150.145,12031615,1607036416000,4,'SRID=3067;LINESTRING Z(426276.194 6756842.892 77.5, 426323.258 6756888.416 78.2390000000014, 426379.765 6756951.466 79.26300000000629)'::geometry,0,0,-1000,0,0,0,0),
        (142307,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:36:55.689','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,123.364,12031607,1607036416000,4,'SRID=3067;LINESTRING Z(426406.215 6756980.422 79.44800000000396, 426413.027 6756987.888 79.47400000000198, 426448.839 6757033.06 79.16999999999825, 426482.643 6757077.218 78.46799999999348)'::geometry,0,0,-1000,0,0,0,0),
        (142502,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:33:03.114','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,10.268,11238121,1607036416000,4,'SRID=3067;LINESTRING Z(426756.072 6758150.039 76.78699999999662, 426755.419 6758150.633 76.89800000000105, 426758.001 6758159.656 78.08100000000559)'::geometry,0,0,-1000,0,0,0,0),
        (142501,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:33:03.114','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,10.066,11238118,1607036416000,4,'SRID=3067;LINESTRING Z(426758.001 6758159.656 78.08100000000559, 426759.14 6758164.549 79.05899999999383, 426759.577 6758169.572 79.10099999999511)'::geometry,0,0,-1000,0,0,0,0),
        (142401,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:31:40.259','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,81.770,12030946,1607036416000,4,'SRID=3067;LINESTRING Z(427088.45 6758953.475 77.2390000000014, 427103.761 6758993.179 77.5850000000064, 427116.357 6759030.317 78.08999999999651)'::geometry,0,0,-1000,0,0,0,0),
        (142306,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:29:49.494','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,76.545,11256825,1607036416000,4,'SRID=3067;LINESTRING Z(427052.899 6758884.317 74.94500000000698, 427078.849 6758956.329 77.08599999999569)'::geometry,0,0,-1000,0,0,0,0),
        (142305,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:29:49.494','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,193.283,11265385,1607036416000,4,'SRID=3067;LINESTRING Z(426892.053 6758496.152 79.51300000000629, 426929.829 6758574.256 78.73799999999756, 426954.789 6758623.657 78.22900000000664, 426975.611 6758670.404 77.6030000000028)'::geometry,0,0,-1000,0,0,0,0),
        (142304,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:29:49.494','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,18.523,11265395,1607036416000,4,'SRID=3067;LINESTRING Z(426883.992 6758479.475 79.47500000000582, 426892.053 6758496.152 79.51300000000629)'::geometry,0,0,-1000,0,0,0,0),
        (142303,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:29:49.494','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,79.512,12030945,1607036416000,4,'SRID=3067;LINESTRING Z(427078.849 6758956.329 77.08599999999569, 427104.893 6759031.455 77.80899999999383)'::geometry,0,0,-1000,0,0,0,0),
        (142302,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:29:49.494','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,89.140,12031413,1607036416000,4,'SRID=3067;LINESTRING Z(426848.493 6758397.728 79.92600000000675, 426876.27 6758463.496 79.72699999999895, 426883.992 6758479.475 79.47500000000582)'::geometry,0,0,-1000,0,0,0,0),
        (142301,2000,1,5,167,1,0,0,'silari','silari','2021-11-15 11:29:49.494','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,227.670,11256831,1607036416000,4,'SRID=3067;LINESTRING Z(426975.611 6758670.404 77.6030000000028, 427000.481 6758727.059 76.88099999999395, 427030.064 6758816.328 75.78299999999581, 427045.115 6758857.848 75.2670000000071, 427052.899 6758884.317 74.94500000000698)'::geometry,0,0,-1000,0,0,0,0),
        (142708,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:39:51.123','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,7.883,11238296,1607036416000,4,'SRID=3067;LINESTRING Z(425957.122 6756479.731 81.23200000000361, 425961.21 6756486.471 78.63400000000547)'::geometry,0,0,-1000,0,0,0,0),
        (142707,2000,2,2,167,1,0,0,'silari','silari','2021-11-15 11:39:51.123','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,62.033,11238294,1607036416000,4,'SRID=3067;LINESTRING Z(425928.536 6756424.696 81.82399999999325, 425953.62 6756473.954 81.55400000000373, 425957.122 6756479.731 81.23200000000361)'::geometry,0,0,-1000,0,0,0,0),
        (142706,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:39:51.123','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,200.934,12031705,1607036416000,4,'SRID=3067;LINESTRING Z(425961.21 6756486.471 78.63400000000547, 425983.894 6756520.43 81.04899999999907, 426010.554 6756552.465 80.5850000000064, 426092.114 6756638.319 78.91400000000431)'::geometry,0,0,-1000,0,0,0,0),
        (142705,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:34:59.359','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,49.287,11535736,1607036416000,4,'SRID=3067;LINESTRING Z(426638.71 6757433.527 71.846000000005, 426642.772 6757463.742 71.77599999999802, 426645.584 6757478.507 71.76200000000244, 426645.819 6757479.739 71.74599999999919, 426646.259 6757482.216 71.73799999999756)'::geometry,0,0,-1000,0,0,0,0),
        (142704,2000,2,5,167,1,0,0,'silari','silari','2021-11-15 11:34:59.359','2021-11-15 00:00:00',2,1,NULL,NULL,NULL,1,0,9,0.000,13.178,11535734,1607036416000,4,'SRID=3067;LINESTRING Z(426646.259 6757482.216 71.73799999999756, 426648.358 6757495.226 71.69599999999627)'::geometry,0,0,-1000,0,0,0,0),
        (142167,2000,0,5,167,1,15,248,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:40:07.917',1,2,72057,437570,NULL,1,0,3,0.000,240.530,2525349,1446398762000,4,'SRID=3067;LINESTRING Z(427594.807 6762161.196 92.75599999999395, 427584.296 6762197.419 92.65799999999581, 427569.028 6762248.669 92.51499999999942, 427556.278 6762291.431 92.63000000000466, 427545.028 6762329.635 93.14999999999418, 427535.836 6762358.551 93.971000000005, 427529.418 6762373.294 94.37300000000687, 427525.496 6762380.811 94.41700000000128, 427520.06 6762389.191 94.61199981786554)'::geometry,15,248,148122184,2,0,2,0),
        (142168,2000,0,5,167,1,248,425,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:40:07.917',1,2,72057,437571,NULL,1,0,3,0.000,182.865,2525978,1446398762000,4,'SRID=3067;LINESTRING Z(427629.717 6761981.88 94.50999999999476, 427628.475 6761991.59 94.40499999999884, 427626.573 6762006.116 94.20200000000477, 427624.494 6762022.38 94.03500000000349, 427622.42 6762038.168 93.83400000000256, 427619.724 6762053.001 93.66099999999278, 427616.063 6762069.247 93.50599999999395, 427613.395 6762081.709 93.36500000000524, 427609.244 6762099.372 93.17200000000594, 427604.261 6762120.82 92.98799999999756, 427600.273 6762138.168 92.7899999999936, 427594.807 6762161.196 92.75599999999395)'::geometry,248,425,148122184,0,0,0,0),
        (142169,2000,0,5,167,1,425,525,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:40:07.917',1,2,72057,437572,NULL,1,0,3,0.000,102.119,2525965,1446398762000,4,'SRID=3067;LINESTRING Z(427622.364 6761880.299 96.6140000000014, 427623.139 6761884.783 96.52700000000186, 427628.516 6761925.502 95.49800000000687, 427630.049 6761943.861 95.09200000000419, 427630.468 6761962.841 94.73399999999674, 427630.682 6761972.014 94.56600000000617, 427629.717 6761981.88 94.51000034626254)'::geometry,425,525,148122184,0,2,0,2),
        (142180,2000,2,5,167,1,525,599,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444095,NULL,1,0,3,0.000,73.197,2525988,1446398762000,4,'SRID=3067;LINESTRING Z(427613.2 6761807.864 98.79899999999907, 427617.198 6761830.547 98.14100000000326, 427622.1 6761859.574 97.26600000000326, 427622.364 6761880.299 96.61401036245219)'::geometry,525,599,148127788,2,0,2,0),
        (142137,2000,1,5,167,1,525,598,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406260,NULL,1,0,3,0.000,72.672,11897533,1597186817000,4,'SRID=3067;LINESTRING Z(427605.528 6761809.626 99.04399999999441, 427610.795 6761829.279 98.44800000000396, 427615.516 6761847.804 97.7609999999986, 427622.364 6761880.299 96.61401294236344)'::geometry,525,598,48863145,2,0,2,0),
        (142138,2000,1,5,167,1,598,705,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406261,NULL,1,0,3,0.000,106.621,2525983,1446398762000,4,'SRID=3067;LINESTRING Z(427580.032 6761706.116 98.80299999999988, 427587.029 6761736.604 98.94000000000233, 427590.794 6761753.25 99.05199999999604, 427597.958 6761782.014 99.2219999999943, 427605.528 6761809.626 99.04399999999441)'::geometry,598,705,48863145,0,0,0,0),
        (142181,2000,2,5,167,1,599,706,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444096,NULL,1,0,3,0.000,106.307,2525984,1446398762000,4,'SRID=3067;LINESTRING Z(427593.613 6761703.387 98.5280000000057, 427599.123 6761735.51 98.66999999999825, 427605.476 6761766.164 98.83000000000175, 427613.2 6761807.864 98.79899999999907)'::geometry,599,706,148127788,0,0,0,0),
        (142139,2000,1,5,167,1,705,820,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406262,NULL,1,0,3,0.000,114.230,2525999,1446727681000,4,'SRID=3067;LINESTRING Z(427557.6 6761594.127 100.02599999999802, 427564.457 6761625.63 99.69199999999546, 427570.916 6761655.745 99.28500000000349, 427575.518 6761681.522 98.92299999999523, 427580.032 6761706.116 98.80299999999988)'::geometry,705,820,48863145,0,0,0,0),
        (142182,2000,2,5,167,1,706,820,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444097,NULL,1,0,3,0.000,114.165,2526000,1446398762000,4,'SRID=3067;LINESTRING Z(427569.624 6761591.776 100.00100000000384, 427575.842 6761622.47 99.62799999999697, 427582.148 6761652.109 99.15600000000268, 427588.314 6761680.324 98.75400000000081, 427593.213 6761701.657 98.55599999999686, 427593.613 6761703.387 98.5280000000057)'::geometry,706,820,148127788,0,0,0,0),
        (142183,2000,2,5,167,1,820,933,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444098,NULL,1,0,3,0.000,111.365,2526059,1446398762000,4,'SRID=3067;LINESTRING Z(427547.92 6761482.547 107.34200000000419, 427552.276 6761504.219 105.8289999999979, 427556.299 6761524.121 104.29099999999744, 427562.884 6761557.085 101.72000000000116, 427569.245 6761589.82 99.99700000000303, 427569.624 6761591.776 100.00100000000384)'::geometry,820,933,148127788,0,0,0,0),
        (142140,2000,1,5,167,1,820,830,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406263,NULL,1,0,3,0.000,9.888,11342949,1559689217000,4,'SRID=3067;LINESTRING Z(427555.466 6761584.472 100.11800000000221, 427556.042 6761587.164 100.05800000000454, 427557.6 6761594.127 100.02600048764536)'::geometry,820,830,48863145,0,0,0,0),
        (142141,2000,1,5,167,1,830,933,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406264,NULL,1,0,3,0.000,101.939,11342954,1559689217000,4,'SRID=3067;LINESTRING Z(427534.552 6761484.705 107.43499999999767, 427534.776 6761485.848 107.38300000000163, 427538.495 6761504.789 106.06100000000151, 427542.547 6761523.967 104.58999999999651, 427547.046 6761545.681 102.88700000000244, 427551.71 6761566.763 101.22299999999814, 427555.466 6761584.472 100.11800000000221)'::geometry,830,933,48863145,0,0,0,0),
        (142142,2000,1,5,167,1,933,1035,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406265,NULL,1,0,3,0.000,101.788,2526030,1446398762000,4,'SRID=3067;LINESTRING Z(427512.502 6761385.336 114.42500000000291, 427516.12 6761401.031 113.46899999999732, 427520.185 6761419.102 112.16700000000128, 427525.594 6761444.304 110.25400000000081, 427529.663 6761462.059 108.89599999999336, 427534.552 6761484.705 107.43500705388776)'::geometry,933,1035,48863145,0,0,0,0),
        (142184,2000,2,5,167,1,933,1037,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444099,NULL,1,0,3,0.000,103.437,2526031,1446398762000,4,'SRID=3067;LINESTRING Z(427524.593 6761381.776 114.38999999999942, 427527.602 6761395.409 113.72699999999895, 427532.874 6761418.712 111.97900000000664, 427537.429 6761438.113 110.5280000000057, 427543.548 6761463.714 108.50199999999313, 427547.92 6761482.547 107.34202987058687)'::geometry,933,1037,148127788,0,0,0,0),
        (142143,2000,1,5,167,1,1035,1118,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406266,NULL,1,0,3,0.000,81.885,2526038,1446398762000,4,'SRID=3067;LINESTRING Z(427496.014 6761305.142 114.46499999999651, 427499.04 6761317.193 114.42900000000373, 427502.782 6761335.894 114.53699999999662, 427508.019 6761362.2 114.57799999999406, 427512.502 6761385.336 114.42500237555366)'::geometry,1035,1118,48863145,0,0,0,0),
        (142185,2000,2,5,167,1,1037,1117,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444100,NULL,1,0,3,0.000,80.330,2526039,1446398762000,4,'SRID=3067;LINESTRING Z(427508.087 6761303.163 114.64500000000407, 427511.978 6761322.655 114.56900000000314, 427519.649 6761357.528 114.57099999999627, 427524.593 6761381.776 114.39000136404113)'::geometry,1037,1117,148127788,0,0,0,0),
        (142186,2000,2,5,167,1,1117,1297,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444101,NULL,1,0,3,0.000,178.432,2526027,1446398762000,4,'SRID=3067;LINESTRING Z(427438.949 6761139.206 107.26399999999558, 427447.097 6761159.695 107.83999999999651, 427459.419 6761188.611 109.21400000000722, 427482.306 6761233.933 111.2390000000014, 427494.372 6761257.47 112.40700000000652, 427500.031 6761274.453 113.28500000000349, 427504.121 6761290.311 113.93899999999849, 427508.087 6761303.163 114.64499067359776)'::geometry,1117,1297,148127788,0,0,0,0),
        (142144,2000,1,5,167,1,1118,1299,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406267,NULL,1,0,3,0.000,180.239,2526026,1446398762000,4,'SRID=3067;LINESTRING Z(427426.175 6761139.73 107.34500000000116, 427438.13 6761166.611 107.96700000000419, 427452.022 6761196.493 109.375, 427473.589 6761237.197 111.33000000000175, 427486.382 6761266.435 112.71300000000338, 427496.014 6761305.142 114.46498965949662)'::geometry,1118,1299,48863145,0,0,0,0),
        (142187,2000,2,5,167,1,1297,1307,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444102,NULL,1,0,3,0.000,9.878,2526145,1446398762000,4,'SRID=3067;LINESTRING Z(427435.505 6761129.979 107.06399999999849, 427436.875 6761134.738 107.18499999999767, 427438.949 6761139.206 107.26399725402119)'::geometry,1297,1307,148127788,0,0,0,0),
        (142145,2000,1,5,167,1,1299,1307,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406268,NULL,1,0,3,0.000,7.715,2526147,1446398762000,4,'SRID=3067;LINESTRING Z(427423.421 6761132.523 107.153999999995, 427426.175 6761139.73 107.3449933578338)'::geometry,1299,1307,48863145,0,0,0,0),
        (142146,2000,1,5,167,1,1307,1411,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406269,NULL,1,0,3,0.000,103.570,2526133,1446398762000,4,'SRID=3067;LINESTRING Z(427411.552 6761030.601 100.99400000000605, 427411.206 6761037.564 101.40899999999965, 427410.463 6761061.432 103.4320000000007, 427411.47 6761084.371 105.28699999999662, 427414.872 6761105.281 106.28699999999662, 427419.607 6761122.006 106.82799999999406, 427420.451 6761124.648 106.93499999999767, 427422.166 6761129.199 107.05100000000675, 427423.421 6761132.523 107.153999999995)'::geometry,1307,1411,48863145,0,0,0,0),
        (142188,2000,2,5,167,1,1307,1410,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444103,NULL,1,0,3,0.000,102.311,2526134,1446398762000,4,'SRID=3067;LINESTRING Z(427422.508 6761029.723 101.15899999999965, 427422.088 6761034.03 101.41199999999662, 427421.167 6761059.794 103.27400000000489, 427421.997 6761084.47 105.11000000000058, 427426.986 6761104.766 106.10199999999895, 427433.106 6761122.86 106.81200000000536, 427435.505 6761129.979 107.06398831200018)'::geometry,1307,1410,148127788,0,0,0,0),
        (142189,2000,2,5,167,1,1410,1422,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444104,NULL,1,0,3,0.000,12.177,2526164,1446398762000,4,'SRID=3067;LINESTRING Z(427423.696 6761017.604 99.98699999999371, 427422.508 6761029.723 101.15899140054573)'::geometry,1410,1422,148127788,0,0,0,0),
        (142147,2000,1,5,167,1,1411,1425,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406270,NULL,1,0,3,0.000,13.440,2526163,1446398762000,4,'SRID=3067;LINESTRING Z(427412.224 6761017.178 99.8070000000007, 427411.552 6761030.601 100.99400000000605)'::geometry,1411,1425,48863145,0,0,0,0),
        (142190,2000,2,5,167,1,1422,1489,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444105,NULL,1,0,3,0.000,66.294,6935769,1481065208000,4,'SRID=3067;LINESTRING Z(427423.389 6760951.339 98.08599999999569, 427423.588 6760953.904 98.21600000000035, 427424.405 6760982.509 99.33599999999569, 427424.186 6761002.731 100.01499999999942, 427423.696 6761017.604 99.98699999999371)'::geometry,1422,1489,148127788,0,0,0,0),
        (142148,2000,1,5,167,1,1425,1489,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406271,NULL,1,0,3,0.000,64.309,6935767,1481065208000,4,'SRID=3067;LINESTRING Z(427413.626 6760952.971 97.9320000000007, 427414.56 6760964.076 98.39299999999639, 427413.944 6760990.791 99.3859999999986, 427413.122 6761003.426 99.69700000000012, 427412.224 6761017.178 99.80699754103748)'::geometry,1425,1489,48863145,0,0,0,0),
        (142149,2000,1,5,167,1,1489,1512,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406272,NULL,1,0,3,0.000,22.427,6935785,1481065208000,4,'SRID=3067;LINESTRING Z(427411.401 6760930.657 97.33199999999488, 427412.604 6760941.126 97.55800000000454, 427413.626 6760952.971 97.9320000000007)'::geometry,1489,1512,48863145,0,0,0,0),
        (142191,2000,2,5,167,1,1489,1510,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444106,NULL,1,0,3,0.000,21.437,6935786,1481065208000,4,'SRID=3067;LINESTRING Z(427421.735 6760929.966 97.49000000000524, 427423.389 6760951.339 98.08599999999569)'::geometry,1489,1510,148127788,0,0,0,0),
        (142192,2000,2,5,167,1,1510,1576,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444107,NULL,1,0,3,0.000,64.798,2526384,1446727681000,4,'SRID=3067;LINESTRING Z(427413.393 6760865.734 97.14999999999418, 427418.686 6760899.232 97.08100000000559, 427421.735 6760929.966 97.48999386448489)'::geometry,1510,1576,148127788,0,0,0,0),
        (142150,2000,1,5,167,1,1512,1577,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406273,NULL,1,0,3,0.000,64.789,2526383,1446398762000,4,'SRID=3067;LINESTRING Z(427403.675 6760866.333 96.99400000000605, 427405.835 6760882.229 97.06600000000617, 427411.401 6760930.657 97.33199999999488)'::geometry,1512,1577,48863145,0,0,0,0),
        (142193,2000,2,5,167,1,1576,1632,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444108,NULL,1,0,3,0.000,56.578,2526386,1446398762000,4,'SRID=3067;LINESTRING Z(427403.036 6760810.16 97.92699999999604, 427406.021 6760823.378 98.08400000000256, 427407.282 6760828.911 98.10599999999977, 427412.095 6760853.976 97.49199999999837, 427413.393 6760865.734 97.15000220663512)'::geometry,1576,1632,148127788,0,0,0,0),
        (142151,2000,1,5,167,1,1577,1632,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406274,NULL,1,0,3,0.000,54.900,2526385,1446398762000,4,'SRID=3067;LINESTRING Z(427390.848 6760812.957 97.65799999999581, 427395.937 6760834.524 97.9210000000021, 427403.347 6760864.492 97.04899999999907, 427403.675 6760866.333 96.99400000000605)'::geometry,1577,1632,48863145,0,0,0,0),
        (142194,2000,2,5,167,1,1632,1750,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444109,NULL,1,0,3,0.000,117.218,2526391,1446398762000,4,'SRID=3067;LINESTRING Z(427380.098 6760695.345 94.16700000000128, 427381.253 6760708.762 94.68700000000536, 427384.74 6760726.012 95.41000000000349, 427387.919 6760741.6 96.0969999999943, 427390.508 6760757.286 96.58400000000256, 427395.182 6760777.499 97.20100000000093, 427398.792 6760793.983 97.55499999999302, 427403.036 6760810.16 97.92699809791667)'::geometry,1632,1750,148127788,0,0,0,0),
        (142152,2000,1,5,167,1,1632,1812,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406275,NULL,1,0,3,0.000,178.704,2526395,1446398762000,4,'SRID=3067;LINESTRING Z(427355.523 6760637.821 91.36699999999837, 427355.83 6760639.38 91.42200000000594, 427360.171 6760661.401 92.33000000000175, 427363.888 6760680.708 93.15099999999802, 427366.832 6760695.427 93.75699999999779, 427370.134 6760715.306 94.58900000000722, 427374.547 6760738.491 95.51399999999558, 427378.186 6760757.481 96.29200000000128, 427382.451 6760778.134 96.87799999999697, 427387.04 6760796.891 97.33999999999651, 427390.848 6760812.957 97.657998862983)'::geometry,1632,1812,48863145,0,0,0,0),
        (142195,2000,2,5,167,1,1750,1812,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444110,NULL,1,0,3,0.000,61.101,2526402,1446398762000,4,'SRID=3067;LINESTRING Z(427367.69 6760635.522 91.68499999999767, 427370.174 6760646.758 92.16999999999825, 427373.746 6760662.903 92.80299999999988, 427376.997 6760679.994 93.51200000000244, 427380.098 6760695.345 94.16698944152255)'::geometry,1750,1812,148127788,0,0,0,0),
        (142153,2000,1,5,167,1,1812,1986,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406276,NULL,1,0,3,0.000,172.454,2526477,1446398762000,4,'SRID=3067;LINESTRING Z(427328.091 6760467.716 87.13700000000244, 427328.85 6760475.309 87.16800000000512, 427330.385 6760486.7 87.24800000000687, 427332.996 6760505.845 87.46199999999953, 427334.827 6760522.14 87.67200000000594, 427337.14 6760536.697 87.94000000000233, 427339.928 6760551.099 88.30499999999302, 427341.138 6760564.703 88.67699999999604, 427343.453 6760578.785 89.08199999999488, 427345.773 6760591.762 89.56500000000233, 427348.223 6760605.375 90.10899999999674, 427351.952 6760621.677 90.72599999999511, 427355.523 6760637.821 91.36699862291212)'::geometry,1812,1986,48863145,0,0,0,0),
        (142196,2000,2,5,167,1,1812,1900,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444111,NULL,1,0,3,0.000,87.965,2526491,1446398762000,4,'SRID=3067;LINESTRING Z(427351.279 6760549.115 88.30400000000373, 427353.597 6760562.408 88.71899999999732, 427355.3 6760571.429 89.03399999999965, 427357.925 6760587.094 89.63000000000466, 427361.962 6760605.454 90.4210000000021, 427365.06 6760621.438 91.0969999999943, 427367.69 6760635.522 91.68499999999767)'::geometry,1812,1900,148127788,0,0,0,0),
        (142197,2000,2,5,167,1,1900,1986,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444112,NULL,1,0,3,0.000,85.168,2526484,1446398762000,4,'SRID=3067;LINESTRING Z(427339.445 6760464.783 87.17600000000675, 427342.223 6760481.557 87.27999999999884, 427344.986 6760501.967 87.4429999999993, 427347.134 6760518.263 87.66199999999662, 427349.429 6760537.09 88.06600000000617, 427351.279 6760549.115 88.30399124900279)'::geometry,1900,1986,148127788,0,0,0,0),
        (142198,2000,2,5,167,1,1986,2057,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444113,NULL,1,0,3,0.000,70.987,2526516,1446398762000,4,'SRID=3067;LINESTRING Z(427329.293 6760394.532 86.32700000000477, 427330.529 6760401.811 86.52000000000407, 427332.532 6760414.627 86.70900000000256, 427334.534 6760428.077 86.73600000000442, 427337.3 6760447.854 87.09100000000035, 427339.445 6760464.783 87.17600000000675)'::geometry,1986,2057,148127788,0,0,0,0),
        (142154,2000,1,5,167,1,1986,2059,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406277,NULL,1,0,3,0.000,72.911,2526472,1446398762000,4,'SRID=3067;LINESTRING Z(427316.839 6760395.721 86.24899999999616, 427318.074 6760403.158 86.39299999999639, 427320.079 6760415.659 86.63199999999779, 427321.913 6760431.163 86.25400000000081, 427324.848 6760448.411 87.04499999999825, 427326.07 6760458.853 87.06900000000314, 427328.091 6760467.716 87.13699696188102)'::geometry,1986,2059,48863145,0,0,0,0),
        (142199,2000,2,5,167,1,2057,2307,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444114,NULL,1,0,3,0.000,248.171,2526249,1446398762000,4,'SRID=3067;LINESTRING Z(427286.799 6760150.048 82.45699999999488, 427291.747 6760177.741 82.58999999999651, 427295.928 6760199.422 82.70500000000175, 427299.634 6760221.417 82.75, 427303.969 6760244.364 82.87099999999919, 427307.836 6760265.569 82.92299999999523, 427310.146 6760281.075 83.07099999999627, 427315.098 6760307.661 83.60899999999674, 427318.486 6760329.971 84.35000000000582, 427322.039 6760350.701 85.04099999999744, 427325.741 6760373.645 85.71499999999651, 427329.293 6760394.532 86.32700000000477)'::geometry,2057,2307,148127788,0,0,0,0),
        (142155,2000,1,5,167,1,2059,2307,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406278,NULL,1,0,3,0.000,246.483,2526247,1446398762000,4,'SRID=3067;LINESTRING Z(427275.152 6760152.795 82.41800000000512, 427278.707 6760173.05 82.5, 427281.954 6760191.249 82.58000000000175, 427285.502 6760213.243 82.63199999999779, 427288.602 6760233.075 82.76399999999558, 427293.549 6760261.084 82.83100000000559, 427300.351 6760299.537 83.31600000000617, 427303.744 6760320.425 83.89500000000407, 427308.496 6760347.457 84.79700000000594, 427312.05 6760368.187 85.53599999999278, 427314.828 6760384.643 85.94000000000233, 427316.839 6760395.721 86.24899269590439)'::geometry,2059,2307,48863145,0,0,0,0),
        (142156,2000,1,5,167,1,2307,2560,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406279,NULL,1,0,3,0.000,251.007,2526527,1446398762000,4,'SRID=3067;LINESTRING Z(427229.834 6759905.935 79.48600000000442, 427232.614 6759918.905 79.5969999999943, 427237.71 6759949.129 79.73200000000361, 427242.358 6759972.867 79.85000000000582, 427247.296 6760002.932 79.90200000000186, 427252.874 6760031.419 80.08900000000722, 427256.896 6760053.257 80.37200000000303, 427262.469 6760082.691 80.98799999999756, 427266.486 6760106.112 81.54099999999744, 427270.66 6760129.689 82.0969999999943, 427275.152 6760152.795 82.41799740929551)'::geometry,2307,2560,48863145,0,0,0,0),
        (142200,2000,2,5,167,1,2307,2558,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444115,NULL,1,0,3,0.000,249.380,2526526,1446398762000,4,'SRID=3067;LINESTRING Z(427244.418 6759904.317 79.58800000000338, 427249.939 6759935.823 79.79399999999441, 427253.798 6759958.926 79.83900000000722, 427258.601 6759983.456 79.90799999999581, 427262.085 6760005.258 80.01900000000023, 427266.869 6760034.532 80.24599999999919, 427270.74 6760054.631 80.43799999999464, 427275.053 6760082.953 81.02700000000186, 427279.229 6760105.899 81.64999999999418, 427283.243 6760130.109 82.15799999999581, 427286.799 6760150.048 82.45699999999488)'::geometry,2307,2558,148127788,0,0,0,0),
        (142201,2000,2,5,167,1,2558,2578,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444116,NULL,1,0,3,0.000,19.798,2526623,1446398762000,4,'SRID=3067;LINESTRING Z(427240.745 6759884.867 79.44199999999546, 427242.412 6759892.603 79.4539999999979, 427244.418 6759904.317 79.58799898113601)'::geometry,2558,2578,148127788,0,2,0,2),
        (142157,2000,1,5,167,1,2560,2579,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406280,NULL,1,0,3,0.000,19.100,2526624,1446398762000,4,'SRID=3067;LINESTRING Z(427227.224 6759887.017 79.34399999999732, 427228.276 6759893.593 79.403999999995, 427229.834 6759905.935 79.48600000000442)'::geometry,2560,2579,48863145,0,2,0,2),
        (142202,2000,2,5,167,1,2578,2695,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444117,NULL,1,0,3,0.000,116.421,2526615,1446398762000,4,'SRID=3067;LINESTRING Z(427222.092 6759769.971 78.74300000000221, 427228.179 6759810.536 79.02700000000186, 427235.58 6759849.657 79.32499999999709, 427240.745 6759884.867 79.44199999999546)'::geometry,2578,2695,148127788,2,0,2,0),
        (142158,2000,1,5,167,1,2579,2696,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406281,NULL,1,0,3,0.000,116.481,2526616,1446398762000,4,'SRID=3067;LINESTRING Z(427207.736 6759772.178 78.80800000000454, 427214.825 6759814.451 79.07300000000396, 427221.564 6759854.698 78.78500000000349, 427227.224 6759887.017 79.34399247898271)'::geometry,2579,2696,48863145,2,0,2,0),
        (142203,2000,2,5,167,1,2695,2700,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,74952,444118,NULL,1,0,3,87.748,92.678,2526652,1587596417000,4,'SRID=3067;LINESTRING Z(427221.144 6759765.133 78.59073615519705, 427222.092 6759769.971 78.74298704593551)'::geometry,2695,2700,148127788,0,0,0,0),
        (142159,2000,1,5,167,1,2696,2700,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61241,406282,NULL,1,0,3,74.442,78.412,2526656,1587596417000,4,'SRID=3067;LINESTRING Z(427207.061 6759768.266 78.22269753739812, 427207.736 6759772.178 78.80800000000454)'::geometry,2696,2700,48863145,0,0,0,0),
        (142129,2000,1,5,167,1,2700,2775,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61204,405967,NULL,1,0,3,0.000,74.442,2526656,1587596417000,4,'SRID=3067;LINESTRING Z(427194.09 6759694.967 78.44100000000617, 427195.896 6759706.574 78.5399999999936, 427203.7 6759748.8 75.30999999999767, 427207.061 6759768.266 78.22269753739812)'::geometry,2700,2775,48863316,0,0,0,0),
        (142113,2000,2,5,167,1,2700,2788,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,60858,403704,NULL,1,0,3,0.000,87.748,2526652,1587596417000,4,'SRID=3067;LINESTRING Z(427205.801 6759678.743 78.24000000000524, 427207.189 6759686.72 78.29499999999825, 427215.802 6759737.877 77.7329999999929, 427221.144 6759765.133 78.59073615519705)'::geometry,2700,2788,48863580,0,0,0,0),
        (142130,2000,1,5,167,1,2775,3226,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61204,405968,NULL,1,0,3,0.000,447.670,2532816,1587596417000,4,'SRID=3067;LINESTRING Z(427146.462 6759249.951 78.93399999999383, 427152.543 6759297.156 78.79799999999523, 427158.336 6759362.026 78.23099999999977, 427167.167 6759464.688 77.24499999999534, 427173.283 6759533.273 76.69700000000012, 427180.768 6759600.332 76.82200000000012, 427188.102 6759655.901 77.97699999999895, 427194.09 6759694.967 78.44099922275261)'::geometry,2775,3226,48863316,0,0,0,0),
        (142114,2000,2,5,167,1,2788,3224,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,60858,403705,NULL,1,0,3,0.000,432.962,2532817,1587596417000,4,'SRID=3067;LINESTRING Z(427159.358 6759248.352 75.50699999999779, 427162.245 6759279.635 79, 427165.431 6759307.147 78.86299999999756, 427169.05 6759347.256 77.36100000000442, 427174.263 6759404.597 76.68399999999383, 427179.33 6759462.314 76.44000000000233, 427183.674 6759505.753 76.20100000000093, 427187.988 6759540.053 76.00699999999779, 427196.033 6759599.101 76.69500000000698, 427202.402 6759648.109 77.86599999999453, 427205.801 6759678.743 78.24000000000524)'::geometry,2788,3224,48863580,0,0,0,0),
        (142115,2000,2,5,167,1,3224,3235,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,60858,403706,NULL,1,0,3,0.000,10.506,2532906,1587596417000,4,'SRID=3067;LINESTRING Z(427157.767 6759237.967 74.42900000000373, 427159.358 6759248.352 75.50698305534664)'::geometry,3224,3235,48863580,0,0,0,0),
        (142131,2000,1,5,167,1,3226,3237,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61204,405969,NULL,1,0,3,0.000,10.975,2532905,1587596417000,4,'SRID=3067;LINESTRING Z(427144.966 6759239.078 78.3289999999979, 427146.462 6759249.951 78.9339760928264)'::geometry,3226,3237,48863316,0,0,0,0),
        (142116,2000,2,5,167,1,3235,3246,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,60858,403707,NULL,1,0,3,0.000,11.624,2532903,1587596417000,4,'SRID=3067;LINESTRING Z(427156.001 6759226.478 74.30499999999302, 427157.767 6759237.967 74.42900000000373)'::geometry,3235,3246,48863580,0,0,0,0),
        (142132,2000,1,5,167,1,3237,3248,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61204,405970,NULL,1,0,3,0.000,10.812,2532902,1587596417000,4,'SRID=3067;LINESTRING Z(427143.491 6759228.367 78.69400000000314, 427144.966 6759239.078 78.32900281320198)'::geometry,3237,3248,48863316,0,0,0,0),
        (142117,2000,2,5,167,1,3246,3324,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,60858,403708,NULL,1,0,3,0.000,77.182,2532897,1587596417000,4,'SRID=3067;LINESTRING Z(427143.59 6759150.306 78.96300000000338, 427145.449 6759160.031 78.92900000000373, 427152.11 6759200.865 79.37099999999919, 427156.001 6759226.478 74.30499999999302)'::geometry,3246,3324,48863580,0,0,0,0),
        (142133,2000,1,5,167,1,3248,3320,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61204,405971,NULL,1,0,3,0.000,71.474,2532896,1587596417000,4,'SRID=3067;LINESTRING Z(427132.707 6759157.714 78.85000000000582, 427140.96 6759209.987 79.04700000000594, 427143.491 6759228.367 78.69400000000314)'::geometry,3248,3320,48863316,0,0,0,0),
        (142134,2000,1,5,167,1,3320,3403,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61204,405972,NULL,1,0,3,0.000,83.104,11535724,1587596417000,4,'SRID=3067;LINESTRING Z(427116.104 6759076.296 78.19100000000617, 427121.123 6759098.057 78.33299999999872, 427132.707 6759157.714 78.85000000000582)'::geometry,3320,3403,48863316,0,0,0,0),
        (142118,2000,2,5,167,1,3324,3362,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,60858,403709,NULL,1,0,3,0.000,37.893,2532907,1587596417000,4,'SRID=3067;LINESTRING Z(427136.472 6759113.088 78.32000000000698, 427143.59 6759150.306 78.96300000000338)'::geometry,3324,3362,48863580,0,0,0,0),
        (142119,2000,2,5,167,1,3362,3403,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,60858,403710,NULL,1,0,3,0.000,39.947,11535725,1587596417000,4,'SRID=3067;LINESTRING Z(427127.807 6759074.093 78.4600000000064, 427128.363 6759076.338 78.5399999999936, 427136.183 6759111.668 78.33100000000559, 427136.472 6759113.088 78.32000025435202)'::geometry,3362,3403,48863580,0,0,0,0),
        (142120,2000,2,5,167,1,3403,3449,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,60858,403711,NULL,1,0,3,0.000,45.249,12030926,1603839624000,4,'SRID=3067;LINESTRING Z(427116.357 6759030.317 78.08999999999651, 427127.807 6759074.093 78.4600000000064)'::geometry,3403,3449,48863580,0,0,0,0),
        (142135,2000,1,5,167,1,3403,3450,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',1,2,61204,405973,NULL,1,0,3,0.000,46.221,12030924,1603839624000,4,'SRID=3067;LINESTRING Z(427104.893 6759031.455 77.80899999999383, 427116.104 6759076.296 78.19099812603231)'::geometry,3403,3450,48863316,0,0,0,0),
        (142121,2000,2,5,167,1,3449,3522,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,2,60858,403712,NULL,1,0,3,7.937,81.355,12030943,1607036416000,4,'SRID=3067;LINESTRING Z(427096.753 6758959.565 77.28773110755552, 427116.357 6759030.317 78.08999999999651)'::geometry,3449,3522,48863580,0,0,0,0)""".execute

      sqlu"""INSERT INTO project_link (id,project_id,track,discontinuity_type,road_number,road_part_number,start_addr_m,end_addr_m,created_by,modified_by,created_date,modified_date,status,administrative_class,roadway_id,linear_location_id,connected_link_id,ely,reversed,side,start_measure,end_measure,link_id,adjusted_timestamp,link_source,geometry,original_start_addr_m,original_end_addr_m,roadway_number,start_calibration_point,end_calibration_point,orig_start_calibration_point,orig_end_calibration_point) VALUES
      (142136,2000,1,5,167,1,3450,3522,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,2,61204,405974,NULL,1,0,3,7.849,79.469,12030942,1607036416000,4,'SRID=3067;LINESTRING Z(427085.592 6758962.497 77.21279294444813, 427086.19 6758964.129 77.23600000000442, 427089.602 6758975.571 77.29799999999523, 427093.015 6758987.012 77.39599999999336, 427104.893 6759031.455 77.80899791105041)'::geometry,3450,3522,48863316,0,0,0,0),
      (142170,2000,2,5,167,1,3522,3530,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,2,72455,439425,NULL,1,0,3,0.000,7.937,12030943,1607036416000,4,'SRID=3067;LINESTRING Z(427094.634 6758951.916 77.20100000000093, 427096.753 6758959.565 77.28773110755552)'::geometry,3522,3530,148127680,0,2,0,2),
      (142122,2000,1,5,167,1,3522,3530,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,2,61102,405239,NULL,1,0,3,0.000,7.849,12030942,1607036416000,4,'SRID=3067;LINESTRING Z(427082.889 6758955.128 77.1079999999929, 427085.592 6758962.497 77.21279294444813)'::geometry,3522,3530,48863700,0,2,0,2),
      (142174,2000,2,5,167,1,3530,3585,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,74305,441615,NULL,1,0,3,0.000,54.385,12030953,1613432159000,4,'SRID=3067;LINESTRING Z(427077.819 6758900.196 76.9149999999936, 427094.634 6758951.916 77.20100000000093)'::geometry,3530,3585,148128280,2,0,2,0),
      (142160,2000,1,5,167,1,3530,3617,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,70209,433722,NULL,1,0,3,0.000,87.157,12030937,1613432159000,4,'SRID=3067;LINESTRING Z(427056.761 6758871.985 76.7280000000028, 427058.873 6758879.063 76.72900000000664, 427062.285 6758890.504 76.71300000000338, 427065.698 6758901.946 76.77000000000407, 427082.889 6758955.128 77.1079999999929)'::geometry,3530,3617,148121596,2,0,2,0),
      (142175,2000,2,5,167,1,3585,3618,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,74305,441616,NULL,1,0,3,0.000,33.234,12030957,1613432159000,4,'SRID=3067;LINESTRING Z(427067.876 6758868.484 76.79799999999523, 427068.86 6758871.562 76.83800000000338, 427071.313 6758879.362 76.86900000000605, 427071.86 6758881.112 76.87200000000303, 427074.84 6758890.655 76.91700000000128, 427077.819 6758900.196 76.91500006006812)'::geometry,3585,3618,148128280,0,0,0,0),
      (142161,2000,1,5,167,1,3617,3626,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,70209,433723,NULL,1,0,3,0.000,9.193,12030961,1613432159000,4,'SRID=3067;LINESTRING Z(427054.134 6758863.175 76.71799999999348, 427055.46 6758867.622 76.72299999999814, 427056.761 6758871.985 76.72799964281675)'::geometry,3617,3626,148121596,0,0,0,0),
      (142176,2000,2,5,167,1,3618,3628,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,74305,441617,NULL,1,0,3,0.000,9.937,12030959,1613432159000,4,'SRID=3067;LINESTRING Z(427064.836 6758859.023 76.83000000000175, 427065.812 6758862.025 76.82000000000698, 427067.876 6758868.484 76.79800141951935)'::geometry,3618,3628,148128280,0,0,0,0),
      (142162,2000,1,5,167,1,3626,3678,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,70209,433724,NULL,1,0,3,0.000,51.102,12031015,1613432159000,4,'SRID=3067;LINESTRING Z(427039.172 6758814.32 76.78100000000268, 427040.425 6758817.871 76.76300000000629, 427041.809 6758821.856 76.74199999999837, 427045.222 6758833.297 76.71199999999953, 427054.134 6758863.175 76.71799997782809)'::geometry,3626,3678,148121596,0,2,0,2),
      (142177,2000,2,5,167,1,3628,3674,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,74305,441618,NULL,1,0,3,0.000,45.709,12031017,1613432159000,4,'SRID=3067;LINESTRING Z(427052.171 6758815.105 76.69899999999325, 427053.157 6758818.277 76.71700000000419, 427056.135 6758828.518 76.69800000000396, 427064.836 6758859.023 76.83000000000175)'::geometry,3628,3674,148128280,0,2,0,2),
      (142178,2000,2,5,167,1,3674,3705,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,74305,441619,NULL,1,0,3,0.000,31.532,12031022,1613432159000,4,'SRID=3067;LINESTRING Z(427042.478 6758785.102 76.57399999999325, 427042.535 6758785.257 76.5789999999979, 427048.122 6758802.072 76.71499999999651, 427052.171 6758815.105 76.69899999999325)'::geometry,3674,3705,148128280,2,0,2,0),
      (142163,2000,1,5,167,1,3678,3710,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,70209,433725,NULL,1,0,3,0.000,32.438,12031020,1613432159000,4,'SRID=3067;LINESTRING Z(427028.149 6758783.813 76.91000000000349, 427030.336 6758789.735 76.84200000000419, 427033.746 6758799.097 76.82399999999325, 427037.109 6758808.475 76.80999999999767, 427039.172 6758814.32 76.78100000000268)'::geometry,3678,3710,148121596,2,0,2,0),
      (142179,2000,2,5,167,1,3705,3760,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,74305,441620,NULL,1,0,3,22.298,78.042,12031032,1613432159000,4,'SRID=3067;LINESTRING Z(427022.535 6758733.05 77.15022672854454, 427024.671 6758738.406 77.09100000000035, 427028.337 6758747.741 76.98699999999371, 427031.957 6758757.093 76.88300000000163, 427035.53 6758766.463 76.77999999999884, 427039.056 6758775.852 76.67600000000675, 427042.478 6758785.102 76.57399999999325)'::geometry,3705,3760,148128280,0,0,0,0),
      (142164,2000,1,5,167,1,3710,3760,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,70209,433726,NULL,1,0,3,27.364,78.037,12031030,1613432159000,4,'SRID=3067;LINESTRING Z(427009.933 6758736.529 77.18956802599187, 427012.588 6758743.188 77.14999999999418, 427016.23 6758752.461 77.09500000000116, 427019.826 6758761.753 77.0399999999936, 427023.376 6758771.063 76.98500000000058, 427026.879 6758780.39 76.92999999999302, 427028.149 6758783.813 76.91000000000349)'::geometry,3710,3760,148121596,0,0,0,0),
      (142236,2000,2,5,167,1,3760,3782,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83803,470109,NULL,1,0,3,0.000,22.298,12031032,1613432159000,4,'SRID=3067;LINESTRING Z(427014.159 6758712.385 77.38099999999395, 427017.198 6758719.794 77.29799999999523, 427020.957 6758729.091 77.19400000000314, 427022.535 6758733.05 77.15022672854454)'::geometry,3760,3782,258705231,0,0,0,0),
      (142208,2000,1,5,167,1,3760,3787,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83162,467868,NULL,1,0,3,0.000,27.364,12031030,1613432159000,4,'SRID=3067;LINESTRING Z(426999.601 6758711.191 77.33999999999651, 427001.382 6758715.477 77.31399999999849, 427005.163 6758724.695 77.25999999999476, 427008.898 6758733.932 77.20500000000175, 427009.933 6758736.529 77.18956802599187)'::geometry,3760,3787,258705208,0,0,0,0),
      (142237,2000,2,5,167,1,3782,3945,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83803,470110,NULL,1,0,3,0.000,161.619,12031386,1613432159000,4,'SRID=3067;LINESTRING Z(426946.647 6758565.59 77.90499999999884, 426950.795 6758573.77 77.85000000000582, 426955.285 6758582.736 77.83199999999488, 426959.731 6758591.726 77.91000000000349, 426964.132 6758600.737 77.6530000000057, 426968.487 6758609.77 77.65899999999965, 426972.798 6758618.826 77.64100000000326, 426977.063 6758627.901 77.59500000000116, 426981.283 6758636.999 77.6710000000021, 426985.457 6758646.117 77.6469999999972, 426989.585 6758655.257 77.67900000000373, 426993.668 6758664.416 77.67799999999988, 426997.704 6758673.596 77.63899999999558, 427001.696 6758682.796 77.72299999999814, 427005.64 6758692.017 77.57000000000698, 427009.539 6758701.256 77.58100000000559, 427013.392 6758710.515 77.37600000000384, 427014.159 6758712.385 77.3809992885737)'::geometry,3782,3945,258705231,0,2,0,2),
      (142209,2000,1,5,167,1,3787,3936,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83162,467869,NULL,1,0,3,0.000,148.075,12031385,1613432159000,4,'SRID=3067;LINESTRING Z(426937.714 6758576.707 78.84100000000035, 426939.19 6758579.617 78.81299999999464, 426943.651 6758588.526 78.70200000000477, 426948.069 6758597.457 78.62399999999616, 426952.441 6758606.41 78.47999999999593, 426956.768 6758615.384 78.3640000000014, 426961.05 6758624.382 78.27300000000105, 426965.288 6758633.399 78.15799999999581, 426969.48 6758642.438 78.0570000000007, 426973.627 6758651.496 77.93600000000151, 426977.729 6758660.577 77.875, 426981.785 6758669.677 77.74000000000524, 426985.796 6758678.797 77.64599999999336, 426989.761 6758687.938 77.57799999999406, 426993.68 6758697.098 77.47699999999895, 426997.554 6758706.279 77.39500000000407, 426999.601 6758711.191 77.33999999999651)'::geometry,3787,3936,258705208,0,2,0,2),
      (142210,2000,1,5,167,1,3936,3951,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83162,467870,NULL,1,0,3,0.000,14.763,12031388,1613432159000,4,'SRID=3067;LINESTRING Z(426931.002 6758563.558 79.00599999999395, 426934.684 6758570.731 78.89900000000489, 426937.714 6758576.707 78.84100068013333)'::geometry,3936,3951,258705208,2,2,2,2),
      (142238,2000,2,5,167,1,3945,4039,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83803,470111,NULL,1,0,3,0.000,94.028,12031416,1613432159000,4,'SRID=3067;LINESTRING Z(426903.422 6758482.089 75.71700000000419, 426918.45 6758511.641 77.99700000000303, 426923.054 6758520.481 77.94000000000233, 426927.7 6758529.303 77.96300000000338, 426930.103 6758533.83 77.95699999999488, 426932.388 6758538.134 77.93600000000151, 426937.055 6758547.006 77.93899999999849, 426941.679 6758555.904 77.89400000000023, 426946.259 6758564.825 77.90899999999965, 426946.647 6758565.59 77.90499999999884)'::geometry,3945,4039,258705231,2,0,2,0),
      (142211,2000,1,5,167,1,3951,4037,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83162,467871,NULL,1,0,3,0.000,85.396,12031414,1613432159000,4,'SRID=3067;LINESTRING Z(426891.717 6758487.737 76.78299999999581, 426906.911 6758517.619 79.43600000000151, 426911.542 6758526.51 79.33800000000338, 426916.212 6758535.377 79.25400000000081, 426918.625 6758539.923 79.20100000000093, 426920.899 6758544.206 79.18600000000151, 426925.539 6758553.027 79.1030000000028, 426930.134 6758561.867 79.028999999995, 426931.002 6758563.558 79.00599999999395)'::geometry,3951,4037,258705208,2,0,2,0),
      (142212,2000,1,5,167,1,4037,4043,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83162,467872,NULL,1,0,3,0.000,6.436,12031419,1613432159000,4,'SRID=3067;LINESTRING Z(426888.847 6758481.976 76.6359999999986, 426891.717 6758487.737 76.78299303591203)'::geometry,4037,4043,258705208,0,0,0,0),
      (142239,2000,2,5,167,1,4039,4045,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83803,470112,NULL,1,0,3,0.000,6.443,12031418,1613432159000,4,'SRID=3067;LINESTRING Z(426900.548 6758476.323 76.13800000000629, 426903.422 6758482.089 75.71700000000419)'::geometry,4039,4045,258705231,0,0,0,0),
      (142213,2000,1,5,167,1,4043,4137,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83162,467873,NULL,1,0,3,0.000,93.424,12031411,1613432159000,4,'SRID=3067;LINESTRING Z(426848.493 6758397.728 79.92600000000675, 426854.46 6758409.372 79.91599999999744, 426858.608 6758418.498 79.89999999999418, 426862.798 6758427.606 79.8469999999943, 426867.028 6758436.695 79.87300000000687, 426871.299 6758445.765 79.8289999999979, 426875.609 6758454.815 79.79399999999441, 426879.96 6758463.847 79.76499999999942, 426884.352 6758472.859 79.6649999999936, 426888.847 6758481.976 76.6359999999986)'::geometry,4043,4137,258705208,0,0,0,0),
      (142240,2000,2,5,167,1,4045,4195,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83803,470113,NULL,1,0,3,167.833,316.685,12031394,1613432159000,4,'SRID=3067;LINESTRING Z(426838.924 6758340.857 79.01015160975358, 426842.408 6758349.201 79.04099999999744, 426846.288 6758358.382 79.19400000000314, 426850.209 6758367.546 79.2219999999943, 426854.171 6758376.691 79.16400000000431, 426858.174 6758385.819 79.18899999999849, 426862.217 6758394.929 79.278999999995, 426866.301 6758404.021 79.28599999999278, 426870.426 6758413.094 79.17500000000291, 426874.591 6758422.149 79.1530000000057, 426878.797 6758431.185 79.23500000000058, 426883.043 6758440.203 79.05100000000675, 426887.329 6758449.202 78.95299999999406, 426891.655 6758458.181 78.76799999999639, 426896.021 6758467.14 78.57799999999406, 426900.548 6758476.323 76.13800000000629)'::geometry,4045,4195,258705231,0,0,0,0),
      (142214,2000,1,5,167,1,4137,4195,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:28:08.878',3,1,83162,467874,NULL,1,0,3,57.197,115.397,12031399,1613432159000,4,'SRID=3067;LINESTRING Z(426825.85 6758344.113 79.90310598875506, 426848.493 6758397.728 79.92599991398635)'::geometry,4137,4195,258705208,0,0,0,0),
      (142204,2000,1,5,167,1,4195,4252,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:28:08.878',3,1,80266,459297,NULL,1,0,3,0.000,57.197,12031399,1613432159000,4,'SRID=3067;LINESTRING Z(426803.954 6758291.276 79.81100000000151, 426810.909 6758308.737 79.88800000000629, 426825.85 6758344.113 79.90310598875506)'::geometry,4195,4252,191749399,0,0,0,0),
      (142207,2000,2,5,167,1,4195,4361,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,80322,459520,NULL,1,0,3,1.998,167.833,12031394,1613432159000,4,'SRID=3067;LINESTRING Z(426781.074 6758185.48 79.2381194990005, 426782.782 6758190.644 79.2329999999929, 426785.955 6758200.092 79.20100000000093, 426789.17 6758209.527 79.2899999999936, 426792.427 6758218.947 79.29700000000594, 426795.726 6758228.352 79.31399999999849, 426799.067 6758237.742 79.35899999999674, 426802.45 6758247.117 79.29799999999523, 426805.875 6758256.478 79.33599999999569, 426809.341 6758265.821 79.30999999999767, 426812.849 6758275.151 79.32499999999709, 426816.4 6758284.465 79.19199999999546, 426819.991 6758293.762 79.27700000000186, 426823.624 6758303.043 79.21199999999953, 426827.299 6758312.308 79.16400000000431, 426831.013 6758321.557 79.13800000000629, 426834.771 6758330.788 79.04499999999825, 426838.568 6758340.004 79.00699999999779, 426838.924 6758340.857 79.01015160975358)'::geometry,4195,4361,191749978,0,0,0,0),
      (142205,2000,1,5,167,1,4252,4360,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,80266,459298,NULL,1,0,3,0.000,108.083,12031402,1613432159000,4,'SRID=3067;LINESTRING Z(426768.606 6758189.168 79.31500000000233, 426770.454 6758194.753 79.32700000000477, 426773.645 6758204.256 79.43300000000454, 426776.879 6758213.746 79.54399999999441, 426780.155 6758223.221 79.5850000000064, 426783.474 6758232.68 79.5850000000064, 426786.834 6758242.125 79.67799999999988, 426790.236 6758251.554 79.70299999999406, 426793.681 6758260.97 79.75199999999313, 426797.168 6758270.368 79.79700000000594, 426800.697 6758279.752 79.8179999999993, 426803.954 6758291.276 79.8110001320308)'::geometry,4252,4360,191749399,0,0,0,0),
      (142206,2000,1,5,167,1,4360,4361,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,80266,459299,NULL,1,0,3,33.119,34.123,12031429,1613432159000,4,'SRID=3067;LINESTRING Z(426768.291 6758188.214 79.30942366739795, 426768.606 6758189.168 79.3149979516927)'::geometry,4360,4361,191749399,0,0,0,0),
      (142243,2000,2,5,167,1,4361,4363,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,89359,481945,NULL,1,0,3,0.000,1.998,12031394,1613432159000,4,'SRID=3067;LINESTRING Z(426780.446 6758183.583 79.24000000000524, 426781.074 6758185.48 79.2381194990005)'::geometry,4361,4363,306168997,0,0,0,0),
      (142242,2000,1,5,167,1,4361,4384,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,84673,471812,NULL,1,0,3,10.036,33.119,12031429,1613432159000,4,'SRID=3067;LINESTRING Z(426761.167 6758166.258 79.13391547854789, 426764.198 6758175.704 79.221000000005, 426767.305 6758185.235 79.29200000000128, 426768.291 6758188.214 79.30942366739795)'::geometry,4361,4384,258707763,0,0,0,0),
      (142244,2000,2,5,167,1,4363,4384,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,89359,481946,NULL,1,0,3,11.044,32.127,12031427,1613432159000,4,'SRID=3067;LINESTRING Z(426773.939 6758163.53 79.04161461373407, 426776.563 6758171.705 79.03299999999581, 426780.446 6758183.583 79.24000000000524)'::geometry,4363,4384,306168997,0,0,0,0),
      (142215,2000,2,5,167,1,4384,4395,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83554,469184,NULL,1,0,3,0.000,11.044,12031427,1613432159000,4,'SRID=3067;LINESTRING Z(426770.605 6758153.001 75.5280000000057, 426773.517 6758162.215 79.04300000000512, 426773.939 6758163.53 79.04161461373407)'::geometry,4384,4395,258681080,0,0,0,0),
      (142225,2000,1,5,167,1,4384,4394,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83769,469955,NULL,1,0,3,0.000,10.036,12031429,1613432159000,4,'SRID=3067;LINESTRING Z(426758.142 6758156.689 75.95699999999488, 426761.135 6758166.159 79.13300000000163, 426761.167 6758166.258 79.13391547854789)'::geometry,4384,4394,258681071,0,0,0,0),
      (142226,2000,1,5,167,1,4394,4401,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83769,469956,NULL,1,0,3,0.000,6.965,12031433,1613432159000,4,'SRID=3067;LINESTRING Z(426756.072 6758150.039 76.78699999999662, 426758.142 6758156.689 75.95699999999488)'::geometry,4394,4401,258681071,0,0,0,0),
      (142216,2000,2,5,167,1,4395,4402,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83554,469185,NULL,1,0,3,0.000,6.963,12031435,1613432159000,4,'SRID=3067;LINESTRING Z(426768.534 6758146.353 75.96400000000722, 426770.513 6758152.711 75.54700000000594, 426770.605 6758153.001 75.52800733649632)'::geometry,4395,4402,258681080,0,0,0,0),
      (142227,2000,1,5,167,1,4401,4456,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:36:06.069',3,3,83769,469957,NULL,1,0,3,0.000,54.575,12031424,1613432159000,4,'SRID=3067;LINESTRING Z(426739.748 6758097.988 78.4539999999979, 426749.819 6758132.685 78.79499999999825, 426756.072 6758150.039 76.78702223882152)'::geometry,4401,4456,258681071,0,0,0,0),
      (142217,2000,2,5,167,1,4402,4487,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83554,469186,NULL,1,0,3,0.000,85.715,12031420,1613432159000,4,'SRID=3067;LINESTRING Z(426743.664 6758064.354 77.92900000000373, 426748.016 6758076.219 78.096000000005, 426750.679 6758085.824 78.18799999999464, 426753.385 6758095.417 78.20200000000477, 426756.132 6758104.997 78.23600000000442, 426758.923 6758114.565 78.39900000000489, 426761.757 6758124.121 78.50999999999476, 426764.633 6758133.664 78.61599999999453, 426767.552 6758143.195 78.34500000000116, 426768.534 6758146.353 75.96400000000722)'::geometry,4402,4487,258681080,0,0,0,0),
      (142228,2000,1,5,167,1,4456,4527,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:36:06.069',3,3,83769,469958,NULL,1,0,3,391.470,462.193,12031132,1613432159000,4,'SRID=3067;LINESTRING Z(426722.721 6758029.369 77.67885092922758, 426723.257 6758031.604 77.71199999999953, 426724.882 6758040.382 77.7899999999936, 426734.439 6758078.727 78.26900000000023, 426739.748 6758097.988 78.4539979438889)'::geometry,4456,4527,258681071,0,0,0,0),
      (142218,2000,2,5,167,1,4487,4527,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:34:13.759',3,3,83554,469187,NULL,1,0,3,255.884,296.866,12031637,1613432159000,4,'SRID=3067;LINESTRING Z(426733.072 6758024.765 76.97603284988446, 426743.664 6758064.354 77.92900000000373)'::geometry,4487,4527,258681080,0,0,0,0),
      (142219,2000,1,5,167,1,4527,4919,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:36:06.069',3,3,83659,469480,NULL,1,0,3,0.000,391.470,12031132,1613432159000,4,'SRID=3067;LINESTRING Z(426659.156 6757643.498 72.49700000000303, 426665.698 6757704.14 73.15700000000652, 426670.249 6757744.126 73.67799999999988, 426676.809 6757794.63 74.33000000000175, 426686.183 6757854.384 75.1469999999972, 426692.031 6757890.079 75.67299999999523, 426700.235 6757931.359 76.27099999999336, 426711.362 6757982.042 76.97699999999895, 426722.721 6758029.369 77.67885092922758)'::geometry,4527,4919,258705234,0,0,0,0),
      (142101,2000,2,5,167,1,4527,4784,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:34:13.759',3,3,60705,402889,NULL,1,0,3,0.000,255.884,12031637,1613432159000,4,'SRID=3067;LINESTRING Z(426685.976 6757773.418 72.57799999999406, 426694.312 6757835.208 73.30199999999604, 426722.241 6757981.459 75.95600000000559, 426731.793 6758019.986 76.86100000000442, 426733.072 6758024.765 76.97603284988446)'::geometry,4527,4784,48864590,0,0,0,0),
      (142102,2000,2,5,167,1,4784,4925,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,60705,402890,NULL,1,0,3,0.000,140.747,12031633,1613432159000,4,'SRID=3067;LINESTRING Z(426671.284 6757633.451 72.15899999999965, 426671.414 6757634.652 72.1710000000021, 426672.489 6757644.59 72.3179999999993, 426673.565 6757654.528 72.27599999999802, 426674.64 6757664.467 72.47699999999895, 426675.715 6757674.405 72.32200000000012, 426676.79 6757684.342 72.33100000000559, 426677.866 6757694.28 72.44899999999325, 426678.941 6757704.219 72.49499999999534, 426680.016 6757714.157 72.49400000000605, 426681.091 6757724.092 72.58199999999488, 426681.169 6757724.801 72.53299999999581, 426682.183 6757734.003 72.46199999999953, 426683.318 6757743.906 72.5969999999943, 426684.498 6757753.803 72.63099999999395, 426685.976 6757773.418 72.57799999999406)'::geometry,4784,4925,48864590,0,0,0,0),
      (142220,2000,1,5,167,1,4919,4995,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83659,469481,NULL,1,0,3,0.000,75.785,12031646,1613432159000,4,'SRID=3067;LINESTRING Z(426651.146 6757568.139 71.93700000000536, 426652.043 6757576.423 71.98200000000361, 426653.118 6757586.361 72.08599999999569, 426654.193 6757596.299 72.11999999999534, 426655.268 6757606.236 72.16199999999662, 426656.344 6757616.174 72.24099999999453, 426657.42 6757626.112 72.3469999999943, 426658.494 6757636.05 72.41199999999662, 426659.156 6757643.498 72.49700000000303)'::geometry,4919,4995,258705234,0,0,0,0),
      (142103,2000,2,5,167,1,4925,5057,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,60705,402891,NULL,1,0,3,0.000,132.370,12031638,1613432159000,4,'SRID=3067;LINESTRING Z(426659.362 6757501.627 71.47900000000664, 426659.843 6757504.793 71.5679999999993, 426661.155 6757519.783 71.25699999999779, 426662.467 6757534.773 71.24099999999453, 426663.779 6757549.763 71.26399999999558, 426665.091 6757564.753 71.46300000000338, 426666.403 6757579.744 71.70299999999406, 426667.715 6757594.734 71.73799999999756, 426669.027 6757609.724 71.91099999999278, 426670.339 6757624.714 72.07099999999627, 426671.284 6757633.451 72.15899999999965)'::geometry,4925,5057,48864590,0,0,0,0),
      (142221,2000,1,5,167,1,4995,5059,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83659,469482,NULL,1,0,3,0.000,64.561,12031641,1613432159000,4,'SRID=3067;LINESTRING Z(426643.054 6757504.095 71.77599999999802, 426643.534 6757507.252 71.75400000000081, 426644.348 6757512.693 71.77400000000489, 426644.993 6757517.09 71.79700000000594, 426646.363 6757526.934 71.778999999995, 426647.633 6757536.784 71.81500000000233, 426648.807 6757546.653 71.87399999999616, 426649.41 6757552.088 71.90099999999802, 426649.892 6757556.547 71.90600000000268, 426650.967 6757566.485 71.91599999999744, 426651.146 6757568.139 71.93700000000536)'::geometry,4995,5059,258705234,0,0,0,0),
      (142104,2000,2,5,167,1,5057,5062,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,60705,402892,NULL,1,0,3,0.000,4.238,12031656,1613432159000,4,'SRID=3067;LINESTRING Z(426658.726 6757497.437 71.49899999999616, 426659.362 6757501.627 71.47900000000664)'::geometry,5057,5062,48864590,0,0,0,0),
      (142222,2000,1,5,167,1,5059,5064,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83659,469483,NULL,1,0,3,0.000,4.236,12031657,1613432159000,4,'SRID=3067;LINESTRING Z(426642.418 6757499.907 71.80599999999686, 426643.054 6757504.095 71.7760001203744)'::geometry,5059,5064,258705234,0,0,0,0),
      (142105,2000,2,5,167,1,5062,5084,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,60705,402893,NULL,1,0,3,0.000,22.503,12038619,1613432159000,4,'SRID=3067;LINESTRING Z(426655.262 6757475.202 71.71799999999348, 426655.599 6757477.326 71.6420000000071, 426656.804 6757484.98 70.9719999999943, 426658.338 6757494.885 71.41099999999278, 426658.726 6757497.437 71.49899143800388)'::geometry,5062,5084,48864590,0,0,0,0),
      (142223,2000,1,5,167,1,5064,5130,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83659,469484,NULL,1,0,3,0.000,65.988,12031653,1613432159000,4,'SRID=3067;LINESTRING Z(426632.024 6757434.743 71.90600000000268, 426632.628 6757438.251 71.88000000000466, 426634.253 6757448.076 71.81500000000233, 426635.032 6757452.936 71.84100000000035, 426635.823 6757457.92 71.8579999999929, 426637.388 6757467.793 71.81200000000536, 426638.953 6757477.665 71.78800000000047, 426639.308 6757479.9 71.80499999999302, 426640.508 6757487.524 71.82000000000698, 426642.035 6757497.386 71.77599999999802, 426642.418 6757499.907 71.80599553731894)'::geometry,5064,5130,258705234,0,0,0,0),
      (142106,2000,2,5,167,1,5084,5128,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,60705,402894,NULL,1,0,3,0.000,44.078,12038618,1613432159000,4,'SRID=3067;LINESTRING Z(426645.944 6757432.121 71.79200000000128, 426647.355 6757438.225 71.72000000000116, 426649.985 6757450.511 70.83400000000256, 426652.614 6757462.796 70.875, 426655.243 6757475.082 71.721000000005, 426655.262 6757475.202 71.71800212866246)'::geometry,5084,5128,48864590,0,0,0,0),
      (142107,2000,2,5,167,1,5128,5389,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,60705,402895,NULL,1,0,3,226.899,486.784,12031594,1613432159000,4,'SRID=3067;LINESTRING Z(426569.919 6757184.724 74.39156843888222, 426570.753 6757186.324 74.36299999999756, 426575.273 6757195.361 74.08000000000175, 426579.642 6757204.471 73.75199999999313, 426583.86 6757213.652 73.44500000000698, 426587.914 6757222.883 73.21099999999569, 426588.789 6757224.929 73.16199999999662, 426591.848 6757232.123 73.17999999999302, 426595.363 6757240.391 73.41899999999441, 426595.774 6757241.358 73.36699999999837, 426599.613 6757250.699 73.42799999999988, 426603.276 6757260.137 73.37799999999697, 426606.75 6757269.648 73.41300000000047, 426610.033 6757279.226 73.31900000000314, 426613.126 6757288.867 73.2390000000014, 426616.026 6757298.569 72.86900000000605, 426618.731 6757308.327 72.52300000000105, 426621.242 6757318.135 72.35199999999895, 426623.551 6757327.968 72.36999999999534, 426624.085 6757330.373 72.35099999999511, 426625.711 6757337.786 72.27599999999802, 426627.853 6757347.549 69.89500000000407, 426629.994 6757357.313 70.19700000000012, 426631.463 6757364.013 70.48500000000058, 426632.136 6757367.098 70.57499999999709, 426634.209 6757376.796 70.50299999999697, 426636.838 6757389.081 70.56200000000536, 426639.468 6757401.367 70.80800000000454, 426642.097 6757413.653 71.0460000000021, 426644.726 6757425.939 71.77300000000105, 426645.944 6757432.121 71.79200000000128)'::geometry,5128,5389,48864590,0,0,0,0),
      (142224,2000,1,5,167,1,5130,5389,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83659,469485,NULL,1,0,3,133.932,392.801,12031597,1613432159000,4,'SRID=3067;LINESTRING Z(426557.092 6757188.229 75.47231883288767, 426559.179 6757192.234 75.34900000000198, 426563.603 6757201.078 75.11599999999453, 426567.878 6757209.993 74.93300000000454, 426572.006 6757218.978 74.74099999999453, 426575.991 6757228.051 74.528999999995, 426576.836 6757230.026 74.48500000000058, 426579.889 6757237.207 74.36100000000442, 426583.403 6757245.474 74.22299999999814, 426583.784 6757246.37 74.20600000000559, 426587.545 6757255.52 74.06500000000233, 426591.114 6757264.717 73.82300000000396, 426594.499 6757273.985 73.77999999999884, 426597.699 6757283.318 73.5219999999972, 426600.712 6757292.713 73.36500000000524, 426603.538 6757302.166 73.2609999999986, 426606.174 6757311.674 73.18700000000536, 426608.621 6757321.232 72.98399999999674, 426610.883 6757330.862 72.8350000000064, 426611.396 6757333.173 72.78599999999278, 426613.017 6757340.57 72.64999999999418, 426615.16 6757350.333 72.5170000000071, 426617.301 6757360.097 72.43700000000536, 426618.768 6757366.79 72.375, 426619.435 6757369.842 72.30400000000373, 426621.516 6757379.582 72.28399999999965, 426623.531 6757389.328 72.18700000000536, 426625.48 6757399.088 72.0570000000007, 426627.365 6757408.861 72.07499999999709, 426629.185 6757418.646 71.96899999999732, 426630.939 6757428.443 71.91599999999744, 426632.024 6757434.743 71.90600042082791)'::geometry,5130,5389,258705234,0,0,0,0),
      (142123,2000,2,5,167,1,5389,5616,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,61119,405333,NULL,1,0,3,0.000,226.899,12031594,1613432159000,4,'SRID=3067;LINESTRING Z(426436.467 6757001.992 79.12600000000384, 426441.468 6757007.381 79.125, 426447.859 6757015.067 79.02300000000105, 426454.251 6757022.753 78.85199999999895, 426460.642 6757030.439 78.60599999999977, 426467.034 6757038.124 78.58199999999488, 426473.425 6757045.81 78.70200000000477, 426479.817 6757053.496 78.73799999999756, 426486.208 6757061.182 78.89900000000489, 426492.601 6757068.868 78.66000000000349, 426493.52 6757069.975 78.59900000000198, 426499.003 6757076.651 78.26799999999639, 426505.3 6757084.529 77.86900000000605, 426511.483 6757092.496 77.40099999999802, 426516.553 6757099.208 76.99300000000221, 426517.571 6757100.575 76.92900000000373, 426523.51 6757108.723 76.4600000000064, 426529.325 6757116.985 75.94000000000233, 426535.003 6757125.343 75.66099999999278, 426540.541 6757133.794 75.33299999999872, 426545.937 6757142.336 75.05499999999302, 426551.191 6757150.966 74.96600000000035, 426556.301 6757159.683 74.78800000000047, 426561.266 6757168.482 74.72900000000664, 426566.084 6757177.364 74.52300000000105, 426569.919 6757184.724 74.39156843888222)'::geometry,5389,5616,48864204,0,0,0,0),
      (142229,2000,1,5,167,1,5389,5523,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83782,470019,NULL,1,0,3,0.000,133.932,12031597,1613432159000,4,'SRID=3067;LINESTRING Z(426482.643 6757077.218 78.46799999999348, 426483.501 6757078.25 78.42900000000373, 426488.906 6757084.832 78.28100000000268, 426495.091 6757092.569 78.11900000000605, 426501.165 6757100.396 77.9030000000057, 426506.157 6757107.004 77.70500000000175, 426507.109 6757108.282 77.67900000000373, 426512.945 6757116.29 77.44599999999627, 426518.637 6757124.376 77.17600000000675, 426524.193 6757132.555 76.97000000000116, 426529.613 6757140.826 76.77400000000489, 426534.894 6757149.185 76.57600000000093, 426540.036 6757157.631 76.32799999999406, 426545.036 6757166.162 76.12300000000687, 426549.895 6757174.773 75.84399999999732, 426554.61 6757183.465 75.61900000000605, 426557.092 6757188.229 75.47231883288767)'::geometry,5389,5523,258705202,0,0,0,0),
      (142230,2000,1,5,167,1,5523,5652,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83782,470020,NULL,1,0,3,0.000,128.211,12031603,1613432159000,4,'SRID=3067;LINESTRING Z(426399.96 6756979.243 79.1530000000057, 426400.599 6756979.95 79.36199999999371, 426405.596 6756985.508 79.36599999999453, 426412.173 6756992.954 79.38800000000629, 426418.672 6757000.463 79.37600000000384, 426425.101 6757008.043 79.31699999999546, 426428.125 6757011.661 79.29799999999523, 426431.477 6757015.691 79.27499999999418, 426437.868 6757023.376 79.23500000000058, 426444.259 6757031.062 79.13700000000244, 426450.651 6757038.748 79.05199999999604, 426457.042 6757046.433 78.95500000000175, 426463.434 6757054.119 78.85099999999511, 426469.825 6757061.805 78.73200000000361, 426476.217 6757069.491 78.58000000000175, 426482.643 6757077.218 78.46800172991661)'::geometry,5523,5652,258705202,0,0,0,0),
      (142124,2000,2,5,167,1,5616,5653,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:38:44.53',3,3,61119,405334,NULL,1,0,3,0.000,36.607,12031623,1613432159000,4,'SRID=3067;LINESTRING Z(426412.123 6756974.653 79.21300000000338, 426436.467 6757001.992 79.12600000000384)'::geometry,5616,5653,48864204,0,0,0,0),
      (142231,2000,1,5,167,1,5652,5661,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83782,470021,NULL,1,0,3,0.000,8.671,12031622,1613432159000,4,'SRID=3067;LINESTRING Z(426394.139 6756972.816 79.16099999999278, 426398.941 6756978.118 79.153999999995, 426399.96 6756979.243 79.15300015686213)'::geometry,5652,5661,258705202,0,0,0,0),
      (142125,2000,2,5,167,1,5653,5673,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:38:44.53',3,3,61119,405335,NULL,1,0,3,0.000,20.398,11364575,1613432159000,4,'SRID=3067;LINESTRING Z(426398.538 6756959.437 79.17699999999604, 426412.123 6756974.653 79.21299997936782)'::geometry,5653,5673,48864204,0,0,0,0),
      (142232,2000,1,5,167,1,5661,5686,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83782,470022,NULL,1,0,3,0.000,25.304,12031618,1613432159000,4,'SRID=3067;LINESTRING Z(426377.206 6756954.014 79.18799999999464, 426378.81 6756955.893 79.21899999999732, 426385.521 6756963.302 79.27700000000186, 426392.231 6756970.71 79.32099999999627, 426394.139 6756972.816 79.16099999999278)'::geometry,5661,5686,258705202,0,2,0,2),
      (142126,2000,2,5,167,1,5673,5691,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:38:44.53',3,3,61119,405336,NULL,1,0,3,0.000,17.666,11364577,1613432159000,4,'SRID=3067;LINESTRING Z(426386.772 6756946.259 79.20600000000559, 426398.538 6756959.437 79.1770005056682)'::geometry,5673,5691,48864204,0,2,0,2),
      (142233,2000,1,5,167,1,5686,5836,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83782,470023,NULL,1,0,3,0.000,150.175,12031613,1613432159000,4,'SRID=3067;LINESTRING Z(426276.194 6756842.892 77.5, 426284.7 6756852.512 77.56900000000314, 426291.516 6756859.765 77.66700000000128, 426298.282 6756867.063 77.78599999999278, 426305.006 6756874.411 77.88899999999558, 426306.702 6756876.281 77.92699999999604, 426311.706 6756881.805 78.00400000000081, 426318.416 6756889.213 78.09799999999814, 426325.126 6756896.622 78.2280000000028, 426331.838 6756904.031 78.42200000000594, 426338.548 6756911.441 78.57799999999406, 426345.258 6756918.848 78.7719999999972, 426351.968 6756926.257 78.87900000000081, 426358.679 6756933.667 78.98099999999977, 426365.389 6756941.075 79.08800000000338, 426372.1 6756948.484 79.153999999995, 426377.206 6756954.014 79.18799933646693)'::geometry,5686,5836,258705202,2,0,2,0),
      (142127,2000,2,5,167,1,5691,6117,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:38:44.53',3,3,61119,405337,NULL,1,0,3,0.000,426.228,12031587,1613432159000,4,'SRID=3067;LINESTRING Z(426092.114 6756638.319 78.91400000000431, 426238.052 6756789.109 76.10499999999593, 426333.519 6756888.133 77.92299999999523, 426386.772 6756946.259 79.20600000000559)'::geometry,5691,6117,48864204,2,0,2,0),
      (142234,2000,1,5,167,1,5836,6112,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:38:24.749',3,3,83782,470024,NULL,1,0,3,0.000,275.496,12031591,1613432159000,4,'SRID=3067;LINESTRING Z(426084.306 6756645.215 79.54499999999825, 426175.857 6756740.2 78.22599999999511, 426231.29 6756797.155 77.45200000000477, 426276.194 6756842.892 77.49999970377281)'::geometry,5836,6112,258705202,0,0,0,0),
      (142235,2000,1,5,167,1,6112,6132,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83782,470025,NULL,1,0,3,117.361,137.253,12031706,1613432159000,4,'SRID=3067;LINESTRING Z(426069.911 6756631.489 79.71242434712487, 426071.435 6756633.054 79.69500000000698, 426084.306 6756645.215 79.54499999999825)'::geometry,6112,6132,258705202,0,0,0,0),
      (142128,2000,2,5,167,1,6117,6132,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,61119,405338,NULL,1,0,3,185.292,200.316,12031703,1613432159000,4,'SRID=3067;LINESTRING Z(426082.369 6756626.885 79.09304296422744, 426092.114 6756638.319 78.91400000000431)'::geometry,6117,6132,48864204,0,0,0,0),
      (142165,2000,2,5,167,1,6132,6197,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,72013,437378,NULL,1,0,3,120.190,185.292,12031703,1613432159000,4,'SRID=3067;LINESTRING Z(426039.116 6756578.254 80.26391728636113, 426044.727 6756584.222 80.25900000000547, 426051.575 6756591.504 79.81900000000314, 426058.423 6756598.787 79.53299999999581, 426082.369 6756626.885 79.09304296422744)'::geometry,6132,6197,148121997,0,0,0,0),
      (142241,2000,1,5,167,1,6132,6197,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,83886,470390,NULL,1,0,3,52.713,117.361,12031706,1613432159000,4,'SRID=3067;LINESTRING Z(426024.803 6756585.179 80.44668013968516, 426030.32 6756590.843 80.33999999999651, 426038.543 6756599.286 80.16899999999441, 426046.766 6756607.728 80.04499999999825, 426054.989 6756616.17 79.9149999999936, 426063.212 6756624.612 79.78900000000431, 426069.911 6756631.489 79.71242434712487)'::geometry,6132,6197,258705205,0,0,0,0),
      (142108,2000,1,5,167,1,6197,6250,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,60762,403189,NULL,1,0,3,0.000,52.713,12031706,1613432159000,4,'SRID=3067;LINESTRING Z(425990.061 6756545.651 81.09299999999348, 425999.084 6756557.776 80.90799999999581, 426005.651 6756565.517 80.73699999999371, 426013.874 6756573.959 80.62099999999919, 426022.097 6756582.401 80.49899999999616, 426024.803 6756585.179 80.44668013968516)'::geometry,6197,6250,48864415,0,2,0,2),
      (142171,2000,2,5,167,1,6197,6318,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,72613,440100,NULL,1,0,3,0.000,120.190,12031703,1613432159000,4,'SRID=3067;LINESTRING Z(425965.217 6756483.729 79.30899999999383, 425967.993 6756488.358 81.2390000000014, 425970.249 6756491.836 81.14900000000489, 425975.765 6756500.047 81.05100000000675, 425981.412 6756508.169 80.95699999999488, 425987.189 6756516.2 80.89599999999336, 425993.092 6756524.137 80.80599999999686, 425999.122 6756531.978 80.75199999999313, 426005.276 6756539.722 80.61000000000058, 426011.555 6756547.366 80.55800000000454, 426017.954 6756554.91 80.45600000000559, 426024.473 6756562.35 80.35899999999674, 426031.116 6756569.691 80.2390000000014, 426035.799 6756574.728 80.2390000000014, 426037.88 6756576.94 80.26499999999942, 426039.116 6756578.254 80.26391728636113)'::geometry,6197,6318,148127681,0,0,0,0),
      (142109,2000,1,5,167,1,6250,6313,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:39:15.546',3,3,60762,403190,NULL,1,0,3,0.000,62.569,12031709,1608073892000,4,'SRID=3067;LINESTRING Z(425952.879 6756495.335 80.31299999999464, 425974.698 6756525.539 81.28599999999278, 425990.061 6756545.651 81.09299999999348)'::geometry,6250,6313,48864415,2,0,2,0),
      (142110,2000,1,5,167,1,6313,6322,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:39:15.546',3,3,60762,403191,NULL,1,0,3,0.000,9.643,11238297,1608073892000,4,'SRID=3067;LINESTRING Z(425947.656 6756487.231 81.61699999999837, 425952.375 6756494.638 80.42900000000373, 425952.879 6756495.335 80.31299999999464)'::geometry,6313,6322,48864415,0,0,0,0),
      (142172,2000,2,5,167,1,6318,6324,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,72613,440101,NULL,1,0,3,0.000,6.098,12031718,1613432159000,4,'SRID=3067;LINESTRING Z(425962.082 6756478.499 79.61800000000221, 425965.217 6756483.729 79.30899999999383)'::geometry,6318,6324,148127681,0,0,0,0),
      (142111,2000,1,5,167,1,6322,6349,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:39:15.546',3,3,60762,403192,NULL,1,0,3,0.000,27.042,12031673,1608073892000,4,'SRID=3067;LINESTRING Z(425933.297 6756464.316 81.99000000000524, 425947.656 6756487.231 81.61700212748957)'::geometry,6322,6349,48864415,0,0,0,0),
      (142173,2000,2,5,167,1,6324,6387,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:24:50.289',5,3,72613,440102,NULL,1,0,3,0.000,63.176,12031715,1613432159000,4,'SRID=3067;LINESTRING Z(425931.364 6756423.31 81.72000000000116, 425935.855 6756431.81 81.70100000000093, 425940.415 6756440.579 81.59299999999348, 425945.115 6756449.274 81.53500000000349, 425949.953 6756457.893 81.5109999999986, 425954.929 6756466.433 81.46400000000722, 425960.177 6756475.321 81.44800000000396, 425962.082 6756478.499 79.61806027932492)'::geometry,6324,6387,148127681,0,2,0,2),
      (142112,2000,1,5,167,1,6349,6387,'silari','silari','2021-11-15 11:23:42.432','2021-11-15 11:39:15.546',3,3,60762,403193,NULL,1,0,3,0.000,37.429,12031669,1613432159000,4,'SRID=3067;LINESTRING Z(425916.837 6756430.705 82.25199999999313, 425920.938 6756439.416 82.27999999999884, 425925.622 6756448.423 82.28599999999278, 425933.297 6756464.316 81.99000626944645)'::geometry,6349,6387,48864415,0,2,0,2)""".execute

      case object projectSaved{val id=2000}
      val test_road_number = 167
      val test_road_part_number = 1

      val beforeCalc = projectService_db.getProjectLinks(projectSaved.id).toList
      projectService_db.recalculateProjectLinks(projectSaved.id, "")
      val afterCalculatedProjectlinks = projectService_db.getProjectLinks(projectSaved.id).toList

      val oldAddresses = ((afterCalculatedProjectlinks.filter(pl => pl.status != LinkStatus.New && pl.track != Track.LeftSide)).sortBy(_.originalStartAddrMValue).toList.map(pl => (pl.originalStartAddrMValue, pl.originalEndAddrMValue, pl.status)),
                           (afterCalculatedProjectlinks.filter(pl => pl.status != LinkStatus.New && pl.track != Track.RightSide)).sortBy(_.originalStartAddrMValue).toList.map(pl => (pl.originalStartAddrMValue, pl.originalEndAddrMValue, pl.status)))

      /* Check original addresses continuos*/
      assert(oldAddresses._1.head._1 == 0)
      assert(oldAddresses._1.head._1 == oldAddresses._2.head._1)
      assert(oldAddresses._1.last._2 == oldAddresses._2.last._2)
      oldAddresses._1.tail.foldLeft(oldAddresses._1.head) { (cur, n) =>
        assert(n._1 <= n._2)
        assert(cur._2 == n._1)
        n
      }

      oldAddresses._2.tail.foldLeft(oldAddresses._2.head) { (cur, n) =>
        assert(n._1 <= n._2)
        assert(cur._2 == n._1)
        n
      }

      val leftSide = afterCalculatedProjectlinks.filterNot(_.track == Track.RightSide).filterNot(_.status == LinkStatus.Terminated).sortBy(_.startAddrMValue)
      val rightSide = afterCalculatedProjectlinks.filterNot(_.track == Track.LeftSide).filterNot(_.status == LinkStatus.Terminated).sortBy(_.startAddrMValue)

      def continuosAddresses(t: Seq[ProjectLink]) = {
        t.sortBy(_.startAddrMValue).tail.foldLeft(t.head) { (cur, next) =>
          assert(next.startAddrMValue <= next.endAddrMValue)
          assert(cur.endAddrMValue == next.startAddrMValue)
          next
        }
      }

      continuosAddresses(leftSide)
      continuosAddresses(rightSide)

      /* Create change table */
      val (changeProject, warningMessage) = projectService_db.getChangeProject(projectSaved.id)
      println("Change table warning messages:")
      if (warningMessage.isDefined) {
        println(warningMessage)
      } else println("No warnings.\n")

      println("CHANGE TABLE")

      val roadwayChanges = roadwayChangesDAO.fetchRoadwayChanges(Set(projectSaved.id))
      prettyPrintLog(roadwayChanges)

      // Check Change table target
      val two_track_nonterminated_targets = changeProject.get.changeInfoSeq.filter(changeInfo => List(1,2,3).contains(changeInfo.changeType.value)).map(changeInfo => changeInfo.target)
      val two_track_nonterminated_sources = changeProject.get.changeInfoSeq.filter(changeInfo => List(1,3,5).contains(changeInfo.changeType.value)).map(_.source)
      val two_track_unchanged_and_transfers = changeProject.get.changeInfoSeq.filter(changeInfo => List(1,3).contains(changeInfo.changeType.value))

      // Cross check source/target lengths
      two_track_unchanged_and_transfers.foreach(t => (t.source.endAddressM.get - t.source.startAddressM.get) should be (t.target.endAddressM.get - t.target.startAddressM.get))

      // Value checks
      two_track_nonterminated_sources.foreach(rcs => {
        rcs.trackCode.get.toInt should (be >= 0 and be <= 2)

        rcs.roadNumber shouldBe Some(test_road_number)
        rcs.startRoadPartNumber shouldBe Some(test_road_part_number)
        rcs.endRoadPartNumber shouldBe Some(test_road_part_number)
      })

      /* Check two tracks has equal start and end addresses on both tracks and even count of two track lines. */
      val two_track_groups: Iterable[Seq[RoadwayChangeSection]] = two_track_nonterminated_sources.filterNot(_.trackCode.get == 0).groupBy(t => t.startAddressM).values
      two_track_groups.foreach(two_track_pair => {
        two_track_pair.size should be(2)
        two_track_pair.head.trackCode.get should not be two_track_pair.last.trackCode.get
        two_track_pair.head.startAddressM.get should be(two_track_pair.last.startAddressM.get)
        two_track_pair.head.endAddressM.get should be(two_track_pair.last.endAddressM.get)
      }
      )

      /* Check two track addresses are continuous on each track. */
      def check_two_track_continuous(x: Seq[RoadwayChangeSection]) = {
        Seq(Track.LeftSide, Track.RightSide).foreach(track => {
          val trackAddresses = x.filterNot(_.trackCode.get == track.value).sortBy(_.startAddressM.get).map(rcs => {
            (rcs.startAddressM.get, rcs.endAddressM.get)
          })
          trackAddresses.tail.foldLeft(trackAddresses.head._2) { (cur, next) =>
            assert(next._1 < next._2) // StartAddress < EndAddress
            assert(cur == next._1) // Prev endAddress = next startAddress
            next._2
          }
        })
      }
      check_two_track_continuous(two_track_nonterminated_sources)
      check_two_track_continuous(two_track_nonterminated_targets)
    }}
}


