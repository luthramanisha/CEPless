package tcep.placement.manets

import akka.actor.{ActorContext, ActorRef, ActorSystem}
import akka.cluster.{Cluster, Member}
import akka.pattern.Patterns
import akka.serialization.SerializationExtension
import org.discovery.vivaldi.{Coordinates, DistVivaldiActor}
import tcep.data.Queries.{BinaryQuery, Query}
import tcep.graph.nodes.traits.Node.Dependencies
import tcep.machinenodes.helper.actors.StarksTask
import tcep.placement.{HostInfo, OperatorMetrics, PlacementStrategy}
import tcep.utils.{SizeEstimator, SpecialStats, TCEPUtils}

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * Created by mac on 10/10/2017.
  */
object StarksAlgorithm extends PlacementStrategy {

  def strategyName():String  = "MDCEP"
  override def hasInitialPlacementRoutine(): Boolean = false
  override def hasPeriodicUpdate(): Boolean = false // TODO implement
  // TODO create separate trait for Relaxation-like algorithms so that this declaraction is not necessary
  override def initialVirtualOperatorPlacement(cluster: Cluster, query: Query): Future[Map[Query, Coordinates]] =
    throw new RuntimeException("MDCEP does not have a separate initial operator placement routine")
  override def calculateVCSingleOperator(cluster: Cluster, operator: Query, startCoordinates: Coordinates, dependencyCoordinates: List[Coordinates], nnCandidates: Map[Member, Coordinates]): Future[Coordinates] =
    throw new RuntimeException("MDCEP does not have a separate initial operator placement routine")
  override def findHost(virtualCoordinates: Coordinates, candidates: Map[Member, Coordinates], operator: Query, parents: List[ActorRef]): Future[HostInfo] =
    throw new RuntimeException("MDCEP does not have a separate initial operator placement routine")
  override def selectHostFromCandidates(coordinates: Coordinates, memberToCoordinates: Map[Member, Coordinates], operator: Option[Query]): Future[Member] =
    throw new RuntimeException("MDCEP does not have a separate initial operator placement routine")
  private val k = 2 // nearest neighbours to try in case task manager for nearest neighbour cannot be found

  def findOptimalNode(context: ActorContext, cluster: Cluster, dependencies: Dependencies, askerInfo: HostInfo, operator: Query): Future[HostInfo] = {

    if(!initialized) this.initialize(cluster)

    if(askerInfo.visitedMembers.contains(cluster.selfMember)) { // prevent forwarding in circles, i.e. (rare) case when distances are equal

      val hostInfo = HostInfo(cluster.selfMember, operator, askerInfo.operatorMetrics, askerInfo.visitedMembers) // if this node is asked a second time, stop forwarding and deploy on self
      for {
        bdpupdate <- this.updateOperatorToParentBDP(cluster, operator, hostInfo.member, dependencies.parents)
      } yield {
        this.updateOperatorMsgOverhead(Some(operator), SizeEstimator.estimate(hostInfo)) //add overhead from sending back hostInfo to requester
        hostInfo.operatorMetrics = this.getPlacementMetrics(operator)
        hostInfo
      }

    } else {
      val startTime = System.currentTimeMillis()
      val candidates: Set[Member] = findPossibleNodesToDeploy(cluster)
      val candidateCoordRequest = getCoordinatesOfMembers(cluster, candidates, Some(operator))
      val parentCoordRequest = getCoordinatesOfNodes(cluster, dependencies.parents, Some(operator))
      val hostRequest = for {
        candidateCoordinates <- candidateCoordRequest
        parentCoordinates <- parentCoordRequest
        hostInfo <- {
          log.info(s"the number of available candidates are: ${candidates.size}, candidates: ${candidates.toList}")
          log.info(s"applyStarksAlgorithm() - dependencyCoords:\n ${parentCoordinates.map(d => d._1.path.address.toString + " -> " + d._2.toString)}")
          placementMetrics += operator -> askerInfo.operatorMetrics // add message overhead from callers (who forwarded to this node); if this is the first caller, these should be zero
          SpecialStats.debug(s"$this", s"applying starks on ${cluster.selfAddress} to $operator")
          applyStarksAlgorithm(context, cluster, parentCoordinates, candidateCoordinates, askerInfo, operator, askerInfo.visitedMembers)
        }
        bdp <- this.updateOperatorToParentBDP(cluster, operator, hostInfo.member, dependencies.parents)

      } yield {
         // add local overhead to accumulated overhead from callers
        hostInfo.operatorMetrics = this.getPlacementMetrics(operator)
        if(hostInfo.visitedMembers.nonEmpty) hostInfo.operatorMetrics.accMsgOverhead += SizeEstimator.estimate(hostInfo) // add overhead from sending back hostInfo to requester once this future finishes successfully
        SpecialStats.log(strategyName(), "Placement", s"after update: ${hostInfo.operatorMetrics}")
        placementMetrics.remove(operator) // placement of operator complete, clear entry
        hostInfo
      }

      hostRequest.onComplete {
        case Success(host) =>
          val latencyInMillis = System.currentTimeMillis() - startTime
          SpecialStats.debug(s"$this", s"found host ${host.member}, after $latencyInMillis ms")
          SpecialStats.log(strategyName(), "Placement", s"TotalMessageOverhead:${placementMetrics.getOrElse(operator, OperatorMetrics().accMsgOverhead)}, placement time: $latencyInMillis")
          SpecialStats.log(strategyName(), "Placement", s"PlacementTime:${System.currentTimeMillis() - startTime} millis")
        case Failure(exception) =>
          log.error(s"failed to place $operator, cause: \n $exception")
      }
      hostRequest
    }
  }

  def findOptimalNodes(context: ActorContext, cluster: Cluster, dependencies: Dependencies, askerInfo: HostInfo, operator: Query): Future[(HostInfo, HostInfo)] = {
    throw new RuntimeException("Starks algorithm does not support reliability")
  }

  /**
    * Applies Starks's algorithm to find the optimal node for operator deployment
    *
    * @param parents   parent nodes on which this operator is dependent (i.e. closer to publishers in the operator tree)
    * @param candidateNodes coordinates of the candidate nodes
    * @param askerInfo HostInfo(member, operator, operatorMetrics) from caller; operatorMetrics contains msgOverhead accumulated from forwarding
    * @param visitedMembers the members to which the operator has already been forwarded
    * @return the address of member where operator will be deployed
    */
  def applyStarksAlgorithm(context: ActorContext,
                           cluster: Cluster,
                           parents: Map[ActorRef, Coordinates],
                           candidateNodes: Map[Member, Coordinates],
                           askerInfo: HostInfo,
                           operator: Query,
                           visitedMembers: List[Member]): Future[HostInfo] = {

    def findDistanceFromParents(coordinates: Coordinates): Double = {
      parents.values.foldLeft(0d)((t, c) => t + coordinates.distance(c))
    }

    //val prodsizes = parents.keySet.toSeq.map(p => s"\n$p has size ${SizeEstimator.estimate(p)}" )
    //println(s"DEBUG parent size (bytes) : $prodsizes")
    val mycoords: Coordinates = DistVivaldiActor.localPos.coordinates
    val mydist: Double = findDistanceFromParents(mycoords)
    val neighbourDistances: Seq[((Member, Coordinates), Double)] = candidateNodes
      .map(e => e -> findDistanceFromParents(e._2)).toSeq
      .sortWith(_._2 < _._2)
    val closestNeighbourDist: Double = if(neighbourDistances.nonEmpty) neighbourDistances.head._2 else Double.MaxValue

    log.info(s"applyStarksAlgorithm(): " +
      s"\n my distance to parents: $mydist " +
      s"\n neighbours sorted by distance to parents:" +
      s" ${neighbourDistances.map(e => s"\n${e._1._1.address.toString} : ${e._2}")}")

    // algorithm description from Starks paper
    // Identify for each operator the so-called relay node, which is the next hop node towards the corresponding data source.
    //• Handle a pinned (= unary) operator: if the local node hosts the parent (or is the publisher), place it locally. Otherwise mark it
    //to be forwarded to the relay node.
    //• Handle an unpinned (= all other) operator: if all parents (= closer to stream operators in graph) of this operator have been
    // forwarded to and hosted on the same relay node, mark it to be forwarded this relay node. Otherwise place the operator locally

    val res: Future[HostInfo] = operator match {
      case s: BinaryQuery => {   // deploying any non-unary operator
        // possible cases:
        // 1. parents on same host -> forward to that host
        // 2. parents not on same host, self is not neighbour closest to both -> find closest neighbour to both (that is neither of the parents)
        // 3. parents not on same host, self is neighbour closest to both -> deploy on self
        // no parent exists for which there exists another parent that has a different address == two parents with different addresses exist
        val allParentsOnSameHost = !parents.exists(p  => parents.exists(other => other._1.path.address.toString != p._1.path.address.toString))

        val relayNodeCandidates = neighbourDistances
          .filter(n => n._2 <= mydist)
          .filter(!_._1._1.hasRole("Publisher"))  // exclude publishers from eligible nodes to avoid bad placement decisions due to latency space inaccuracies
          .sortWith(_._2 < _._2)

        val relayNodeDist = if(relayNodeCandidates.nonEmpty) relayNodeCandidates.head._2 else Double.MaxValue

        log.info(s"\n distances of all candidates to dependencies: " +
          s"\n ${relayNodeCandidates.map(e => s"\n ${e._1} : ${e._2}")}" +
          s"\n self: ${cluster.selfAddress.host} : $mydist")

        if (allParentsOnSameHost && mydist <= relayNodeDist) { // self is not included in neighbours
          log.info(s"all parents on same host, deploying operator on self: ${cluster.selfAddress.toString}")
          Future { HostInfo(cluster.selfMember, operator, askerInfo.operatorMetrics) }

        } else if (allParentsOnSameHost && relayNodeDist <= mydist) {
          log.info(s"all parents on same host, forwarding operator to neighbour: ${neighbourDistances.head._1._1.address.toString}")
          val updatedAskerInfo = HostInfo(cluster.selfMember, operator, askerInfo.operatorMetrics)
          forwardToNeighbour(parents, relayNodeCandidates.take(k).map(_._1._1), updatedAskerInfo, operator, visitedMembers)

        } else {  // dependencies not on same host

          val relayNodeCandidatesFiltered = relayNodeCandidates
            .filter(n => !parents.keys.toList.map(d => d.path.address).contains(n._1._1.address)) // exclude both parents
            .sortWith(_._2 < _._2)
          // forward to a neighbour that lies between self and dependencies that is not a publisher and not one of the parents
          if (relayNodeCandidatesFiltered.nonEmpty && relayNodeCandidatesFiltered.head._2 <= mydist) {
            log.info(s"parents not on same host, deploying operator on closest non-parent neighbour")
            val updatedAskerInfo = HostInfo(cluster.selfMember, operator, askerInfo.operatorMetrics)
            forwardToNeighbour(parents, relayNodeCandidatesFiltered.take(k).map(_._1._1), updatedAskerInfo, operator, visitedMembers)

          } else {
            log.info(s"dependencies not on same host, deploying operator on self")
            Future { HostInfo(cluster.selfMember, operator, askerInfo.operatorMetrics) }
          }
        }
      }
      case _ => { // deploying an unary or leaf operator
        // deploy on this host if it is closer to dependency than all candidates
        if (mydist <= closestNeighbourDist) {
          log.info(s"deploying stream operator on self: ${cluster.selfAddress.toString}, mydist: $mydist, neighbourDist: $closestNeighbourDist")
          Future { HostInfo(cluster.selfMember, operator, askerInfo.operatorMetrics) }

        } else {
          log.info(s"forwarding operator $operator to neighbour: ${neighbourDistances.head._1._1.address.toString}," +
            s" neighbourDist: $closestNeighbourDist, mydist: $mydist")
          val updatedAskerInfo = HostInfo(cluster.selfMember, operator, askerInfo.operatorMetrics)
          forwardToNeighbour(parents, neighbourDistances.take(k).map(_._1._1), updatedAskerInfo, operator, visitedMembers)
        }
      }
    }

    res
  }

  // if retrieval of taskmanager fails, try other candidates
  def forwardTaskManager(node: Member) = TCEPUtils.getTaskManagerOfMember(cluster, node)

  def forwardToNeighbour(producers: Map[ActorRef, Coordinates], orderedCandidates: Seq[Member], askerInfo: HostInfo, operator: Query, visitedMembers: List[Member]): Future[HostInfo] = {

    val taskSize = SizeEstimator.estimate(StarksTask(operator, Seq(), askerInfo))
    val updatedAskerInfo = askerInfo.copy()
    updatedAskerInfo.visitedMembers = cluster.selfMember :: visitedMembers
    updatedAskerInfo.operatorMetrics.accMsgOverhead += taskSize
    val starksTask = StarksTask(operator, producers.keySet.toSeq, updatedAskerInfo) // updatedAskerInfo's accMsgOverhead carries the overhead from all previous calls

    val forwardTaskManagerRequest: Future[ActorRef] = forwardTaskManager(orderedCandidates.head) recoverWith {
      case e: Throwable =>
        if (orderedCandidates.tail.nonEmpty) {
          log.warn(s"unable to contact taskManager ${orderedCandidates.head}, trying with ${orderedCandidates.tail}, cause \n $e")
          this.forwardTaskManager(orderedCandidates.tail.head)
        } else {
          log.warn(s"unable to contact taskManager ${orderedCandidates.head}, trying with ${cluster.selfMember}, cause \n $e")
          this.forwardTaskManager(cluster.selfMember)
        }
    }

    forwardTaskManagerRequest.onComplete {
      case Success(taskManager) =>
        log.info(s"${updatedAskerInfo.member.toString} asking taskManager ${taskManager} to host $operator")
        SpecialStats.debug(s"$this", s"Starks asking $taskManager to host $operator")
      case scala.util.Failure(exception) =>
        log.error(s"no candidate taskManager among ($orderedCandidates) and self could be contacted")
        SpecialStats.debug(s"$this", s"failed Starks request hosting $operator: no task manager could be contacted")
        throw exception
    }

    val deploymentResult = for {
      forwardTaskManager <- forwardTaskManagerRequest
      hostInfo <- Patterns.ask(forwardTaskManager, starksTask, resolveTimeout).asInstanceOf[Future[HostInfo]]
    } yield hostInfo

    deploymentResult.onComplete { // logging callback
      case scala.util.Success(hostInfo) =>
        SpecialStats.debug(s"$this", s"deploying $operator on ${hostInfo.member}")
        log.info(s"forwardToNeighbour - received answer, deploying operator on ${hostInfo}")
      case scala.util.Failure(exception) =>
        SpecialStats.debug(s"$this", s"could not deploy operator $operator, cause \n $exception")
        log.error(strategyName(), s"no neighbour among $orderedCandidates or self could host operator $operator \n exception: " + exception.toString)
        throw exception
    }

    deploymentResult
  }

  /*
  /**
    * Applies Starks's algorithm to find the optimal node for operator deployment
    *
    * @param producers      producers nodes on which this operator is dependent
    * @param candidateNodes coordinates of the candidate nodes
    * @return the address of member where operator will be deployed
    */
  def applyStarksAlgorithm(context: ActorContext,
                           cluster: Cluster,
                           producers: Map[ActorRef, Coordinates],
                           candidateNodes: Map[Member, Coordinates],
                           hostInfo: HostInfo,
                           operator: Query): HostInfo = {

    def sortNeighbours(lft: (Member, Coordinates), rght: (Member, Coordinates)) = {
      findDistanceFromProducers(lft._2) > findDistanceFromProducers(rght._2)
    }

    def findDistanceFromProducers(memberCord: Coordinates): Double = {
      producers.values.foldLeft(0d)((t, c) => t + memberCord.distance(c))
    }
    val mycords = DistVivaldiActor.localPos.coordinates

    val neighbours = ListMap(candidateNodes.toSeq.sortWith(sortNeighbours): _*)

    val neighborDist = findDistanceFromProducers(neighbours.head._2)
    val mydist = findDistanceFromProducers(mycords)
    if (hostInfo.member.address.equals(cluster.selfAddress) || (neighbours.nonEmpty && neighborDist < mydist)) {
      hostInfo.hops += 1
      hostInfo.distance = mycords.distance(neighbours.head)
      log.info(s"StarksDist: ${hostInfo.distance }")
      SpecialStats.debug(s"StarksDist", s"${hostInfo.distance}")
      val host = deployOnNeighbor(context, producers, RootActorPath(neighbours.head._1.address) / "user" / "TaskManager", hostInfo, operator)
      this.updateOperatorMsgOverhead(operator)
      this.updateOperatorMetrics(cluster, operator, host.member, producers.keys.toList)
      host
    } else {
      this.updateOperatorMsgOverhead(operator)
      this.updateOperatorMetrics(cluster, operator, cluster.selfMember, producers.keys.toList)
      HostInfo(cluster.selfMember, operator, this.getPlacementMetrics(operator), hostInfo.distance, hostInfo.hops)
    }
  }

  def deployOnNeighbor(context: ActorContext,
                       producers: Map[ActorRef, Coordinates],
                       path: ActorPath, hostInfo: HostInfo,
                       operator: Query): HostInfo = {
    try {
      val starksTask = StarksTask(operator, producers.keySet.toSeq, hostInfo)
      localMsgOverhead += SizeEstimator.estimate(StarksTask(operator, List.empty, null))
      val actorRef = Await.result[ActorRef](context.actorSelection(path).resolveOne(), resolveTimeout.duration)
      val deployResult = Patterns.ask(actorRef, starksTask, resolveTimeout)
      val coordinates = Await.result(deployResult, resolveTimeout.duration)
      coordinates.asInstanceOf[HostInfo]
    } catch {
      case e: Throwable => {
        SpecialStats.debug(strategyName(), s"unable to contact neighbour, hosting operator locally")
        HostInfo(cluster.selfMember, operator, distance = hostInfo.distance, hops = hostInfo.hops)
      }
    }
  }
  */

  def testSerialization(system: ActorSystem, o: AnyRef) = {
    val startTime = System.currentTimeMillis()
    // Get the Serialization Extension
    val serialization = SerializationExtension(system)
    // Have something to serialize
    val original = o
    println(s"\n ------------------------------------------------------------\n object to serialize: $o")
    // Find the Serializer for it
    val serializer = serialization.findSerializerFor(original)
    println(s"selected serializer: $serializer")
    // Turn it into bytes
    val bytes = serializer.toBinary(original)
    println(s"serialized Bytes: ${bytes.size}")
    println(s"estimated Bytes: ${SizeEstimator.estimate(o)}")
    // Turn it back into an object
    val back = serializer.fromBinary(bytes, manifest = None)
    // Voilá!
    println(s"serialization and deserialization successful after ${System.currentTimeMillis() - startTime}:\n " +back)
  }

}