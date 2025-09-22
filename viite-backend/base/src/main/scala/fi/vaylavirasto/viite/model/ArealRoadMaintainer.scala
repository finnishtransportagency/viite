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
   * Id is a string consisting of the typeName and number of this ArealRoadMaintainer.
   * This in the id we want to save to the database, too. */
  final def id: String = {  s"$typeName$number"  }

  /** Overriding basic toString, printing the ArealRoadMaintainer as a string. */
  override def toString    : String = {  s"$typeName $number $name"  }
  final def toStringVerbose: String = {  s"$typeName $number $name"      }
  final def toStringShort  : String = {  s"$shortName"                       }
  final def toStringAll    : String = {  s"$typeName $number $name ($shortName)" }
}

/** A specialized trait for EVKs, with pre-defined typeName and typeInfo for them. */
trait EVK extends ArealRoadMaintainer {
  override val typeName: String = "EVK"
  override val typeInfo: String = "Elinvoimakeskus. Vastuussa teiden hallinnoinnista 01.01.2026 alkaen."
  val    number: Int
  val      name: String
  val shortName: String
  val isELY: Boolean = true
}

/** A specialized trait for ELYs, with pre-defined typeName and typeInfo for them. */
trait ELY extends ArealRoadMaintainer {
  override val typeName: String = "ELY"
  override val typeInfo: String = "Elinvoima- liikenne ja ympäristökeskus. Vastuussa teiden hallinnoinnista 31.12.2025 saakka."
  val    number: Int
  val      name: String
  val shortName: String
  val isELY: Boolean = false
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
  case object EVKTEST              extends EVK {    val number =  0;  val name =  "TESTI_EVK";        val shortName = "TEST"    }

  /* Create pre-defined (traffic responsibility) ELY instances */
  case object ELYUusimaa           extends ELY {    val number =  1;  val name = "Uusimaa";           val shortName = "UUD"    }
  case object ELYVarsinaisSuomi    extends ELY {    val number =  2;  val name = "Varsinais-Suomi";   val shortName = "VAR"    }
  case object ELYKaakkoisSuomi     extends ELY {    val number =  3;  val name = "Kaakkois-Suomi";    val shortName = "KAS"    }
  case object ELYPirkanmaa         extends ELY {    val number =  4;  val name = "Pirkanmaa";         val shortName = "PIR"    }
  case object ELYPohjoisSavo       extends ELY {    val number =  8;  val name = "Pohjois-Savo";      val shortName = "POS"    }
  case object ELYKeskiSuomi        extends ELY {    val number =  9;  val name = "Keski-Suomi";       val shortName = "KES"    }
  case object ELYEteläPohjanmaa    extends ELY {    val number = 10;  val name = "Etelä-Pohjanmaa";   val shortName = "EPO"    }
  case object ELYPohjoisPohjanmaa  extends ELY {    val number = 12;  val name = "Pohjois-Pohjanmaa"; val shortName = "POP"    }
  case object ELYLappi             extends ELY {    val number = 14;  val name = "Lappi";             val shortName = "LAP"    }

  /* List of pre-defined EVK instances. These are the only ones Viite accepts. */
  private val EVKset: Set[EVK] = Set[EVK](
    EVKUusimaa,          EVKLounaisSuomi,
    EVKKaakkoisSuomi,    EVKSisäSuomi,
    EVKKeskiSuomi,       EVKItäSuomi,
    EVKEteläPohjanmaa,   EVKPohjanmaa,
    EVKPohjoisSuomi,     EVKLappi,
    EVKTEST
  )

  /* List of pre-defined (traffic responsibility) ELY instances. These are the only ELYs Viite accepts. */
  private val ELYset = Set[ELY](
    ELYUusimaa,         ELYVarsinaisSuomi,
    ELYKaakkoisSuomi,   ELYPirkanmaa,
    ELYPohjoisSavo,     ELYKeskiSuomi,
    ELYEteläPohjanmaa,  ELYPohjoisPohjanmaa,
    ELYLappi
  )

  /*
  * @param arm The ArealRoadMaintainer to be checked, if it is a proper ELY.
    * @return true, if the arm asked is an ELY, false else. */

  def isELY(arm: ArealRoadMaintainer): Boolean = {
    if(arm.typeName!="ELY")
      false
    else
      ELYset.find(_ == arm) match {
        case Some(_) => true
        case None    => false
      }
  }


  def getELYNumberOrNA(armOpt: Option[ArealRoadMaintainer]): Option[Long] = {
    armOpt match {
      case Some(arm) => if(ArealRoadMaintainer.isELY(arm)) {  Some(arm.number.toLong)  } else {  Some(0L)  }
      case None      => None
    }
  }

  /*
* @param arm The ArealRoadMaintainer to be checked, if it is a proper EVK.
  * @return true, if the arm asked is an EVK, false else. */

  def isEVK(arm: ArealRoadMaintainer): Boolean = {
    if(arm.typeName!="EVK")
      false
    else
      EVKset.find(_ == arm) match {
        case Some(_) => true
        case None    => false
      }
  }


  def getEVKNumberOrNA(armOpt: Option[ArealRoadMaintainer]): Option[Long] = {
    armOpt match {
      case Some(arm) => if(ArealRoadMaintainer.isEVK(arm)) {  Some(arm.number.toLong)  } else {  Some(0L)  }
      case None      => None
    }
  }

  /** Getter for EVKs only. You may search for an EVK by its number.
   *
   * @param number The number of the EVK you wish to get.
   * @return The EVK asked, when found.
   */
  def getEVK(number: Int): EVK = {
    EVKset.find(_.number == number).getOrElse(
      throw ViiteException(s"Olematon EVK ($number)!")
        // TODO Either:ify the throw?
    )
  }

  /** Getter for ELYs only. You may search for an ELY by its number.
   *
   * @param number The number of the ELY you wish to get.
   * @return The ELY asked, when found.
   */
  def getELY(number: Int): ELY = {
    ELYset.find(_.number == number).getOrElse(
      throw ViiteException(s"Olematon ELY ($number)!")
        // TODO Either:ify the throw?
    )
  }

  /** Getter for EVKs only. You may search for an EVK by its DBname, name, or shortName.
   *
   * @param string The string we use to identify the correct EVK to be returned.
   * @return The EVK asked, when found.
   */
  def getEVK(string: String): EVK = {
    if (string == "ELY0") {
      EVKTEST
    }
    else {
    EVKset.find( _.id == string).getOrElse(             // look for "EVK1"
      EVKset.find(  _.name == string).getOrElse(        // look for "Uusimaa"
        EVKset.find(_.shortName == string).getOrElse(   // look for "UUSI"
          // TODO Either:ify the throw?
          throw ViiteException(s"Olematon EVK ('$string')!")   // found nothing resembling the string
        )
      )
    )}
  }

  /** Getter for ELYs only. You may search for an ELY by its DBname, name, or shortName.
   *
   * @param string The string we use to identify the correct ELY to be returned.
   * @return The ELY asked, when found.
   */
  def getELY(string: String): ELY = {
    ELYset.find( _.id == string).getOrElse(             // look for "ELY1"
      ELYset.find(  _.name == string).getOrElse(        // look for "Uusimaa"
        ELYset.find(_.shortName == string).getOrElse(   // look for "UUD"
          // TODO Either:ify the throw?
          throw ViiteException(s"Olematon ELY ('$string')!")   // found nothing resembling the string
        )
      )
    )
  }

  /** Existance checker for EVKs only.
   *
   * @param evk The EVK to be identified
   * @return true, if the evk asked is a proper EVK, false else.
   */
  def existsEVK(evk: EVK): Boolean = {
    EVKset.find(_ == evk) match {
      case Some(_) => true
      case None    => false
    }
  }

  /** Existance checker for ELYs only.
   *
   * @param ely The ELY to be identified
   * @return true, if the ely asked is a proper ELY, false else.
   */
  def existsELY(ely: ELY): Boolean = {
    ELYset.find(_ == ely) match {
      case Some(_) => true
      case None    => false
    }
  }

  /**
   * Getter/constructor.
   * As the only allowed ArealRoadMaintainers are pre-defined, getter and "constructor" are the same.
   *
   * @param nameString The string to be interpreted as an ArealRoadMaintainer.
   * @return an ArealRoadMaintainer, according to the given nameString
   * @throws ViiteException, if the given string does not correspond to any known ArealRoadMaintainer instance.
   */
  def apply(nameString: String): ArealRoadMaintainer = {

    // Interpret the nameString as it would be in the database: $typeName$number, and find an instance with that.
    // Sole names, or numbers are not unique. And I doubt whether the shortNames either.
    EVKset.find(evk => s"${evk.typeName}${evk.number}" == nameString).getOrElse(
      ELYset.find(ely => s"${ely.typeName}${ely.number}" == nameString).getOrElse(
                // If nothing was found, just throw an error. There is no such thing as asked, afawk.
                throw ViiteException(s"Tuntematon tieverkon ylläpitäjätaho ($nameString)!")
                // TODO Either:ify the throw?
      )
    )
  }

}


