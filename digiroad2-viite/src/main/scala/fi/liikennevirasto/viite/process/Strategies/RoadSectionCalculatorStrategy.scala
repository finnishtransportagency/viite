package fi.liikennevirasto.viite.process.Strategies

import fi.liikennevirasto.viite.dao.CalibrationPointDAO.UserDefinedCalibrationPoint
import fi.liikennevirasto.viite.dao.ProjectLink

object RoadSectionCalculatorContext {

  private lazy val combineSectionCalculatorStrategy: CombineSectionCalculatorStrategy = {
    new CombineSectionCalculatorStrategy
  }

  private lazy val roundaboutSectionCalculatorStrategy: RoundaboutSectionCalculatorStrategy = {
    new RoundaboutSectionCalculatorStrategy
  }

  private val defaultStrategy = combineSectionCalculatorStrategy
  private val strategies = Seq(roundaboutSectionCalculatorStrategy)

  def getStrategy(projectLinks: Seq[ProjectLink]): RoadSectionCalculatorStrategy = {
    strategies.find(_.applicableStrategy(projectLinks)).getOrElse(defaultStrategy)
  }

}

trait RoadSectionCalculatorStrategy {

  val name: String

  def applicableStrategy(projectLinks: Seq[ProjectLink]): Boolean = false

  def assignMValues(newProjectLinks: Seq[ProjectLink], oldProjectLinks: Seq[ProjectLink], userCalibrationPoints: Seq[UserDefinedCalibrationPoint]): Seq[ProjectLink]
}
