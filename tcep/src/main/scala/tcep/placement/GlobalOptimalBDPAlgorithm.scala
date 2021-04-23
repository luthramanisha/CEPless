package tcep.placement
import akka.actor.ActorContext
import akka.cluster.{Cluster, Member, MemberStatus}
import org.discovery.vivaldi.Coordinates
import tcep.data.Queries
import tcep.data.Queries.Query
import tcep.graph.nodes.traits.Node
import tcep.utils.TCEPUtils

import scala.concurrent.Future
import scala.concurrent.duration._

object GlobalOptimalBDPAlgorithm extends PlacementStrategy {

  override def strategyName(): String = "GlobalOptimalBDPAlgorithm"
  private var singleNodePlacement: Option[Member] = None

  override def hasInitialPlacementRoutine(): Boolean = false

  override def hasPeriodicUpdate(): Boolean = false

  /**
    * Find optimal node for the operator to place
    *
    * @return HostInfo, containing the address of the host node
    */
  override def findOptimalNode(context: ActorContext, cluster: Cluster, dependencies: Node.Dependencies, askerInfo: HostInfo, operator: Query): Future[HostInfo] = {
    applyGlobalOptimalBDPAlgorithm(cluster, operator, dependencies)
  }

  /**
    * Find two optimal nodes for the operator placement, one node for hosting the operator and one backup node for high reliability
    *
    * @return HostInfo, containing the address of the host nodes
    */
  override def findOptimalNodes(context: ActorContext, cluster: Cluster, dependencies: Node.Dependencies, askerInfo: HostInfo, operator: Query): Future[(HostInfo, HostInfo)] = {
    for {
      mainNode <- applyGlobalOptimalBDPAlgorithm(cluster, operator, dependencies)
    } yield {
      val backup = HostInfo(cluster.selfMember, operator, OperatorMetrics())
      (mainNode, backup)
    }
  }

  /**
    * finds the node with minimum Bandwidth-Delay-Product sum to all publishers and the consumer, using global knowledge about
    * the latency space and bandwidths between nodes.
    * successive calls for different operators (supposedly from the same graph) yield the same host
    * publishers and subscriber can host operators only if they also have the "Candidate" role
    * @param cluster
    * @param operator operator to be placed
    * @return HostInfo containing the host address and message overhead for calculating the placement
    */
  def applyGlobalOptimalBDPAlgorithm(cluster: Cluster, operator: Query, dependencies: Node.Dependencies): Future[HostInfo] = {

    this.initialize(cluster)
    cluster.system.scheduler.scheduleOnce(5 seconds)(() => this.singleNodePlacement = None) // reset
    if(this.singleNodePlacement.isDefined) {
      log.info(s"a previous operator has already been placed optimally on ${this.singleNodePlacement}, placing ${operator.getClass.getSimpleName} there")
      for {
        bdpUpdate <- this.updateOperatorToParentBDP(cluster, operator, singleNodePlacement.get, dependencies.parents)
      } yield HostInfo(this.singleNodePlacement.get, operator, this.getPlacementMetrics(operator))
    } else {
      // includes Publishers, Subscriber, and Candidates that are up
      val allNodes = cluster.state.members.filter(m => m.status == MemberStatus.Up && !m.hasRole("VivaldiRef"))
      // get publishers and subscribers
      val publishers = allNodes.filter(_.hasRole("Publisher")).toList
      val subscriber = allNodes.find(_.hasRole("Subscriber"))

      if (publishers.isEmpty) throw new RuntimeException(s"cannot find publisher nodes in cluster! \n ${cluster.state.members.mkString("\n")}")
      if (subscriber.isEmpty) throw new RuntimeException(s"cannot find subscriber node in cluster! \n ${cluster.state.members.mkString("\n")}")
      else {
        val coordRequest: Future[Map[Member, Coordinates]] = getCoordinatesOfMembers(cluster, allNodes, Some(operator))
        val bandwidthMeasurementsRequests: Future[Map[Member, Map[Member, Double]]] = TCEPUtils.makeMapFuture(allNodes.map(c => c -> getAllBandwidthsFromNode(cluster, c, Some(operator))).toMap)
        val hostRequest = for {
          candidateCoordinates <- coordRequest
          bandwidthMeasurementsM <- bandwidthMeasurementsRequests
        } yield {
          // transform bandwidth and latency measurements into maps with keys on member pairs
          val bandwidthMeasurements: Map[(Member, Member), Double] = bandwidthMeasurementsM.flatMap(s => s._2.map(t => (s._1, t._1) -> t._2))
          val allLatencies: Map[(Member, Member), Double] = candidateCoordinates.flatMap(s =>
            candidateCoordinates.map(t => (s._1, t._1) -> s._2.distance(t._2)))
          // Bandwidth-Delay-Product (BDP) between all pairs (bandwidth map does not contain keys for (m, m) -> return 0 for equal nodes)
          val linkBDPs: Map[(Member, Member), Double] = allLatencies.map(l => l._1 -> l._2 * bandwidthMeasurements.getOrElse(l._1, if(l._1._1.equals(l._1._2)) 0.0 else Double.MaxValue))
          // calculate for each candidate the BDP between it and all publishers and the subscriber
          def getBDPEntry(a: Member, b: Member): Double = linkBDPs.getOrElse((a, b), linkBDPs.getOrElse((b, a), Double.MaxValue))
          val placementBDPs = candidateCoordinates.keySet.map(c => c -> {
            val bdpsToPublishers = publishers.map(p => getBDPEntry(c, p))
            val bdpToSubscriber = getBDPEntry(c, subscriber.get)
            bdpsToPublishers.sum + bdpToSubscriber
          })

          // only use candidates (if publishers and subscriber are included depends on whether they additionally have the "Candidate" role)
          val minBDPCandidate = placementBDPs.filter(_._1.hasRole("Candidate")).minBy(_._2)
          for {
            bdpUpdate <- this.updateOperatorToParentBDP(cluster, operator, minBDPCandidate._1, dependencies.parents)
          } yield {
            val hostInfo = HostInfo(minBDPCandidate._1, operator, this.getPlacementMetrics(operator))
            placementMetrics.remove(operator) // placement of operator complete, clear entry
            /*
            SpecialStats.debug("GlobalOptimalBDPAlgorithm", s"received coordinates and bandwidth measurements from all nodes")
            SpecialStats.debug("GlobalOptimalBDPAlgorithm", s"transformed bw measurements (size: ${bandwidthMeasurements.size})")
            SpecialStats.debug("GlobalOptimalBDPAlgorithm", s"calculated all pair latencies (size: ${allLatencies.size}")
            SpecialStats.debug("GlobalOptimalBDPAlgorithm", s"calculated all pair bdps (size: ${linkBDPs.size})")
            SpecialStats.debug("GlobalOptimalBDPAlgorithm", s"calculated all candidate bdps (size: ${placementBDPs.size}) " +
              s"\n ${placementBDPs.toList.sortBy(_._2).map(e => s";;${e._1.address} -> ${e._2}").mkString("\n")}")
            SpecialStats.debug(strategyName(), s"found host with min BDP (${minBDPCandidate} $hostInfo")
            */
            log.info(s"found host with minimum BDP to publishers and subscriber: ${minBDPCandidate}")
            this.singleNodePlacement = Some(minBDPCandidate._1)
            hostInfo
          }

        }
        hostRequest.flatten
      }
    }
  }

  override def calculateVCSingleOperator(cluster: Cluster, operator: Queries.Query, startCoordinates: Coordinates, dependencyCoordinates: List[Coordinates], nnCandidates: Map[Member, Coordinates]): Future[Coordinates] = ???

  override def selectHostFromCandidates(coordinates: Coordinates, memberToCoordinates: Map[Member, Coordinates], operator: Option[Queries.Query]): Future[Member] = ???
}
