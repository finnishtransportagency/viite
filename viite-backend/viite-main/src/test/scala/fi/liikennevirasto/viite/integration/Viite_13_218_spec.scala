package fi.liikennevirasto.viite

import fi.liikennevirasto.digiroad2._
import fi.liikennevirasto.digiroad2.client.kgv.KgvRoadLink
import fi.liikennevirasto.digiroad2.postgis.PostGISDatabase
import fi.liikennevirasto.digiroad2.service.RoadLinkService
import fi.liikennevirasto.digiroad2.util.ViiteProperties
import fi.liikennevirasto.viite.dao._
import fi.liikennevirasto.viite.model.RoadAddressLinkLike
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import fi.liikennevirasto.viite.process.strategy.DefaultSectionCalculatorStrategy
import fi.liikennevirasto.viite.util._
import fi.vaylavirasto.viite.dao.Sequences
import fi.vaylavirasto.viite.geometry.{GeometryUtils, Point}
import fi.vaylavirasto.viite.model.{AdministrativeClass, CalibrationPointType, Discontinuity, LifecycleStatus, LinkGeomSource, RoadAddressChangeType, RoadLink, SideCode, Track, TrafficDirection}
import org.joda.time.DateTime
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import org.scalatest.mockito.MockitoSugar
import slick.driver.JdbcDriver.backend.Database
import slick.driver.JdbcDriver.backend.Database.dynamicSession

import scala.collection.immutable.HashMap
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps

class Viite_13_218_spec extends FunSuite with Matchers with BeforeAndAfter {
  val mockProjectService: ProjectService = MockitoSugar.mock[ProjectService]
  val mockRoadLinkService: RoadLinkService = MockitoSugar.mock[RoadLinkService]
  val mockDefaultSectionCalculatorStrategy: DefaultSectionCalculatorStrategy = MockitoSugar.mock[DefaultSectionCalculatorStrategy]
  val mockKGVClient: KgvRoadLink = MockitoSugar.mock[KgvRoadLink]
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
  val nodesAndJunctionsService_db: NodesAndJunctionsService = {
    new NodesAndJunctionsService(roadwayDAO, roadwayPointDAO, linearLocationDAO, nodeDAO, nodePointDAO, junctionDAO_db, junctionPointDAO, roadwayChangesDAO, projectReservedPartDAO)
  }
  val kgvClient_db: KgvRoadLink = new KgvRoadLink

  val eventbus_db: DigiroadEventBus = {
    Class.forName(ViiteProperties.eventBus).newInstance().asInstanceOf[DigiroadEventBus]
  }
  val roadLinkService_db: RoadLinkService = {
    new RoadLinkService(kgvClient_db, eventbus_db, new JsonSerializer, true)
  }
  val roadAddressService_db: RoadAddressService = new RoadAddressService(mockRoadLinkService, roadwayDAO, linearLocationDAO, roadNetworkDAO, roadwayPointDAO, nodePointDAO, junctionPointDAO, roadwayAddressMapper, eventbus_db, true)

  val projectService_db: ProjectService =
    new ProjectService(
                        roadAddressService_db,
                        mockRoadLinkService,
                        nodesAndJunctionsService_db,
                        roadwayDAO,
                        roadwayPointDAO,
                        linearLocationDAO,
                        projectDAO,
                        projectLinkDAO,
                        nodeDAO,
                        nodePointDAO,
                        junctionPointDAO,
                        projectReservedPartDAO,
                        roadwayChangesDAO,
                        roadwayAddressMapper,
                        eventbus_db,
                        frozenTimeAPIServiceEnabled = true
                        ) {
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
    RoadLink(ral.linkId, ral.geometry, geomLength, adminClass, extractTrafficDirection(ral.sideCode, ral.track), Option(ral.startDate.toString), ral.createdBy, LifecycleStatus.InUse, ral.linkGeomSource, 749, "")
  }

  private def extractTrafficDirection(sideCode: SideCode, track: Track): TrafficDirection = {
    (sideCode, track) match {
      case (_, Track.Combined) => TrafficDirection.BothDirections
      case (SideCode.TowardsDigitizing, Track.RightSide) => TrafficDirection.TowardsDigitizing
      case (SideCode.TowardsDigitizing, Track.LeftSide) => TrafficDirection.AgainstDigitizing
      case (SideCode.AgainstDigitizing, Track.RightSide) => TrafficDirection.AgainstDigitizing
      case (SideCode.AgainstDigitizing, Track.LeftSide) => TrafficDirection.TowardsDigitizing
      case (_, _) => TrafficDirection.UnknownDirection
    }
  }

  private def toRoadLink(ral: RoadAddressLinkLike): RoadLink = {
    RoadLink(ral.linkId, ral.geometry, ral.length, ral.administrativeClass, extractTrafficDirection(ral.sideCode, Track.apply(ral.trackCode.toInt)), ral.modifiedAt, ral.modifiedBy, ral.lifecycleStatus, ral.roadLinkSource, 749, "")
  }

  /* update errors*/
  def errorPartsToApi(errorParts: projectService_db.projectValidator.ValidationErrorDetails): Map[String, Any] = {
    Map("ids" -> errorParts.affectedPlIds,
      "linkIds" -> errorParts.affectedLinkIds,
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
        x = x match {
          case Some => f.get(c).asInstanceOf[Option[AnyRef]].get
          case _ => x
        }
        val z = x match {
          case info1: RoadwayChangeInfo => val ss   = (Map[String, Any]() /: info1.source.getClass.getDeclaredFields) { (b, B) => {
            B.setAccessible(true)
            if (header.size < 11) header = header ++ B.getName
            val value    = B.get(info1.source)
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

            val tt   = (Map[String, Any]() /: info1.target.getClass.getDeclaredFields) { (b, B) => {
              B.setAccessible(true)
              val value    = B.get(info1.target)
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
              if (t._1 == "endRoadPartNumber" && t._2.toString.nonEmpty) ("length", ttt.find(_._1 == "endAddressM").get._2.asInstanceOf[Long] - ttt.find(_._1 == "startAddressM").get._2.asInstanceOf[Long]) else t
            })
            val t    = List(tttt.head, tttt(6), tttt(5), tttt(2), tttt(8), tttt(3), tttt(7), tttt(1), tttt(4))

            val T: Map[String, Any] = Map("changeType" -> info1.changeType)
            val R: Map[String, Any] = Map("reversed" -> info1.reversed)

            List(T.toList ++ s ++ R.toList, Map("" -> "").toList ++ t ++ Map("" -> "").toList)
          case _ => List()
        }
        if (z.isEmpty) a else z
      }
      fields.map(_.map(_._2.toString))
    }

    def headerFieldsToString: String = {
      val fields = (Map[String, Any]() /: c.getClass.getDeclaredFields) { (a, f) =>
        f.setAccessible(true)
        var x = f.get(c)
        x = x match {
          case Some => f.get(c).asInstanceOf[Option[String]].get
          case _ => x
        }
        val z = x match {
          case info1: RoadwayChangeInfo => (Map[String, Any]() /: info1.source.getClass.getDeclaredFields) { (b, B) => {
            B.setAccessible(true)
            b + (B.getName -> "")
          }
          }.asInstanceOf[HashMap.HashTrieMap[String, Option[Any]]].toList.map(t => {
            t._1 -> t._1
          })
          case _ => List()
        }
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
          RoadLink(11910497.toString, List(Point(512267.421,6838792.474,102.50800000000163), Point(512265.142,6838796.858,102.49899999999616), Point(512260.53 ,6838805.726,102.43899999999849), Point(512255.918,6838814.595,102.37399999999616), Point(512251.306,6838823.463,102.29499999999825), Point(512246.694,6838832.331,102.24300000000221), Point(512242.082,6838841.2  ,102.21099999999569), Point(512237.471,6838850.068,102.11199999999371), Point(512232.859,6838858.937,102.00900000000547), Point(512228.247,6838867.805,101.98600000000442), Point(512223.636,6838876.674,101.98399999999674), Point(512219.023,6838885.543,101.87600000000384), Point(512214.411,6838894.412,101.83199999999488), Point(512212.728,6838897.649,101.80999999999767), Point(512209.972,6838908.207,101.84200000000419)), 129.458,  AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse, LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910501.toString, List(Point(512244.316,6838851.527,102.22900000000664), Point(512240.643,6838858.47 ,102.17500000000291), Point(512237.21 ,6838864.801,102.16999999999825), Point(512233.455,6838871.881,102.11400000000140), Point(512228.734,6838880.786,102.11199999999371), Point(512226.332,6838885.298,102.00800000000163), Point(512225.179,6838887.139,102.04899999999907), Point(512219.873,6838895.609,101.94599999999627), Point(512216.978,6838900.231,101.92999999999302), Point(512214.039,6838903.695,101.89699999999720), Point(512209.972,6838908.207,101.84200000000419)), 66.499,  AdministrativeClass.State, TrafficDirection.TowardsDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse, LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910509.toString, List(Point(512476.259,6838479.304,103.22500000000582), Point(512469.723,6838482.604,103.06299999999464), Point(512469.446,6838482.79 ,103.05599999999686), Point(512463.159,6838490.128,102.76600000000326), Point(512456.655,6838497.719,102.55499999999302), Point(512450.151,6838505.309,102.39299999999639), Point(512443.647,6838512.9  ,102.22299999999814), Point(512437.143,6838520.491,102.05400000000373), Point(512430.639,6838528.081,101.89299999999639), Point(512424.135,6838535.672,101.80599999999686), Point(512417.631,6838543.263,101.72999999999593), Point(512411.128,6838550.853,101.69299999999930), Point(512404.623,6838558.444,101.69700000000012), Point(512399.524,6838564.395,101.74000000000524), Point(512398.123,6838566.038,101.68600000000151), Point(512391.737,6838573.728,101.69800000000396), Point(512385.520,6838581.555,101.70399999999790), Point(512379.473,6838589.515,101.66599999999744), Point(512369.283,6838604.408,101.65200000000186), Point(512365.37,6838610.901,101.59399999999732)), 172.893,  AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse, LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910511.toString, List(Point(512476.259,6838479.304,103.22500000000582), Point(512474.424,6838486.31 ,103.09399999999732), Point(512470.254,6838492.565,102.83900000000722), Point(512463.439,6838500.463,102.59399999999732), Point(512462.083,6838502.209,102.52700000000186), Point(512458.844,6838506.084,102.41300000000047), Point(512455.589,6838509.806,102.29700000000594), Point(512449.075,6838517.389,102.07200000000012), Point(512447.741,6838518.966,102.09200000000419), Point(512442.626,6838525.026,101.83599999999569), Point(512436.179,6838532.664,101.78599999999278), Point(512429.731,6838540.303,101.63099999999395), Point(512423.283,6838547.941,101.47599999999511), Point(512416.835,6838555.58,101.451000000000930), Point(512410.388,6838563.219,101.40499999999884), Point(512408.112,6838565.916,101.39100000000326), Point(512403.972,6838570.885,101.47400000000198), Point(512397.704,6838578.67,101.479999999995930), Point(512391.593,6838586.581,101.4539999999979), Point(512385.644,6838594.613,101.39699999999720), Point(512379.858,6838602.764,101.45699999999488), Point(512374.237,6838611.031,101.40700000000652), Point(512372.244,6838613.972,101.36299999999756)), 170.898,  AdministrativeClass.State, TrafficDirection.TowardsDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse, LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910527.toString, List(Point(512279.32 ,6838745.083,101.78399999999965), Point(512280.764,6838748.184,102.28100000000268), Point(512281.492,6838749.957,102.46799999999348), Point(512281.897,6838751.39 ,102.51399999999558), Point(512282.096,6838753.216,102.52999999999884), Point(512282.065,6838754.61 ,102.59600000000500), Point(512281.863,6838758.142,102.58599999999569), Point(512281.258,6838762.482,102.67200000000594), Point(512279.845,6838766.822,102.64200000000710), Point(512277.826,6838771.868,102.67500000000291), Point(512274.365,6838779.12 ,102.63999999999942), Point(512271.067,6838785.463,102.57300000000396)), 43.161,  AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse,  LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910530.toString, List(Point(512317.14 ,6838754.684,101.96099999999569), Point(512311.861,6838757.541,102.09699999999430), Point(512305.984,6838761.473,101.98200000000361), Point(512300.231,6838766.216,101.55599999999686), Point(512297.694,6838768.455,101.21199999999953), Point(512290.226,6838775.093,102.07899999999790), Point(512283.484,6838782.466,102.53399999999965), Point(512278.172,6838789.637,102.53500000000349)), 52.82,  AdministrativeClass.State, TrafficDirection.TowardsDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse,  LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910544.toString, List(Point(512274.223,6838795.509,102.55400000000373), Point(512271.222,6838800.05 ,102.55100000000675)), 5.443,  AdministrativeClass.State, TrafficDirection.TowardsDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse,  LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910546.toString, List(Point(512278.172,6838789.637,102.53500000000349), Point(512274.223,6838795.509,102.55400000000373)), 7.076,  AdministrativeClass.State, TrafficDirection.TowardsDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse,  LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910547.toString, List(Point(512271.067,6838785.463,102.57300000000396), Point(512269.753,6838787.989,103.00000000000000), Point(512267.421,6838792.474,102.50800000000163)), 7.902,  AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse,  LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910567.toString, List(Point(512329.755,6838665.507,101.92600000000675), Point(512326.997,6838674.426,102.03100000000268), Point(512320.337,6838681.872,102.11800000000221), Point(512317.157,6838684.885,102.05100000000675), Point(512312.885,6838688.528,102.10300000000280), Point(512308.765,6838691.579,102.04600000000210), Point(512303.249,6838695.748,101.87699999999313), Point(512298.129,6838699.121,101.55899999999383), Point(512290.787,6838703.765,101.99499999999534)), 56.18 ,  AdministrativeClass.State, TrafficDirection.AgainstDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse,  LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910569.toString, List(Point(512339.611,6838671.001,101.69599999999627), Point(512334.865,6838680.575,101.83599999999569), Point(512330.257,6838689.446,101.99199999999837), Point(512326.799,6838696.099,102.13300000000163), Point(512325.774,6838698.375,102.20500000000175), Point(512324.825,6838701.767,102.29700000000594), Point(512324.95 ,6838705.951,102.33900000000722), Point(512325.901,6838709.577,102.31500000000233), Point(512327.657,6838714.361,101.62500000000000)), 47.229,  AdministrativeClass.State, TrafficDirection.TowardsDigitizing, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse,  LinkGeomSource.FrozenLinkInterface, 491, ""),
          RoadLink(11910589.toString, List(Point(512477.964,6838476.83 ,103.28299999999581), Point(512476.259,6838479.304,103.22500000000582)), 3.005,  AdministrativeClass.State, TrafficDirection.BothDirections, Some("31.03.2021 02:00:14"), None, LifecycleStatus.InUse,  LinkGeomSource.FrozenLinkInterface, 491, "")
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
        ).map(l => List(l.head.toString,l(1),l(2)))

        val linkPs = dynamicSession.prepareStatement(
          """insert into Link (ID, source, adjusted_timestamp)
             values (?, ?, ?)""".stripMargin)

        Li.zipWithIndex.foreach { case (link,i) =>
          linkPs.setString(1, link.head.asInstanceOf[String])
          linkPs.setLong(2, link(1).asInstanceOf[Number].longValue)
          linkPs.setLong(3, link(2).asInstanceOf[Number].longValue)
          linkPs.addBatch()
        }

        linkPs.executeBatch()
        linkPs.close()

        val linearlocations = List(
          List(126019231,1,"3227478",0.000,131.954,3,"512288.7 6838743.26 0 0, 512234.416 6838863.059 0 131.954","2016-03-30 12:03:00.000",creator),
          List(126019231,2,"3227484",0.000,15.908,3,"512295.534 6838728.895 0 0, 512288.7 6838743.26 0 15.908","2016-03-30 12:03:00.000",creator),
          List(126019231,3,"3227486",1.921,8.646,3,"512298.63 6838722.925 0 0, 512295.534 6838728.895 0 6.725","2016-03-30 12:03:00.000",creator),
          List(126019743,1,"12017340",0.000,153.238,3,"512476.259 6838479.304 0 0, 512378.721 6838597.418 0 153.238","2016-03-08 12:03:00.000",creator),
          List(126019743,2,"12017341",0.000,3.005,3,"512477.964 6838476.83 0 0, 512476.259 6838479.304 0 3.005","2016-03-08 12:03:00.000",creator),
          List(126019743,3,"11910590",0.000,175.113,3,"512584.149 6838337.794 0 0, 512477.964 6838476.83 0 175.113","2016-03-08 12:03:00.000",creator),
          List(126019743,4,"3227503",5.056,54.602,3,"512611.021 6838296.168 0 0, 512584.149 6838337.794 0 49.546","2016-03-08 12:03:00.000",creator),
          List(126019932,1,"3227503",0.000,5.056,3,"512613.763 6838291.92 0 0, 512611.021 6838296.168 0 5.056","2015-10-26 12:10:00.000",creator),
          List(126019932,2,"3227468",0.000,61.692,3,"512642.831 6838237.545 0 0, 512613.763 6838291.92 0 61.692","2015-10-26 12:10:00.000",creator),
          List(126019932,3,"3227469",0.000,83.172,3,"512680.892 6838163.593 0 0, 512642.831 6838237.545 0 83.172","2015-10-26 12:10:00.000",creator),
          List(126019932,4,"3227544",0.000,50.669,3,"512703.961 6838118.484 0 0, 512680.892 6838163.593 0 50.669","2015-10-26 12:10:00.000",creator),
          List(126019932,5,"3227541",0.000,85.657,3,"512730.945 6838037.532 0 0, 512703.961 6838118.484 0 85.657","2015-10-26 12:10:00.000",creator),
          List(148122023,1,"3227486",0.000,1.921,3,"512299.514 6838721.22 0 0, 512298.63 6838722.925 0 1.921","2016-03-30 12:03:00.000",creator),
          List(148122023,2,"11910568",0.000,63.405,3,"512329.755 6838665.507 0 0, 512299.514 6838721.22 0 63.405","2016-03-30 12:03:00.000",creator),
          List(148122023,3,"11910585",0.000,65.218,3,"512365.37 6838610.901 0 0, 512329.755 6838665.507 0 65.218","2016-03-30 12:03:00.000",creator),
          List(148122023,4,"11910587",0.000,18.975,3,"512378.721 6838597.418 0 0, 512365.37 6838610.901 0 18.975","2016-03-30 12:03:00.000",creator),
          List(126019218,1,"3225257",3.062,22.456,3,"511790.071 6840314.779 0 0, 511786.541 6840333.849 0 19.394","2015-10-26 12:10:00.000",creator),
          List(148122186,1,"3225257",0.000,3.062,3,"511790.61 6840311.765 0 0, 511790.071 6840314.779 0 3.062","2016-03-08 12:03:00.000",creator),
          List(148122186,2,"7330427",0.000,93.514,3,"511805.651 6840219.469 0 0, 511790.61 6840311.765 0 93.514","2016-03-08 12:03:00.000",creator),
          List(148122186,3,"7330434",0.000,64.403,3,"511814.67 6840155.702 0 0, 511805.651 6840219.469 0 64.403","2016-03-08 12:03:00.000",creator),
          List(148122186,4,"7094558",0.000,662.726,3,"511867.808 6839499.022 0 0, 511814.67 6840155.702 0 662.726","2016-03-08 12:03:00.000",creator),
          List(148122186,5,"3225290",0.000,37.832,3,"511880.659 6839463.44 0 0, 511867.808 6839499.022 0 37.832","2016-03-08 12:03:00.000",creator),
          List(148122186,6,"3225291",0.000,31.193,3,"511891.647 6839434.246 0 0, 511880.659 6839463.44 0 31.193","2016-03-08 12:03:00.000",creator),
          List(148122186,7,"3225295",0.000,117.061,3,"511943.035 6839329.147 0 0, 511891.647 6839434.246 0 117.061","2016-03-08 12:03:00.000",creator),
          List(148122186,8,"3225166",0.000,8.547,3,"511947.249 6839321.711 0 0, 511943.035 6839329.147 0 8.547","2016-03-08 12:03:00.000",creator),
          List(148122186,9,"11910500",0.000,490.206,3,"512209.972 6838908.207 0 0, 511947.249 6839321.711 0 490.206","2016-03-08 12:03:00.000",creator),
          List(148122186,10,"11910502",0.000,51.344,3,"512234.416 6838863.059 0 0, 512209.972 6838908.207 0 51.344","2016-03-08 12:03:00.000",creator),
          List(148127797,1,"11910505",0.000,15.199,3,"512244.316 6838851.527 0 0, 512234.416 6838863.059 0 15.199","2016-03-08 12:03:00.000",creator),
          List(148127797,2,"11910540",0.000,58.087,3,"512271.222 6838800.05 0 0, 512244.316 6838851.527 0 58.087","2016-03-08 12:03:00.000",creator),
          List(148127797,3,"11910533",0.000,59.053,3,"512297.968 6838747.401 0 0, 512271.222 6838800.05 0 59.053","2016-03-08 12:03:00.000",creator),
          List(148127797,4,"3227482",0.000,15.460,3,"512304.761 6838733.513 0 0, 512297.968 6838747.401 0 15.46","2016-03-08 12:03:00.000",creator),
          List(148127797,5,"3227480",2.005,9.021,3,"512308.174 6838727.383 0 0, 512304.761 6838733.513 0 7.016","2016-03-08 12:03:00.000",creator),
          List(148128053,1,"3227480",0.000,2.005,3,"512309.149 6838725.631 0 0, 512308.174 6838727.383 0 2.005","2016-03-08 12:03:00.000",creator),
          List(148128053,2,"11910572",0.000,62.549,3,"512339.611 6838671.001 0 0, 512309.149 6838725.631 0 62.549","2016-03-08 12:03:00.000",creator),
          List(148128053,3,"11910586",0.000,65.706,3,"512372.244 6838613.972 0 0, 512339.611 6838671.001 0 65.706","2016-03-08 12:03:00.000",creator),
          List(148128053,4,"11910588",0.000,17.783,3,"512378.721 6838597.418 0 0, 512372.244 6838613.972 0 17.783","2016-03-08 12:03:00.000",creator)
        )

        val newIds = Sequences.fetchLinearLocationIds(linearlocations.size)
        val lps = dynamicSession.prepareStatement(
          """insert into LINEAR_LOCATION (id, ROADWAY_NUMBER, order_number, link_id, start_measure, end_measure, SIDE, geometry, valid_from, created_by)
      values (?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 3067), ?, ?)""".stripMargin)

        linearlocations.zipWithIndex.foreach { case (location,i) =>
          lps.setLong(1, newIds(i))
          lps.setLong(2, location.head.asInstanceOf[Number].longValue)//roadway_number
          lps.setLong(3, location(1).asInstanceOf[Number].longValue)//order_number
          lps.setString(4, location(2).asInstanceOf[String])//link_id
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
          List(64046,"3225257",0,2,creator,126019218,0),
          List(68761,"7330427",1,2,creator,148122186,114),
          List(68761,"7330434",0,2,creator,148122186,114),
          List(68762,"7094558",0,2,creator,148122186,178),
          List(68762,"7330434",1,2,creator,148122186,178),
          List(68763,"3225290",0,2,creator,148122186,833),
          List(68763,"7094558",1,2,creator,148122186,833),
          List(64599,"3227478",0,2,creator,126019231,1561),
          List(68764,"11910502",1,2,creator,148122186,1561),
          List(70539,"11910505",0,2,creator,148127797,1561),
          List(70540,"3227480",0,2,creator,148127797,1710),
          List(70540,"3227482",1,2,creator,148127797,1710),
          List(64600,"3227484",1,2,creator,126019231,1710),
          List(64600,"3227486",0,2,creator,126019231,1710),
          List(70189,"3227480",1,2,creator,148128053,1719),
          List(68960,"3227486",1,2,creator,148122023,1719),
          List(68960,"11910568",0,2,creator,148122023,1719),
          List(70189,"11910572",0,2,creator,148128053,1719),
          List(68961,"11910587",1,2,creator,148122023,1868),
          List(70190,"11910588",1,2,creator,148128053,1868),
          List(63779,"12017340",0,2,creator,126019743,1868),
          List(64573,"3227468",1,2,creator,126019932,2316),
          List(64573,"3227469",0,2,creator,126019932,2316),
          List(64574,"3227541",1,2,creator,126019932,2536)
        )

        val newCPIds = (1 to calibrationPoints.size).map(_ => Sequences.nextCalibrationPointId).toList
        val cpPs = dynamicSession.prepareStatement(
          """INSERT INTO CALIBRATION_POINT (ID, ROADWAY_POINT_ID, LINK_ID, START_END, TYPE, CREATED_BY)
             VALUES (?, ?, ?, ?, ?, ?)""".stripMargin)
        calibrationPoints.zipWithIndex.foreach { case (cp,i) =>
          cpPs.setLong(1, newCPIds(i))
          cpPs.setLong(2, roadwayPointDAO.fetch(cp(5).asInstanceOf[Number].longValue,cp(6).asInstanceOf[Number].longValue).get.id)
          cpPs.setString(3, cp(1).asInstanceOf[String])
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
        val links2: (Map[String, List[RoadLink]], Map[String, List[RoadLink]]) = links.groupBy(_.linkId).partition(_._2.size == 1)
        links = links2._1.values.flatten.toList ++ links2._2.map(p => p._2.head.copy(geometry = {
          val geom: Seq[Point] = p._2.flatMap(_.geometry).sortBy(p => (p.x, p.y, p.z)).distinct
          if (p._2.head.geometry.head == geom.head) geom else geom.reverse
        }, length = p._2.map(_.length).sum, sourceId = ""))

        when(mockRoadLinkService.getRoadLinksHistoryFromVVH(any[Set[String]])).thenReturn(Seq())
        when(mockRoadLinkService.getRoadLinksByLinkIds(any[Set[String]])).thenAnswer(new Answer[Seq[RoadLink]] {
          override def answer(i: InvocationOnMock): Seq[RoadLink] = {
            (links ++ newLinks).filter(l => {
              i.getArgument[Seq[String]](0).toList.contains(l.linkId)
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
                                track_to_test        : List[ProjectLink],
                                discontinuity        : Int,
                                roadAddressChangeType: RoadAddressChangeType
                              )

        case class Test_terminated_config(
                                           track_to_test        : List[ProjectLink],
                                           roadAddressChangeType: RoadAddressChangeType
                                         )
        val first_road_part_to_update = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          x.track == Track(0) && x.endAddrMValue <= 1510
        }).toList

        val road_tracks_to_test_1 = List(
          Test_config(first_road_part_to_update, 5, RoadAddressChangeType.Unchanged)
        )

        for (test_track <- road_tracks_to_test_1) {
          projectService_db.updateProjectLinks(projectSaved.id, test_track.track_to_test.map(_.id).toSet, List(), roadAddressChangeType = test_track.roadAddressChangeType, userName = projectSaved.name, newRoadNumber = test_track.track_to_test.head.roadNumber, newRoadPartNumber = test_track.track_to_test.head.roadPartNumber, newTrackCode = test_track.track_to_test.head.track.value, userDefinedEndAddressM = None, administrativeClass = test_track.track_to_test.head.administrativeClass.value, discontinuity = test_track.discontinuity, ely = Some(test_track.track_to_test.head.ely), reversed = false, roadName = Some("Kokkola-Nuijamaa"), coordinates = projectSaved.coordinates)
        }

        val terminateLinkIds = List(11910502, 11910505, 3227478, 3227484, 3227486, 11910568, 11910533, 3227482, 3227480, 11910572, 11910587, 11910588, 12017340, 12017341).map(_.toString)
        val links_to_terminate = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          terminateLinkIds.contains(x.linkId)
        }).toList

        val road_tracks_to_test_2 = List(
          Test_terminated_config(links_to_terminate, RoadAddressChangeType.Termination)
        )

        for (test_track <- road_tracks_to_test_2) {
          projectService_db.updateProjectLinks(projectSaved.id, test_track.track_to_test.map(_.id).toSet, List(), roadAddressChangeType = test_track.roadAddressChangeType, userName = projectSaved.name, newRoadNumber = test_track.track_to_test.head.roadNumber, newRoadPartNumber = test_track.track_to_test.head.roadPartNumber, newTrackCode = test_track.track_to_test.head.track.value, userDefinedEndAddressM = None, administrativeClass = test_track.track_to_test.head.administrativeClass.value, discontinuity = test_track.track_to_test.last.discontinuity.value, ely = Some(test_track.track_to_test.head.ely), reversed = false, roadName = test_track.track_to_test.last.roadName, coordinates = projectSaved.coordinates)
        }

        case class New_links_config(
                                     coordinates           : Option[ProjectCoordinates],
                                     discontinuity         : Discontinuity,
                                     ids                   : Set[Long],
                                     linkIds               : Seq[String],
                                     roadAddressChangeType : RoadAddressChangeType,
                                     projectId             : Long,
                                     roadEly               : Long,
                                     roadLinkSource        : LinkGeomSource,
                                     roadName              : Option[String],
                                     roadNumber            : Long,
                                     roadPartNumber        : Long,
                                     administrativeClass   : AdministrativeClass,
                                     trackCode             : Track,
                                     userDefinedEndAddressM: Option[Int]
                                   )

        val new_links = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.MinorDiscontinuity,
          ids = Set(),
          linkIds = Seq(11910497, 11910547, 11910527).map(_.toString),
          roadAddressChangeType = RoadAddressChangeType.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.RightSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links.linkIds, new_links.projectId, new_links.roadNumber, new_links.roadPartNumber, new_links.trackCode, new_links.discontinuity, new_links.administrativeClass, new_links.roadLinkSource, new_links.roadEly, projectSaved.createdBy, new_links.roadName.get, new_links.coordinates)

        val new_links_2 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910567).map(_.toString),
          roadAddressChangeType = RoadAddressChangeType.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.RightSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_2.linkIds, new_links_2.projectId, new_links_2.roadNumber, new_links_2.roadPartNumber, new_links_2.trackCode, new_links_2.discontinuity, new_links_2.administrativeClass, new_links_2.roadLinkSource, new_links_2.roadEly, projectSaved.createdBy, new_links_2.roadName.get, new_links_2.coordinates)

        val transfer_1_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          x.track == Track(1) && x.linkId == 11910585.toString
        }).toList
        val transfer_1       = List(
          Test_config(transfer_1_links, 5, RoadAddressChangeType.Transfer)
        )

        for (test_track <- transfer_1) {
          projectService_db.updateProjectLinks(projectSaved.id, test_track.track_to_test.map(_.id).toSet, List(), roadAddressChangeType = test_track.roadAddressChangeType, userName = projectSaved.name, newRoadNumber = test_track.track_to_test.head.roadNumber, newRoadPartNumber = test_track.track_to_test.head.roadPartNumber, newTrackCode = test_track.track_to_test.head.track.value, userDefinedEndAddressM = None, administrativeClass = test_track.track_to_test.head.administrativeClass.value, discontinuity = test_track.discontinuity, ely = Some(test_track.track_to_test.head.ely), reversed = false, roadName = Some("Kokkola-Nuijamaa"), coordinates = projectSaved.coordinates)
        }

        val new_links_3 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910509.toString),
          roadAddressChangeType = RoadAddressChangeType.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.RightSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_3.linkIds, new_links_3.projectId, new_links_3.roadNumber, new_links_3.roadPartNumber, new_links_3.trackCode, new_links_3.discontinuity, new_links_3.administrativeClass, new_links_3.roadLinkSource, new_links_3.roadEly, projectSaved.createdBy, new_links_3.roadName.get, new_links_3.coordinates)

        val new_links_4 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910589.toString),
          roadAddressChangeType = RoadAddressChangeType.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.Combined,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_4.linkIds, new_links_4.projectId, new_links_4.roadNumber, new_links_4.roadPartNumber, new_links_4.trackCode, new_links_4.discontinuity, new_links_4.administrativeClass, new_links_4.roadLinkSource, new_links_4.roadEly, projectSaved.createdBy, new_links_4.roadName.get, new_links_4.coordinates)


        val transfer_2_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          List(11910590, 3227503, 3227468, 3227469, 3227544, 3227541).map(_.toString).contains(x.linkId)
        }).toList
        val transfer_2       = List(
          Test_config(transfer_2_links, 2, RoadAddressChangeType.Transfer)
        )

        for (test_track <- transfer_2) {
          projectService_db.updateProjectLinks(projectSaved.id, test_track.track_to_test.map(_.id).toSet, List(), roadAddressChangeType = test_track.roadAddressChangeType, userName = projectSaved.name, newRoadNumber = test_track.track_to_test.head.roadNumber, newRoadPartNumber = test_track.track_to_test.head.roadPartNumber, newTrackCode = test_track.track_to_test.head.track.value, userDefinedEndAddressM = None, administrativeClass = test_track.track_to_test.head.administrativeClass.value, discontinuity = test_track.discontinuity, ely = Some(test_track.track_to_test.head.ely), reversed = false, roadName = Some("Kokkola-Nuijamaa"), coordinates = projectSaved.coordinates)
        }

        val new_links_5 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910501).map(_.toString),
          roadAddressChangeType = RoadAddressChangeType.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.LeftSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_5.linkIds, new_links_5.projectId, new_links_5.roadNumber, new_links_5.roadPartNumber, new_links_5.trackCode, new_links_5.discontinuity, new_links_5.administrativeClass, new_links_5.roadLinkSource, new_links_5.roadEly, projectSaved.createdBy, new_links_5.roadName.get, new_links_5.coordinates)


        val transfer_3_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          x.linkId == 11910540.toString
        }).toList
        val transfer_3       = List(
          Test_config(transfer_3_links, 5, RoadAddressChangeType.Transfer)
        )

        for (test_track <- transfer_3) {
          projectService_db.updateProjectLinks(projectSaved.id, test_track.track_to_test.map(_.id).toSet, List(), roadAddressChangeType = test_track.roadAddressChangeType, userName = projectSaved.name, newRoadNumber = test_track.track_to_test.head.roadNumber, newRoadPartNumber = test_track.track_to_test.head.roadPartNumber, newTrackCode = test_track.track_to_test.head.track.value, userDefinedEndAddressM = None, administrativeClass = test_track.track_to_test.head.administrativeClass.value, discontinuity = test_track.discontinuity, ely = Some(test_track.track_to_test.head.ely), reversed = false, roadName = Some("Kokkola-Nuijamaa"), coordinates = projectSaved.coordinates)
        }

        val new_links_6 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.MinorDiscontinuity,
          ids = Set(),
          linkIds = Seq(11910530, 11910544, 11910546).map(_.toString),
          roadAddressChangeType = RoadAddressChangeType.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.LeftSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_6.linkIds, new_links_6.projectId, new_links_6.roadNumber, new_links_6.roadPartNumber, new_links_6.trackCode, new_links_6.discontinuity, new_links_6.administrativeClass, new_links_6.roadLinkSource, new_links_6.roadEly, projectSaved.createdBy, new_links_6.roadName.get, new_links_6.coordinates)

        val new_links_7 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910569).map(_.toString),
          roadAddressChangeType = RoadAddressChangeType.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.LeftSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_7.linkIds, new_links_7.projectId, new_links_7.roadNumber, new_links_7.roadPartNumber, new_links_7.trackCode, new_links_7.discontinuity, new_links_7.administrativeClass, new_links_7.roadLinkSource, new_links_7.roadEly, projectSaved.createdBy, new_links_7.roadName.get, new_links_7.coordinates)

        val new_links_8 = New_links_config(
          coordinates = projectSaved.coordinates,
          discontinuity = Discontinuity.Continuous,
          ids = Set(),
          linkIds = Seq(11910511).map(_.toString),
          roadAddressChangeType = RoadAddressChangeType.New,
          projectId = projectSaved.id,
          roadEly = 8,
          roadLinkSource = LinkGeomSource.FrozenLinkInterface,
          roadName = Some("Kokkola-Nuijamaa"),
          roadNumber = test_road_number,
          roadPartNumber = test_road_part_number,
          administrativeClass = AdministrativeClass.State,
          trackCode = Track.LeftSide,
          userDefinedEndAddressM = None)

        projectService_db.createProjectLinks(new_links_8.linkIds, new_links_8.projectId, new_links_8.roadNumber, new_links_8.roadPartNumber, new_links_8.trackCode, new_links_8.discontinuity, new_links_8.administrativeClass, new_links_8.roadLinkSource, new_links_8.roadEly, projectSaved.createdBy, new_links_8.roadName.get, new_links_8.coordinates)


        val transfer_4_links = projectService_db.getProjectLinks(projectSaved.id).filter(x => {
          x.track == Track(2) && x.linkId == 11910586.toString
        }).toList
        val transfer_4       = List(
          Test_config(transfer_4_links, 5, RoadAddressChangeType.Transfer)
        )

        for (test_track <- transfer_4) {
          projectService_db.updateProjectLinks(projectSaved.id, test_track.track_to_test.map(_.id).toSet, List(), roadAddressChangeType = test_track.roadAddressChangeType, userName = projectSaved.name, newRoadNumber = test_track.track_to_test.head.roadNumber, newRoadPartNumber = test_track.track_to_test.head.roadPartNumber, newTrackCode = test_track.track_to_test.head.track.value, userDefinedEndAddressM = None, administrativeClass = test_track.track_to_test.head.administrativeClass.value, discontinuity = test_track.discontinuity, ely = Some(test_track.track_to_test.head.ely), reversed = false, roadName = Some("Kokkola-Nuijamaa"), coordinates = projectSaved.coordinates)
        }

        val all_projectlinks = projectService_db.getProjectLinks(projectSaved.id)

        all_projectlinks.filter(pl => Seq(11910530, 11910544, 11910546).map(_.toString).contains(pl.linkId)).exists(pl => pl.discontinuity == Discontinuity.MinorDiscontinuity) shouldBe true
        all_projectlinks.filter(pl => Seq(11910497, 11910547, 11910527).map(_.toString).contains(pl.linkId)).exists(pl => pl.discontinuity == Discontinuity.MinorDiscontinuity) shouldBe true

//        withDynTransaction {
                  projectService_db.recalculateProjectLinks(projectSaved.id, projectSaved.modifiedBy)
//                }
       // Check saved Project elys after VIITE-2922
       // projectDAO.fetchById(projectSaved.id).get.elys shouldBe (Set(road_13_218.head.ely))

        val afterCalculatedProjectlinks = projectService_db.getProjectLinks(projectSaved.id)
        val calculatedProjectlinks      = afterCalculatedProjectlinks.filterNot(_.status == RoadAddressChangeType.Termination)

        val leftSide = calculatedProjectlinks.filterNot(_.track == Track.RightSide).sortBy(_.startAddrMValue)
        val rightSide = calculatedProjectlinks.filterNot(_.track == Track.LeftSide).sortBy(_.startAddrMValue)

        def continuosAddresses(t: Seq[ProjectLink]): ProjectLink = {
          t.sortBy(_.startAddrMValue).tail.foldLeft(t.head) { (cur, next) =>
            assert(next.startAddrMValue <= next.endAddrMValue)
            assert(cur.endAddrMValue == next.startAddrMValue)
            next
          }
        }

        continuosAddresses(leftSide)
        continuosAddresses(rightSide)

        val oldAddresses = (
          afterCalculatedProjectlinks.filter(pl => pl.status != RoadAddressChangeType.New && pl.track != Track.LeftSide).sortBy(_.originalStartAddrMValue).toList.map(pl => (pl.originalStartAddrMValue, pl.originalEndAddrMValue, pl.status)),
          afterCalculatedProjectlinks.filter(pl => pl.status != RoadAddressChangeType.New && pl.track != Track.RightSide).sortBy(_.originalStartAddrMValue).toList.map(pl => (pl.originalStartAddrMValue, pl.originalEndAddrMValue, pl.status))
        )

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

       /** 
         * Checks continuity of addresses and geometry.
         * @param pls Left or right side ProjectLinks with combined to check for continuity.
         */
         def validateAddressesWithGeometry(pls: Seq[ProjectLink]): Unit = {
           val it = pls.sliding(2)
           while (it.hasNext) {
             it.next() match {
               case Seq(curr, next) =>
                 if (curr.discontinuity == Discontinuity.Continuous)
                  curr.connected(next) shouldBe true
                 else
                  curr.connected(next) shouldBe false
            }
          }
        }

       validateAddressesWithGeometry(leftSide)
       validateAddressesWithGeometry(rightSide)

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
        def check_two_track_continuous(x: Seq[RoadwayChangeSection]): Unit = {
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

       /* Check second calculation.
       * The result should be the same as after the first calculation. */
       projectService_db.recalculateProjectLinks(projectSaved.id, "")
       val afterSecondCalc = projectService_db.getProjectLinks(projectSaved.id)
       afterSecondCalc.size should be (afterCalculatedProjectlinks.size)
       afterSecondCalc.sortBy(pl => (pl.startAddrMValue, pl.track.value)).zip(afterCalculatedProjectlinks.sortBy(pl => (pl.startAddrMValue, pl.track.value))).foreach{
         case (pl1, pl2) =>
           pl1.startAddrMValue should be(pl2.startAddrMValue)
           pl1.endAddrMValue should be(pl2.endAddrMValue)
           pl1.startMValue should be(pl2.startMValue)
           pl1.endMValue should be(pl2.endMValue)
       }

        projectDAO.updateProjectStatus(projectSaved.id, ProjectState.UpdatingToRoadNetwork)
        projectService_db.updateRoadwaysAndLinearLocationsWithProjectLinks(projectSaved.id)
        val roadways = roadwayDAO.fetchAllByRoadAndPart(test_road_number,test_road_part_number, withHistory = true).toList
        val linearLocations = linearLocationDAO.fetchByRoadways(roadways.map(_.roadwayNumber).toSet).toList

        /* Check Roadways and linearlocations have a match. */
        val currentRws = roadways.filterNot(r => r.endDate.isDefined || r.validTo.isDefined)
        val linearLocationGrps = linearLocations.groupBy(_.roadwayNumber)
        currentRws.forall(r => linearLocationGrps.contains(r.roadwayNumber)) shouldBe true

       /* Check current roadways have distinct roadwaynumbers. */
        currentRws.map(_.roadwayNumber).toSet should have size currentRws.size

        /* Check one linearlocation for each roadway, link id pair. */
        val linearLocs = linearLocationDAO.fetchByRoadways(currentRws.map(_.roadwayNumber).toSet).toList.groupBy(ll => (ll.roadwayNumber, ll.linkId))
        linearLocs.foreach(ll => assert(ll._2.size == 1))

        val roadwaysByLinkSource = linearLocationDAO.fetchByRoadways(currentRws.map(_.roadwayNumber).toSet).groupBy(_.linkGeomSource)
        val regularLinkSource = LinkGeomSource.FrozenLinkInterface
        val regular = if (roadwaysByLinkSource.contains(regularLinkSource)) roadwaysByLinkSource(regularLinkSource) else Seq()

       def continuosRoadways(t: Seq[Roadway]): Unit = {
         val it = t.sliding(2)
         while (it.hasNext) {
           it.next() match {
             case Seq(cur, next) =>
               assert(next.startAddrMValue <= next.endAddrMValue)
               assert(cur.endAddrMValue == next.startAddrMValue)
           }
         }
       }

       val currentRwsLeftSide = currentRws.filterNot(_.track == Track.LeftSide)
        val currentRwsRightSide = currentRws.filterNot(_.track == Track.RightSide)

        continuosRoadways(currentRwsLeftSide)
        continuosRoadways(currentRwsRightSide)

        val addresses = currentRws.flatMap(r => {
          roadwayAddressMapper.mapRoadAddresses(r, regular)
        })

       def continuosRoadAddressses(t: Seq[RoadAddress]): Unit = {
         val it = t.sliding(2)
         while (it.hasNext) {
           it.next() match {
             case Seq(cur, next) =>
               assert(next.startAddrMValue <= next.endAddrMValue)
               assert(cur.endAddrMValue == next.startAddrMValue)
           }
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

        cals.filterNot(_.typeCode == CalibrationPointType.JunctionPointCP) //puuttuu cp alusta ja roadwaypoint myös

        val currentRoadwayPoints = roadwayPointDAO.fetchByRoadwayNumbers(currentRws.map(_.roadwayNumber).distinct)

        val x = currentRoadwayPoints.filter(c => cals.map(_.roadwayPointId).contains(c.id) )
        //val y = currentRoadwayPoints.filterNot(c => cals.map(_.roadwayPointId).contains(c.id) )

        /* Check correct roadwaynumbers. */
        cals.forall(cal => {
          val t1 = x.find(_.id == cal.roadwayPointId)
          t1.isDefined && t1.get.roadwayNumber == cal.roadwayNumber
        } ) shouldBe true

        val roadAddressCals = cals.filter(_.typeCode == CalibrationPointType.RoadAddressCP).groupBy(_.addrM)
        roadAddressCals.minBy(_._1)._1 shouldBe currentRws.minBy(_.startAddrMValue).startAddrMValue
        roadAddressCals.maxBy(_._1)._1 shouldBe currentRws.maxBy(_.endAddrMValue).endAddrMValue

        println("All good! :)")

      } /* Rollback */
    }
  }
}
