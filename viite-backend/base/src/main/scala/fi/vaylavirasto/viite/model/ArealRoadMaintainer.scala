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
  val        id: Int    // An ArealRoadMaintainer must have an id, unique amongs the same typeName
  val      name: String // An ArealRoadMaintainer must have a human-readable name
  val shortName: String // An ArealRoadMaintainer must have a short name

  assert(typeName!="DEFAULT-NONE","Your implementing trait must override typeName.")

  /** Overriging basic toString, printing the ArealRoadMaintainer as a string. */
  override def toString    : String = {  s"$typeName $id $name"  }

  /** Returns the string describing the ArealRoadMaintainer in the format we want to save it to the database. */
  final def toDBName       : String = {  s"$typeName$id"            }

  final def toStringVerbose: String = {  s"$typeName $id $name"        }
  final def toStringShort  : String = {  s"$shortName"                    }
  final def toStringAll    : String = {  s"$typeName $id $name ($shortName)" }
}

/** Companion object for the abstract ArealRoadMaintainer trait.
 * Defines the ArealRoadMaintainer instances there are, and their access should be through this class. */
object ArealRoadMaintainer {

  /** Getter/constructor.
   * @return an ArealRoadMaintainer, according to the given nameString
   * @throws ViiteException, if the given string does not correspond to any known ArealRoadMaintainer.
   * @param nameString The string to be interpreted as an ArealRoadMaintainer. */
  def apply(nameString: String): ArealRoadMaintainer = {
                // If still nothing was found, just throw an error. There is no such thing, afawk.
                throw ViiteException(s"Tuntematon tieverkon ylläpitäjätaho ($nameString)!")

  }

}


