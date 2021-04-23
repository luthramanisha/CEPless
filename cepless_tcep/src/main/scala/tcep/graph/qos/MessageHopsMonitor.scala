package tcep.graph.qos

import org.slf4j.LoggerFactory
import tcep.data.Events.{Event, MessageHops}
import tcep.data.Queries.{MessageHopsRequirement, _}
import tcep.dsl.Dsl.MessageHopsMeasurement

case class MessageHopsMonitor(query: Query, record: Option[MessageHopsMeasurement]) extends Monitor{

  var logger = LoggerFactory.getLogger(getClass)
  val hopsRequirement = query.requirements.collect { case lr: MessageHopsRequirement => lr }.headOption


  override def onEventEmit(event: Event, status: Int): X = {
    val hopsItem = event.getMonitoringItem[MessageHops]()

    if(record.isDefined && hopsItem.isDefined) record.get.apply(hopsItem.get.hops)

    if(hopsRequirement.isDefined && hopsRequirement.get.otherwise.isDefined){
      val hopsRequirementVal = hopsRequirement.get.requirement
      val currentHops = hopsItem.get.hops
      hopsRequirement.get.operator match {
        case Equal =>        if (!(currentHops == hopsRequirementVal)) hopsRequirement.get.otherwise.get.apply(hopsItem.get.hops)
        case NotEqual =>     if (!(currentHops != hopsRequirementVal)) hopsRequirement.get.otherwise.get.apply(hopsItem.get.hops)
        case Greater =>      if (!(currentHops >  hopsRequirementVal)) hopsRequirement.get.otherwise.get.apply(hopsItem.get.hops)
        case GreaterEqual => if (!(currentHops >= hopsRequirementVal)) hopsRequirement.get.otherwise.get.apply(hopsItem.get.hops)
        case Smaller =>      if (!(currentHops <  hopsRequirementVal)) hopsRequirement.get.otherwise.get.apply(hopsItem.get.hops)
        case SmallerEqual => if (!(currentHops <= hopsRequirementVal)) hopsRequirement.get.otherwise.get.apply(hopsItem.get.hops)
      }
    }
  }
}


case class MessageHopsMonitorFactory(query: Query, record: Option[MessageHopsMeasurement]) extends MonitorFactory {
  override def createNodeMonitor: Monitor = MessageHopsMonitor(query, record)
}
