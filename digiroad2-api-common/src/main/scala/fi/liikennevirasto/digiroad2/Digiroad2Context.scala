package fi.liikennevirasto.digiroad2

import java.util.Properties
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, Props}
import fi.liikennevirasto.digiroad2.client.vvh.VVHClient
import fi.liikennevirasto.digiroad2.municipality.MunicipalityProvider
import fi.liikennevirasto.digiroad2.service._
import fi.liikennevirasto.digiroad2.user.UserProvider
import fi.liikennevirasto.digiroad2.util.JsonSerializer
import fi.liikennevirasto.viite.dao.{LinearLocationDAO, _}
import fi.liikennevirasto.viite.process.RoadAddressFiller.{ChangeSet, LinearLocationAdjustment}
import fi.liikennevirasto.viite._
import fi.liikennevirasto.viite.process.RoadwayAddressMapper
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

class RoadAddressUpdater(roadAddressService: RoadAddressService) extends Actor {
  def receive = {
    case w: ChangeSet => roadAddressService.updateChangeSet(w)
    case _                    => println("roadAddressUpdater: Received unknown message")
  }
}

//class RoadAddressUpdater(roadAddressService: RoadAddressService) extends Actor {
//  def receive = {
//    case w: Seq[any] => roadAddressService.createUnaddressedRoadLink(w.asInstanceOf[Seq[UnaddressedRoadLink]])
//    case _                    => println("roadAddressUpdater: Received unknown message")
//  }
//}
//
//class RoadAddressMerger(roadAddressService: RoadAddressService) extends Actor {
//  def receive = {
//    case w: RoadAddressMerge => roadAddressService.mergeRoadAddress(w.asInstanceOf[RoadAddressMerge])
//    case _                    => println("roadAddressMerger: Received unknown message")
//  }
//}
//
//class RoadAddressAdjustment(roadAddressService: RoadAddressService) extends Actor {
//  def receive = {
//    case w: Seq[any] => roadAddressService.saveAdjustments(w.asInstanceOf[Seq[LinearLocationAdjustment]])
//    case _                    => println("roadAddressUpdater: Received unknown message")
//  }
//}

//TODO check if this is needed at VIITE-1538
//class RoadAddressFloater(roadAddressService: RoadAddressService) extends Actor {
//  def receive = {
//    case w: Set[any] => roadAddressService.checkRoadAddressFloating(w.asInstanceOf[Set[Long]])
//    case _                    => println("roadAddressUpdater: Received unknown message")
//  }
//}

class RoadNetworkChecker(roadNetworkService: RoadNetworkService) extends Actor {
  def receive = {
    case w: RoadCheckOptions =>  roadNetworkService.checkRoadAddressNetwork(w)
    case _ => println("roadAddressChecker: Received unknown message")
  }
}

object Digiroad2Context {
  val Digiroad2ServerOriginatedResponseHeader = "Digiroad2-Server-Originated-Response"
  lazy val properties: Properties = {
    val props = new Properties()
    props.load(getClass.getResourceAsStream("/digiroad2.properties"))
    props
  }
  lazy val revisionInfo: Properties = {
    val props = new Properties()
      props.load(getClass.getResourceAsStream("/revision.properties"))
    props
  }

  val system = ActorSystem("Digiroad2")
  import system.dispatcher
  val logger = LoggerFactory.getLogger(getClass)
  system.scheduler.schedule(FiniteDuration(2, TimeUnit.MINUTES), FiniteDuration(1, TimeUnit.MINUTES)) { // first query after 2 minutes, then once per minute
    try {
      projectService.updateProjectsWaitingResponseFromTR()
    } catch {
      case  NonFatal(ex) =>
        logger.error("Exception at TR checks:" + ex.getMessage)
        System.err.println("Exception at TR checks: " + ex.getMessage)
    }
  }

  system.scheduler.schedule(FiniteDuration(5, TimeUnit.MINUTES), FiniteDuration(5, TimeUnit.MINUTES)) { // first query after 5 minutes, then once per 5 minute
    try {
      projectService.sendProjectsInWaiting()
    } catch {
      case ex: Exception => System.err.println("Exception when sending projects to TR: " + ex.getMessage)
    }
  }




//  val roadAddressUpdater = system.actorOf(Props(classOf[RoadAddressUpdater], roadAddressService), name = "roadAddressUpdater")
//  eventbus.subscribe(roadAddressUpdater, "roadAddress:persistUnaddressedRoadLink")

//  val roadAddressMerger = system.actorOf(Props(classOf[RoadAddressMerger], roadAddressService), name = "roadAddressMerger")
//  eventbus.subscribe(roadAddressMerger, "roadAddress:mergeRoadAddress")

//  val roadAddressAdjustment = system.actorOf(Props(classOf[RoadAddressAdjustment], roadAddressService), name = "roadAddressAdjustment")
//  eventbus.subscribe(roadAddressAdjustment, "roadAddress:persistAdjustments")

//  val roadAddressFloater = system.actorOf(Props(classOf[RoadAddressFloater], roadAddressService), name = "roadAddressFloater")
//  eventbus.subscribe(roadAddressFloater, "roadAddress:floatRoadAddress")

  val roadAddressUpdater = system.actorOf(Props(classOf[RoadAddressUpdater], roadAddressService), name = "roadAddressUpdater")
  eventbus.subscribe(roadAddressUpdater, "roadAddress:persistChangeSet")

  val roadNetworkChecker = system.actorOf(Props(classOf[RoadNetworkChecker], roadNetworkService), name = "roadNetworkChecker")
  eventbus.subscribe(roadNetworkChecker, "roadAddress:RoadNetworkChecker")

  lazy val roadAddressService: RoadAddressService = {
    new RoadAddressService(roadLinkService, roadwayDAO, linearLocationDAO, new RoadNetworkDAO, new UnaddressedRoadLinkDAO, roadwayAddressMapper, eventbus, properties.getProperty("digiroad2.VVHRoadlink.frozen", "false").toBoolean)
  }

  lazy val projectService: ProjectService = {
    new ProjectService(roadAddressService, roadLinkService, eventbus, properties.getProperty("digiroad2.VVHRoadlink.frozen", "false").toBoolean)
  }

  lazy val roadNetworkService: RoadNetworkService = {
    new RoadNetworkService
  }

  lazy val roadNameService : RoadNameService = {
    new RoadNameService
  }

  lazy val authenticationTestModeEnabled: Boolean = {
    properties.getProperty("digiroad2.authenticationTestMode", "false").toBoolean
  }

  lazy val userProvider: UserProvider = {
    Class.forName(properties.getProperty("digiroad2.userProvider")).newInstance().asInstanceOf[UserProvider]
  }

  lazy val municipalityProvider: MunicipalityProvider = {
    Class.forName(properties.getProperty("digiroad2.municipalityProvider")).newInstance().asInstanceOf[MunicipalityProvider]
  }

  lazy val eventbus: DigiroadEventBus = {
    Class.forName(properties.getProperty("digiroad2.eventBus")).newInstance().asInstanceOf[DigiroadEventBus]
  }

  lazy val vvhClient: VVHClient = {
    new VVHClient(getProperty("digiroad2.VVHRestApiEndPoint"))
  }

  lazy val roadLinkService: RoadLinkService = {
    new RoadLinkService(vvhClient, eventbus, new JsonSerializer)
  }

  lazy val roadwayDAO: RoadwayDAO = {
    new RoadwayDAO
  }

  lazy val linearLocationDAO: LinearLocationDAO = {
    new LinearLocationDAO
  }

  lazy val roadwayAddressMapper: RoadwayAddressMapper = {
    new RoadwayAddressMapper(roadwayDAO, linearLocationDAO)
  }

  lazy val revision: String = {
    revisionInfo.getProperty("digiroad2.revision")
  }
  lazy val deploy_date: String = {
    revisionInfo.getProperty("digiroad2.latestDeploy")
  }

  val env = System.getProperty("env")
  def getProperty(name: String) = {
    val property = properties.getProperty(name)
    if(property != null)
      property
    else
      throw new RuntimeException(s"cannot find property $name for enviroment: $env")
  }
}