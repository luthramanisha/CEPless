package tcep.machinenodes.helper.actors

import java.util.concurrent.Executors

import akka.actor.{ActorLogging, ActorRef, Address, Props}
import akka.cluster.Member
import akka.pattern.pipe
import org.discovery.vivaldi.{Coordinates, VivaldiPosition}
import tcep.data.Queries.Query
import tcep.graph.nodes.traits.Node.Dependencies
import tcep.placement.manets.StarksAlgorithm
import tcep.placement.vivaldi.VivaldiCoordinates
import tcep.placement.{BandwidthEstimator, HostInfo}
import tcep.simulation.adaptive.cep.SystemLoad
import tcep.utils.{SpecialStats, TCEPUtils}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

/**
  * Created by raheel
  * on 09/08/2017.
  */
sealed trait Message extends Serializable // priority is configured in TCEPPrioMailbox
trait TransitionControlMessage extends Message //Only for transition related control messages, highest priority in TCEPPriorityMailbox
trait PlacementMessage extends TransitionControlMessage
trait MeasurementMessage extends Message // messages for measurements needed for placements
trait VivaldiCoordinatesMessage extends Message // regular vivaldi coordinate updates

case class StarksTask(operator: Query, producers: Seq[ActorRef], askerInfo: HostInfo) extends PlacementMessage

case class LoadRequest() extends MeasurementMessage
case class LoadResponse(load: Double) extends MeasurementMessage
case class SingleBandwidthRequest(target: Member) extends MeasurementMessage
case class SingleBandwidthResponse(bandwidth: Double) extends MeasurementMessage
case class AllBandwidthsRequest() extends MeasurementMessage
case class AllBandwidthsResponse(bandwidthMap: Map[Member, Double]) extends MeasurementMessage
case class BDPRequest(target: Member) extends MeasurementMessage
case class BDPResponse(bdp: Double) extends MeasurementMessage
case class InitialBandwidthMeasurementStart() extends PlacementMessage
case class BandwidthMeasurementSlotRequest() extends PlacementMessage
case class BandwidthMeasurementSlotGranted() extends PlacementMessage
case class BandwidthMeasurementExists(bandwidth: Double) extends PlacementMessage
case class BandwidthMeasurementComplete(source: Address, target: Address, bandwidth: Double) extends PlacementMessage
case class CoordinatesRequest(node: Address) extends MeasurementMessage // address field is only intended for asking local vivaldi actor about a remote addresses coordinates
case class CoordinatesResponse(coordinate: Coordinates) extends MeasurementMessage

case class StartVivaldiUpdates() extends PlacementMessage
case class VivaldiCoordinatesEstablished() extends PlacementMessage
case class VivaldiPing(sendTime: Long) extends VivaldiCoordinatesMessage
case class VivaldiPong(sendTime: Long, receiverPosition: VivaldiPosition) extends VivaldiCoordinatesMessage

/**
  * responsible for handling placement-related activities
  */
class TaskManagerActor extends VivaldiCoordinates with ActorLogging {

  override implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
  private var bandwidthEstimator: ActorRef = _

  override def preStart(): Unit = {
    super.preStart()
    bandwidthEstimator = cluster.system.actorOf(Props(new BandwidthEstimator()), "BandwidthEstimator")
    log.info(s"started TaskManager with bandwidthEstimator $bandwidthEstimator")
    SpecialStats.debug(s"$this", s"starting taskManager on ${cluster.selfMember} as ${this.self}")
  }

  override def receive: Receive = super.receive orElse {

    case st: StarksTask => {
      val s = sender()
      SpecialStats.debug(s"$this", s"$s sent starks task $s}")
      val starks = StarksAlgorithm
      starks.initialize(cluster)
      val hostRequest: Future[HostInfo] = starks.findOptimalNode(this.context, cluster, Dependencies(st.producers.toList, List()), st.askerInfo, st.operator)
      hostRequest pipeTo s
    }

    case LoadRequest() => sender() ! SystemLoad.getSystemLoad

    case BDPRequest(target: Member) =>
      val s = sender()
      TCEPUtils.getBDPBetweenNodes(cluster, cluster.selfMember, target) pipeTo s // this is a future

    case request: SingleBandwidthRequest => bandwidthEstimator.forward(request)
    case r: AllBandwidthsRequest => bandwidthEstimator.forward(r)
    case i: InitialBandwidthMeasurementStart => bandwidthEstimator.forward(i)
    case r: BandwidthMeasurementSlotRequest => bandwidthEstimator.forward(r)
    case c: BandwidthMeasurementComplete => bandwidthEstimator.forward(c)

    case other => log.info(s"ignoring unknown message $other")
  }
}