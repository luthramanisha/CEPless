package tcep.graph.qos

import org.slf4j.LoggerFactory
import tcep.data.Events.{Event, PlacementOverhead}
import tcep.data.Queries._
import tcep.dsl.Dsl.PlacementOverheadMeasurement

case class PlacementOverheadMonitor(recordOverhead: PlacementOverheadMeasurement) extends Monitor {
  val log = LoggerFactory.getLogger(getClass)

  override def onEventEmit(event: Event, transitionStatus: Int): X = {
      val placementOverheadItem = event.getMonitoringItem[PlacementOverhead]()
      recordOverhead(placementOverheadItem.get.messageOverhead)
  }
}

case class PlacementOverheadMonitorFactory(query: Query, overheadMeasurement: PlacementOverheadMeasurement) extends MonitorFactory {
  override def createNodeMonitor: Monitor = PlacementOverheadMonitor(overheadMeasurement)
}
