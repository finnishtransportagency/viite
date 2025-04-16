package fi.vaylavirasto.viite.model

import fi.vaylavirasto.viite.util.ViiteException

/**
 * The base class interface for all ArealRoadMaintainer types.
 * An ArealRoadMaintainer must have a typeName, telling the generation of the road maintainers, e.g. "EVK"
 * Each ArealRoadMaintainer must have an id unique among the same typeName.
 *
 * Extend this trait as a trait to get a new type of ArealRoadMaintainers.
 * Add your pre-defined list of the new type as objects.
 * Finally, list the new type objects to the ArealRoadMaintainer companion object for a catalog.
 *
 * This trait is sealed, so that all new ArealRoadMaintainer types will be found within this file.
 */
sealed trait ArealRoadMaintainer {

  val  typeName: String = "DEFAULT-NONE"
  val  typeInfo: String = "Describe your new typeName here"
  val    number: Int    // An ArealRoadMaintainer must have a number, and it must be set to be unique among the same typeName
  val      name: String // An ArealRoadMaintainer must have a human-readable name
  val shortName: String // An ArealRoadMaintainer must have a short name

  assert(typeName!="DEFAULT-NONE", "Your implementing trait must override typeName.")
  assert(typeInfo!="Describe your new typeName here", "Your implementing trait must override typeInfo.")

  /** Returns the id for this ArealRoadMaintainer.
   * Id is a string consisting of the typeName, and number of this ArealRoadMaintainer.
   * This in the id we want to save to the database, too. */
  final def id: String = {  s"$typeName$number"  }

  /** Overriding basic toString, printing the ArealRoadMaintainer as a string. */
  override def toString    : String = {  s"$typeName $number $name"  }
  final def toStringVerbose: String = {  s"$typeName $number $name"      }
  final def toStringShort  : String = {  s"$shortName"                       }
  final def toStringAll    : String = {  s"$typeName $number $name ($shortName)" }
}

/** A specialized trait for EVKs, with pre-defined typeName, and typeInfo for them. */
trait EVK extends ArealRoadMaintainer {
  override val typeName: String = "EVK"
  override val typeInfo: String = "Elinvoimakeskus. Vastuussa teiden hallinnoinnista 01.01.2026 alkaen."
  val    number: Int
  val      name: String
  val shortName: String
}

/** Companion object for the abstract ArealRoadMaintainer trait.
 * Defines the ArealRoadMaintainer instances there are, and access to them is to be done through this companion object. */
object ArealRoadMaintainer {

  /* Create pre-defined EVK instances. */
  case object EVKUusimaa           extends EVK {    val number =  1;  val name = "Uusimaa";           val shortName = "UUSI"    }
  case object EVKLounaisSuomi      extends EVK {    val number =  2;  val name = "Lounais-Suomi";     val shortName = "LOUS"    }
  case object EVKKaakkoisSuomi     extends EVK {    val number =  3;  val name = "Kaakkois-Suomi";    val shortName = "KAAS"    }
  case object EVKSisäSuomi         extends EVK {    val number =  4;  val name = "Sisä-Suomi";        val shortName = "SISS"    }
  case object EVKKeskiSuomi        extends EVK {    val number =  5;  val name = "Keski-Suomi";       val shortName = "KESS"    }
  case object EVKItäSuomi          extends EVK {    val number =  6;  val name = "Itä-Suomi";         val shortName = "ITÄS"    }
  case object EVKEteläPohjanmaa    extends EVK {    val number =  7;  val name = "Etelä-Pohjanmaa";   val shortName = "ETPO"    }
  case object EVKPohjanmaa         extends EVK {    val number =  8;  val name = "Pohjanmaa";         val shortName = "POHJ"    }
  case object EVKPohjoisSuomi      extends EVK {    val number =  9;  val name = "Pohjois-Suomi";     val shortName = "POHS"    }
  case object EVKLappi             extends EVK {    val number = 10;  val name = "Lappi";             val shortName = "LAPP"    }

  /* List of pre-defined EVK instances. These are the only ones Viite accepts. */
  private val EVKset: Set[EVK] = Set[EVK](
    EVKUusimaa,          EVKLounaisSuomi,
    EVKKaakkoisSuomi,    EVKSisäSuomi,
    EVKKeskiSuomi,       EVKItäSuomi,
    EVKEteläPohjanmaa,   EVKPohjanmaa,
    EVKPohjoisSuomi,     EVKLappi
  )

  /** Getter for EVKs only. You may search for an EVK by its number.
   *
   * @param number The number of the EVK you wish to get.
   * @return The EVK asked, when found.
   **/
  def getEVK(number: Int): EVK = {
    EVKset.find(_.number == number).getOrElse(
      throw ViiteException(s"Olematon EVK ($number)!")
        // TODO Either:ify the throw?
    )
  }

  /** Getter for EVKs only. You may search for an EVK by its DBname, name, or shortName.
   *
   * @param string The string we use to identify the correct EVK to be returned.
   * @return The EVK asked, when found.
   */
  def getEVK(string: String): EVK = {
    EVKset.find( _.id == string).getOrElse(             // look for "EVK1"
      EVKset.find(  _.name == string).getOrElse(        // look for "Uusimaa"
        EVKset.find(_.shortName == string).getOrElse(   // look for "UUSI"
          // TODO Either:ify the throw?
          throw ViiteException(s"Olematon EVK ('$string')!")   // found nothing resembling the string
        )
      )
    )
  }

  /** Existance checker for EVKs only.
   *
   * @param evk The EVK to be identified
   * @return true, if the evk asked is a proper EVK, false else. */
  def existsEVK(evk: EVK): Boolean = {
    EVKset.find(_ == evk) match {
      case Some(_) => true
      case None    => false
    }
  }

  /**
   * Getter/constructor.
   * As the only allowed ArealRoadMaintainers are pre-defined, getter, and "constructor" are the same.
   *
   * @param nameString The string to be interpreted as an ArealRoadMaintainer.
   * @return an ArealRoadMaintainer, according to the given nameString
   * @throws ViiteException, if the given string does not correspond to any known ArealRoadMaintainer instance.
   */
  def apply(nameString: String): ArealRoadMaintainer = {

    // Interpret the nameString as it would be in the database: $typeName$number, and find an instance with that.
    // Sole names, or numbers are not unique. And I doubt whether the shortNames either.
    EVKset.find(evk => s"${evk.typeName}${evk.number}" == nameString).getOrElse(
                // If nothing was found, just throw an error. There is no such thing as asked, afawk.
                throw ViiteException(s"Tuntematon tieverkon ylläpitäjätaho ($nameString)!")
                // TODO Either:ify the throw?
    )
  }

}


