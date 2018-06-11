package fi.liikennevirasto.viite.process.strategy

import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.ProjectLink

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

trait RoadAddressSectionCalculatorStrategy {

  val name: String

  def applicableStrategy(projectLinks: Seq[ProjectLink]): Boolean = false

  def assignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink]
}
