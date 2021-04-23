package tcep.graph.qos

import org.slf4j.LoggerFactory
import tcep.data.Events.{AverageLoad, Event}
import tcep.data.Queries._
import tcep.dsl.Dsl.LoadMeasurement

case class LoadMonitor(query: Query, record: Option[LoadMeasurement]) extends Monitor{
  val log = LoggerFactory.getLogger(getClass)
  var loadRequirement: Option[LoadRequirement] = query.requirements.collect { case lr: LoadRequirement => lr }.headOption

  override def onEventEmit(event: Event, status: Int): X = {
    val currentLoad = event.getMonitoringItem[AverageLoad]()

    if(record.isDefined && currentLoad.isDefined) record.get.apply(currentLoad.get.load)

    if(loadRequirement.isDefined && loadRequirement.get.otherwise.isDefined){
      val loadRequirementVal = loadRequirement.get.machineLoad.value
      val currentLoadVal = currentLoad.get.load.value
      loadRequirement.get.operator match {
        case Equal =>        if (!(currentLoadVal == loadRequirementVal)) loadRequirement.get.otherwise.get.apply(currentLoad.get.load)
        case NotEqual =>     if (!(currentLoadVal != loadRequirementVal)) loadRequirement.get.otherwise.get.apply(currentLoad.get.load)
        case Greater =>      if (!(currentLoadVal >  loadRequirementVal)) loadRequirement.get.otherwise.get.apply(currentLoad.get.load)
        case GreaterEqual => if (!(currentLoadVal >= loadRequirementVal)) loadRequirement.get.otherwise.get.apply(currentLoad.get.load)
        case Smaller =>      if (!(currentLoadVal <  loadRequirementVal)) loadRequirement.get.otherwise.get.apply(currentLoad.get.load)
        case SmallerEqual => if (!(currentLoadVal <= loadRequirementVal)) loadRequirement.get.otherwise.get.apply(currentLoad.get.load)
      }
    }
  }
}

case class LoadMonitorFactory(query: Query, record: Option[LoadMeasurement]) extends MonitorFactory {
  override def createNodeMonitor: Monitor = LoadMonitor(query, record)
}
