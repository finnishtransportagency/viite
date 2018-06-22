package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.ProjectLink

/**
  * Manage all the existing road address section
  */
object RoadAddressSectionCalculatorContext {

  private lazy val defaultSectionCalculatorStrategy: DefaultSectionCalculatorStrategy = {
    new DefaultSectionCalculatorStrategy
  }

  private lazy val roundaboutSectionCalculatorStrategy: RoundaboutSectionCalculatorStrategy = {
    new RoundaboutSectionCalculatorStrategy
  }

  private val strategies = Seq(roundaboutSectionCalculatorStrategy)

  def getStrategy(projectLinks: Seq[ProjectLink]): RoadAddressSectionCalculatorStrategy = {
    strategies.find(_.applicableStrategy(projectLinks)).getOrElse(defaultSectionCalculatorStrategy)
  }

}

/**
  * Strategy use to recalculate project links on the same road part
  */
trait RoadAddressSectionCalculatorStrategy {

  val name: String

  /**
    * Check if the current strategy can be applied for the specified project links
    * @param projectLinks The project links on the same road part
    * @return Returns true if the strategy should be applied
    */
  def applicableStrategy(projectLinks: Seq[ProjectLink]): Boolean = false

  /**
    * Recalculate and assign all the address measures for all the given project links
    * @param newProjectLinks Project links that DOESN'T exists on the road address network
    * @param oldProjectLinks Project links that already exists on the road address network
    * @param userCalibrationPoints User defined calibration points
    * @return Returns all the project links with recalculated measures
    */
  def assignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink]
}
