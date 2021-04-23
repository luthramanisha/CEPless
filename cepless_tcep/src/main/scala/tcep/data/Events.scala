package tcep.data

import akka.actor.{ActorRef, Address}
import akka.event.LoggingAdapter
import tcep.data.Structures.MachineLoad
import tcep.machinenodes.helper.actors.PlacementMessage
import tcep.placement.HostInfo
import tcep.simulation.adaptive.cep.SystemLoad
import tcep.utils.SizeEstimator

import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag

/**
  * Helpers object for conversions and representation of different events in terms of
  * case classes.
  * */
@SerialVersionUID(15L)
object Events extends java.io.Serializable {

  case object Created extends PlacementMessage
  case object DependenciesRequest extends PlacementMessage
  case class DependenciesResponse(dependencies: Seq[ActorRef]) extends PlacementMessage


  sealed trait MonitoringData

  sealed trait Event {
    var createTime = System.currentTimeMillis()
    var monitoringData = ListBuffer.empty[MonitoringData]

    def getMonitoringItem[A <: MonitoringData: ClassTag](): Option[A] = {
      val clazz = implicitly[ClassTag[A]].runtimeClass
      val res = monitoringData.find(clazz.isInstance(_))
      if(res.isDefined){
        Some(res.get.asInstanceOf[A])
      }else{
        Option.empty
      }
    }

    def getOrCreateMonitoringItem[A <: MonitoringData: ClassTag](defaultValue: A): A = {
      val clazz = implicitly[ClassTag[A]].runtimeClass
      val items = monitoringData.find(clazz.isInstance(_))
      if(items.nonEmpty){
        items.head.asInstanceOf[A]
      }else{
        monitoringData += defaultValue
        defaultValue
      }
    }
  }

  case class OperatorHops(var hops: Int) extends MonitoringData // "hops" in the operator graph
  case class MessageHops(var hops: Int) extends MonitoringData // hops between different hosts -> smaller than OperatorHops if operators are hosted on same host
  case class AverageLoad(var load: MachineLoad) extends MonitoringData
  case class Latency(var latency: Long) extends MonitoringData
  case class NetworkUsage(var networkUsage: Map[Address, Double]) extends MonitoringData
  case class PlacementOverhead(var messageOverhead: Long) extends MonitoringData
  case class EventOverhead(var messageOverhead: Long) extends MonitoringData
  case class PredecessorHost(var address: List[Address]) extends MonitoringData
  case class LastUpdate(var time: Map[Address, Long]) extends MonitoringData
  case class StartTime(var time: Long) extends MonitoringData
  case class PublishingRate(var rate: Double) extends MonitoringData
  //case class ParentHost(var ref: List[ActorRef]) extends  MonitoringData

  /**
    * initialize the monitoring data items for an event
    * @param event the event to have its data initialized
    * @param publishingRate events emitted per second
    * @param host address of the current host (predecessor for receiving operator)
    */
  def initializeMonitoringData(log: LoggingAdapter, event: Event, publishingRate: Double, host: Address): Unit = {
    //event.getOrCreateMonitoringItem[ParentHost](ParentHost(List(this.context.self)))
    event.getOrCreateMonitoringItem[OperatorHops](OperatorHops(0))
    event.getOrCreateMonitoringItem[MessageHops](MessageHops(0))
    event.getOrCreateMonitoringItem[AverageLoad](Events.AverageLoad(MachineLoad(0.0d)))
    event.getOrCreateMonitoringItem[Latency](Latency(0l))
    event.getOrCreateMonitoringItem[NetworkUsage](NetworkUsage(Map(host -> 0.0d)))
    event.getOrCreateMonitoringItem[PlacementOverhead](PlacementOverhead(0l))
    event.getOrCreateMonitoringItem[EventOverhead](EventOverhead(0))
    event.getOrCreateMonitoringItem[PredecessorHost](PredecessorHost(List(host)))
    event.getOrCreateMonitoringItem[LastUpdate](LastUpdate(Map(host -> event.createTime)))
    event.getOrCreateMonitoringItem[StartTime](StartTime(System.currentTimeMillis()))
    event.getOrCreateMonitoringItem[PublishingRate](PublishingRate(publishingRate))
  }

  /**
    * @author Niels
    *         updates the monitoring data attached to each event, depending on whether the previous operator is hosted on this host as well or not
    * @param event the event to update
    */
  def updateMonitoringData(log: LoggingAdapter, event: Event, hostInfo: HostInfo): Unit = {
    return
    log.debug(s"updating MonitoringData start \n $event \n ${event.monitoringData.map("\n"+_)}")
    if(event.monitoringData.isEmpty) throw new IllegalArgumentException( s"received empty monitoringData of ${event} ${event.monitoringData}")

    val parents: List[Address] = event.getMonitoringItem[PredecessorHost]().getOrElse(
      throw new RuntimeException(s"missing predecessor host item in event $event")).address
    val operatorHops = event.getMonitoringItem[OperatorHops]().getOrElse(
      throw new RuntimeException(s"missing operator hops item in event $event")).hops + 1
    val lastUpdate = event.getMonitoringItem[LastUpdate]().getOrElse(
      throw new RuntimeException(s"missing last update time item in event $event")).time

    event.monitoringData.foreach {
      case h: MessageHops =>
        h.hops = if(parents.exists(p => !p.equals(hostInfo.member.address))) h.hops + 1 else h.hops

      case o: OperatorHops =>
        o.hops += 1

      case l: AverageLoad =>
        val currentLoad = SystemLoad.getSystemLoad
        l.load = MachineLoad( (currentLoad / operatorHops) + (l.load.value * (operatorHops - 1) / operatorHops) ) // weighted average

      case l: Latency =>
        val latency1 = System.currentTimeMillis() - lastUpdate(parents.head) // latency to parent1; could use these for BDP updates instead of coords?
        val latency2 = if(lastUpdate.tail.nonEmpty) lastUpdate(parents.tail.head) else 0 // latency to other parent
        l.latency += math.max(latency1, latency2) // keep the larger latency since we want to know delay between event creation and reception

      case accumulatedBDP: NetworkUsage =>
        val combinedBDP = accumulatedBDP.networkUsage.values.sum
        accumulatedBDP.networkUsage = Map(hostInfo.member.address -> (hostInfo.operatorMetrics.operatorToParentBDP.values.sum + combinedBDP))
        // update the accumulated BDP by collecting all individual operator BDPs along an events route
        hostInfo.graphTotalBDP = event.getMonitoringItem[NetworkUsage]().getOrElse(
          throw new RuntimeException(s"missing network usage item in event $event")).networkUsage.head._2

      case po: PlacementOverhead =>
        po.messageOverhead += hostInfo.operatorMetrics.accMsgOverhead

      case e: EventOverhead =>
        e.messageOverhead += SizeEstimator.estimate(event)

      case p: PredecessorHost =>
        p.address = List(hostInfo.member.address)

      case c: LastUpdate =>
        c.time = Map(hostInfo.member.address -> System.currentTimeMillis())

      case s: StartTime =>
      // keep time of event creation at publisher
      case p: PublishingRate =>
      // keep publishing rate from publisher(s)
      case _ => throw new IllegalArgumentException(s"unknown monitoring item type in event $event")
    }
    log.debug(s"updating MonitoringData complete \n $event")
  }
  /**
    * Merges the MonitoringData fields of two events into one so Monitoring information doesn't get lost at binary operators
    * @author Niels
    * @param event the event that gets the merged monitoring data attached
    * @param a ListBuffer of the first event to be merged
    * @param b ListBuffer of the other event to be merged
    */
  def mergeMonitoringData(event: Event, a: ListBuffer[MonitoringData], b: ListBuffer[MonitoringData], log: LoggingAdapter): Event = {

    log.debug(s"DEBUG entered mergeMonitoringData with \n ${a} \n $b")
    def getMonitoringItem[A <: MonitoringData: ClassTag](monitoringDataList: ListBuffer[MonitoringData]): Option[A] = {
      val clazz = implicitly[ClassTag[A]].runtimeClass
      val items = monitoringDataList.find(clazz.isInstance(_))
      if(items.nonEmpty){
        Some(items.head.asInstanceOf[A])
      } else {
        Option.empty
      }
    }
    if(a.isEmpty || b.isEmpty) throw new IllegalArgumentException(s"mergeMonitoringData received empty monitoring data list: $a $b")
    if(a.size != 11 || b.size != 11) throw new IllegalArgumentException(s"mergeMonitoringData  monitoring data list of unequal size: $a $b")

    a.foreach({
      case h: MessageHops =>
        val hopsA: Int = getMonitoringItem[MessageHops](a).get.hops
        val hopsB: Int = getMonitoringItem[MessageHops](b).get.hops
        val merged = math.max(hopsA, hopsB)
        event.getOrCreateMonitoringItem[MessageHops](MessageHops(merged))

      case o: OperatorHops =>
        val hopsA: Int = getMonitoringItem[OperatorHops](a).get.hops
        val hopsB: Int = getMonitoringItem[OperatorHops](b).get.hops
        val merged = math.max(hopsA, hopsB)
        event.getOrCreateMonitoringItem[OperatorHops](OperatorHops(merged))

      case l: AverageLoad =>
        val hopsA: Int = getMonitoringItem[OperatorHops](a).get.hops
        val hopsB: Int = getMonitoringItem[OperatorHops](b).get.hops
        val loadA: AverageLoad = getMonitoringItem[AverageLoad](a).get
        val loadB: AverageLoad = getMonitoringItem[AverageLoad](b).get
        val merged = MachineLoad( // average of averages weighted by operator hops
          if (hopsA + hopsB <= 0)
            0.5d * (loadA.load.value + loadB.load.value)
          else
            ((hopsA * loadA.load.value) + (hopsB * loadB.load.value)) / (hopsA + hopsB))
        event.getOrCreateMonitoringItem[AverageLoad](AverageLoad(merged))

      case l: Latency =>
        val latencyA = getMonitoringItem[Latency](a).get.latency
        val latencyB = getMonitoringItem[Latency](b).get.latency
        val merged = math.max(latencyA, latencyB)
        event.getOrCreateMonitoringItem[Latency](Latency(merged))

      case accumulatedBDP: NetworkUsage =>
        val merged = Map(
          getMonitoringItem[NetworkUsage](a).get.networkUsage.head,
          getMonitoringItem[NetworkUsage](b).get.networkUsage.head)
        event.getOrCreateMonitoringItem[NetworkUsage](NetworkUsage(merged))

      case po: PlacementOverhead =>
        val merged =
          getMonitoringItem[PlacementOverhead](a).get.messageOverhead +
            getMonitoringItem[PlacementOverhead](b).get.messageOverhead
        event.getOrCreateMonitoringItem[PlacementOverhead](PlacementOverhead(merged))

      case e: EventOverhead =>
        val merged =
          getMonitoringItem[EventOverhead](a).get.messageOverhead +
            getMonitoringItem[EventOverhead](b).get.messageOverhead
        event.getOrCreateMonitoringItem[EventOverhead](EventOverhead(merged))

      case p: PredecessorHost =>
        val merged = List(
          getMonitoringItem[PredecessorHost](a).get.address.head, // there is always only one address in the list here (see updateMonitoringData())
          getMonitoringItem[PredecessorHost](b).get.address.head)
        event.getOrCreateMonitoringItem[PredecessorHost](PredecessorHost(merged))

      case c: LastUpdate =>
        val merged = Map(
          getMonitoringItem[LastUpdate](a).get.time.head,
          getMonitoringItem[LastUpdate](b).get.time.head)
        event.getOrCreateMonitoringItem[LastUpdate](LastUpdate(merged))

      case s: StartTime =>
        val merged = math.min( // keep oldest
          getMonitoringItem[StartTime](a).get.time,
          getMonitoringItem[StartTime](b).get.time)
        event.getOrCreateMonitoringItem[StartTime](StartTime(merged))

      case p: PublishingRate =>
        val merged = getMonitoringItem[PublishingRate](a).get.rate + getMonitoringItem[PublishingRate](b).get.rate
        event.getOrCreateMonitoringItem[PublishingRate](PublishingRate(merged))

      case _ => throw new IllegalArgumentException(s"unknown monitoring item type in event $event")
    })
    log.debug(s"DEBUG mergeMonitoringData complete ${event.monitoringData}")
    event
  }

  case class Event1(e1: Any)                                              extends Event
  case class Event2(e1: Any, e2: Any)                                     extends Event
  case class Event3(e1: Any, e2: Any, e3: Any)                            extends Event
  case class Event4(e1: Any, e2: Any, e3: Any, e4: Any)                   extends Event
  case class Event5(e1: Any, e2: Any, e3: Any, e4: Any, e5: Any)          extends Event
  case class Event6(e1: Any, e2: Any, e3: Any, e4: Any, e5: Any, e6: Any) extends Event

  val errorMsg: String = "Panic! Control flow should never reach this point!"

  case class EventToBoolean[A, B](event2: Event2){
    def apply[A, B](f: (A, B) => Boolean): Event => Boolean = {
      case Event2(e1, e2) => f.asInstanceOf[(Any, Any) => Boolean](e1, e2)
      case _ => sys.error(errorMsg)
    }
  }
  def toFunEventAny[A](f: (A) => Any): Event => Any = {
    case Event1(e1) => f.asInstanceOf[(Any) => Any](e1)
    case _ => sys.error(errorMsg)
  }

  def toFunEventAny[A, B](f: (A, B) => Any): Event => Any = {
    case Event2(e1, e2) => f.asInstanceOf[(Any, Any) => Any](e1, e2)
    case _ => sys.error(errorMsg)
  }

  def toFunEventAny[A, B, C](f: (A, B, C) => Any): Event => Any = {
    case Event3(e1, e2, e3) => f.asInstanceOf[(Any, Any, Any) => Any](e1, e2, e3)
    case _ => sys.error(errorMsg)
  }

  def toFunEventAny[A, B, C, D](f: (A, B, C, D) => Any): Event => Any = {
    case Event4(e1, e2, e3, e4) => f.asInstanceOf[(Any, Any, Any, Any) => Any](e1, e2, e3, e4)
    case _ => sys.error(errorMsg)
  }

  def toFunEventAny[A, B, C, D, E](f: (A, B, C, D, E) => Any): Event => Any = {
    case Event5(e1, e2, e3, e4, e5) => f.asInstanceOf[(Any, Any, Any, Any, Any) => Any](e1, e2, e3, e4, e5)
    case _ => sys.error(errorMsg)
  }

  def toFunEventAny[A, B, C, D, E, F](f: (A, B, C, D, E, F) => Any): Event => Any = {
    case Event6(e1, e2, e3, e4, e5, e6) => f.asInstanceOf[(Any, Any, Any, Any, Any, Any) => Any](e1, e2, e3, e4, e5, e6)
    case _ => sys.error(errorMsg)
  }

  def toFunEventBoolean[A](f: (A) => Boolean): Event => Boolean = {
    case Event1(e1) => f.asInstanceOf[(Any) => Boolean](e1)
    case _ => sys.error(errorMsg)
  }

  def toFunEventBoolean[A, B](f: (A, B) => Boolean): Event => Boolean = {
    case Event2(e1, e2) => f.asInstanceOf[(Any, Any) => Boolean](e1, e2)
    case _ => sys.error(errorMsg)
  }

  def toFunEventBoolean[A, B, C](f: (A, B, C) => Boolean): Event => Boolean = {
    case Event3(e1, e2, e3) => f.asInstanceOf[(Any, Any, Any) => Boolean](e1, e2, e3)
    case _ => sys.error(errorMsg)
  }

  def toFunEventBoolean[A, B, C, D](f: (A, B, C, D) => Boolean): Event => Boolean = {
    case Event4(e1, e2, e3, e4) => f.asInstanceOf[(Any, Any, Any, Any) => Boolean](e1, e2, e3, e4)
    case _ => sys.error(errorMsg)
  }

  def toFunEventBoolean[A, B, C, D, E](f: (A, B, C, D, E) => Boolean): Event => Boolean = {
    case Event5(e1, e2, e3, e4, e5) => f.asInstanceOf[(Any, Any, Any, Any, Any) => Boolean](e1, e2, e3, e4, e5)
    case _ => sys.error(errorMsg)
  }

  def toFunEventBoolean[A, B, C, D, E, F](f: (A, B, C, D, E, F) => Boolean): Event => Boolean = {
    case Event6(e1, e2, e3, e4, e5, e6) => f.asInstanceOf[(Any, Any, Any, Any, Any, Any) => Boolean](e1, e2, e3, e4, e5, e6)
    case _ => sys.error(errorMsg)
  }

  def printEvent(event: Event, log: LoggingAdapter): Unit = event match {
    case Event1(e1) => log.info(s"EVENT:\tEvent1($e1)")
    case Event2(e1, e2) => log.info(s"Event:\tEvent2($e1, $e2)")
    case Event3(e1, e2, e3) => log.info(s"Event:\tEvent3($e1, $e2, $e3)")
    case Event4(e1, e2, e3, e4) => log.info(s"Event:\tEvent4($e1, $e2, $e3, $e4)")
    case Event5(e1, e2, e3, e4, e5) => log.info(s"Event:\tEvent5($e1, $e2, $e3, $e4, $e5)")
    case Event6(e1, e2, e3, e4, e5, e6) => log.info(s"Event:\tEvent6($e1, $e2, $e3, $e4, $e5, $e6)")
  }
}