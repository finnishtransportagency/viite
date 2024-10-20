package fi.liikennevirasto.viite.util

import java.io.{File, FileReader, FileWriter}
import java.nio.file.Files.copy
import java.nio.file.Paths
import fi.liikennevirasto.digiroad2.client.kgv.{ChangeInfo, ChangeType}
import fi.liikennevirasto.digiroad2.util.KGVSerializer
import fi.vaylavirasto.viite.geometry.Point
import fi.vaylavirasto.viite.model.{AdministrativeClass, Discontinuity, LifecycleStatus, LinkGeomSource, RoadAddressChangeType, LinkType, RoadLink, SideCode, Track, TrafficDirection}
import org.json4s._
import org.json4s.JsonAST.{JDouble, JInt, JObject, JString}
import org.json4s.jackson.Serialization.{read, write}
import org.slf4j.LoggerFactory

class JsonSerializer extends KGVSerializer {
  val logger = LoggerFactory.getLogger(getClass)
  protected implicit val jsonFormats: Formats = DefaultFormats + SideCodeSerializer + TrafficDirectionSerializer +
    LinkTypeSerializer + AdministrativeClassSerializer + LinkGeomSourceSerializer + LifecycleStatusSerializer +
    DiscontinuitySerializer + TrackSerializer + PointSerializer + ChangeTypeSerializer

  override def readCachedGeometry(file: File): Seq[RoadLink] = {
    val json = new FileReader(file)
    read[Seq[RoadLink]](json)
  }

  override def readCachedChanges(file: File): Seq[ChangeInfo] = {
    val json = new FileReader(file)
    read[Seq[ChangeInfo]](json)
  }

  override def writeCache(file: File, objects: Seq[Object]): Boolean = {

    def writeObjects(objects: Seq[Object], fw: FileWriter): Unit = {
      if (objects.nonEmpty) {
        fw.write(write(objects.head))
        if (objects.tail.nonEmpty) {
          fw.write(",")
          writeObjects(objects.tail, fw)
        }
      }
    }

    val tmpFile = File.createTempFile("tmp", "cached")
    tmpFile.deleteOnExit()

    val fw = new FileWriter(tmpFile)
    try {
      fw.write("[")
      writeObjects(objects, fw)
      fw.write("]")
    } finally {
      fw.close()
    }

    if (file.exists())
      file.delete()

    try {
      copy(Paths.get(tmpFile.getAbsolutePath), Paths.get(file.getAbsolutePath))
      return true
    } catch {
      case ex: Exception => logger.warn("Copy failed", ex)
    } finally {
      try {
        if (tmpFile != null) tmpFile.delete()
      } catch {
        case ex: Exception => logger.info("Deleting tmp file failed", ex)
      }
    }
    false

  }

}
object DigiroadSerializers {
  val jsonFormats: Formats = DefaultFormats + SideCodeSerializer + TrafficDirectionSerializer +
    LinkTypeSerializer + AdministrativeClassSerializer + LinkGeomSourceSerializer + LifecycleStatusSerializer +
    DiscontinuitySerializer + TrackSerializer + PointSerializer + RoadAddressChangeTypeSerializer + ChangeTypeSerializer
}

case object ChangeTypeSerializer extends CustomSerializer[ChangeType](format => ( {
  case JInt(changeType) => ChangeType.apply(changeType.toInt)
}, {
  case s: ChangeType => JInt(s.value)
}))

case object SideCodeSerializer extends CustomSerializer[SideCode](format => ( {
  null
}, {
  case s: SideCode => JInt(s.value)
}))

case object TrafficDirectionSerializer extends CustomSerializer[TrafficDirection](format => ( {
  case JString(direction) => TrafficDirection(direction)
}, {
  case t: TrafficDirection => JString(t.toString)
}))

case object LinkTypeSerializer extends CustomSerializer[LinkType](format => ( {
  case JInt(linkType) => LinkType(linkType.toInt)
}, {
  case lt: LinkType => JInt(BigInt(lt.value))
}))

case object AdministrativeClassSerializer extends CustomSerializer[AdministrativeClass](format => ( {
  case JInt(typeInt) => AdministrativeClass(typeInt.toInt)
}, {
  case ac: AdministrativeClass =>
    JInt(BigInt(ac.value))
}))

case object LinkGeomSourceSerializer extends CustomSerializer[LinkGeomSource](format => ( {
  case JInt(typeInt) => LinkGeomSource(typeInt.toInt)
}, {
  case geomSource: LinkGeomSource =>
    JInt(BigInt(geomSource.value))
}))

case object LifecycleStatusSerializer extends CustomSerializer[LifecycleStatus](format => ( {
  case JInt(typeInt) => LifecycleStatus(typeInt.toInt)
}, {
  case lifecycleStatus: LifecycleStatus =>
    JInt(BigInt(lifecycleStatus.value))
}))

case object DiscontinuitySerializer extends CustomSerializer[Discontinuity](format => ( {
  case s: JString => Discontinuity.apply(s.values)
  case i: JInt => Discontinuity.apply(i.values.intValue)
}, {
  case s: Discontinuity => JInt(s.value)
}))

case object TrackSerializer extends CustomSerializer[Track](format => ( {
  case i: JInt => Track.apply(i.values.intValue)
}, {
  case t: Track => JInt(t.value)
}))

case object PointSerializer extends CustomSerializer[Point](format => ( {
  case o: JObject =>
    val coordinates = o.values.mapValues(_.toString.toDouble)
    Point(coordinates("x"), coordinates("y"), coordinates.getOrElse("z", 0.0))
}, {
  case p: Point => JObject(("x", JDouble(p.x)), ("y", JDouble(p.y)), ("z", JDouble(p.z)))
}))

case object RoadAddressChangeTypeSerializer extends CustomSerializer[RoadAddressChangeType](format => ( {
  case i: JInt =>
    RoadAddressChangeType.apply(i.values.intValue)
}, {
  case l: RoadAddressChangeType => JInt(l.value)
}
))
