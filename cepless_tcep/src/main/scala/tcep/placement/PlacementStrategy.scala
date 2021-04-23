package tcep.placement

import java.util.concurrent.{Executors, TimeUnit}

import akka.actor.{ActorContext, ActorRef, Address}
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.discovery.vivaldi.{Coordinates, DistVivaldiActor}
import org.slf4j.LoggerFactory
import tcep.data.Queries._
import tcep.graph.nodes.traits.Node.Dependencies
import tcep.machinenodes.helper.actors._
import tcep.utils.{SizeEstimator, SpecialStats, TCEPUtils}

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Created by raheel on 17/08/2017.
  * Updated by Niels on 26/11/2018
  */
trait PlacementStrategy {

  val iterations = ConfigFactory.load().getInt("constants.placement.relaxation-initial-iterations")
  implicit val resolveTimeout = Timeout(ConfigFactory.load().getInt("constants.placement.placement-request-timeout"), TimeUnit.SECONDS)
  val requestTimeout = Duration(ConfigFactory.load().getInt("constants.default-request-timeout"), TimeUnit.SECONDS)
  val log = LoggerFactory.getLogger(getClass)
  var cluster: Cluster = _
  var placementMetrics: mutable.Map[Query, OperatorMetrics] = mutable.Map()
  var publishers: Map[String, ActorRef] = Map()
  var memberBandwidths = mutable.Map.empty[(Member, Member), Double] // cache values here to prevent asking taskManager each time (-> msgOverhead)
  var memberCoordinates = Map.empty[Member, Coordinates]
  var memberLoads = mutable.Map.empty[Member, Double] // cache loads, evict entries if an operator is deployed
  private val defaultLoad = ConfigFactory.load().getDouble("constants.default-load")
  private val refreshInterval = ConfigFactory.load().getInt("constants.coordinates-refresh-interval")
  implicit val ec = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  private var bwCounter: Long = 0
  protected var initialized = false

  def strategyName(): String
  def hasInitialPlacementRoutine(): Boolean
  def hasPeriodicUpdate(): Boolean

  /**
    * Find optimal node for the operator to place
    *
    * @return HostInfo, containing the address of the host node
    */
  def findOptimalNode(context: ActorContext, cluster: Cluster, dependencies: Dependencies, askerInfo: HostInfo, operator: Query): Future[HostInfo]

  /**
    * Find two optimal nodes for the operator placement, one node for hosting the operator and one backup node for high reliability
    *
    * @return HostInfo, containing the address of the host nodes
    */
  def findOptimalNodes(context: ActorContext, cluster: Cluster, dependencies: Dependencies, askerInfo: HostInfo, operator: Query): Future[(HostInfo, HostInfo)]


  /**
    * starting point of the initial placement
    * retrieves the coordinates of dependencies and calculates an initial placement of all operators in a query
    * (without actually placing them yet)
    * @param cluster
    * @param query the operator graph to be placed
    * @return a map from operator to host
    */
  def initialVirtualOperatorPlacement(cluster: Cluster, query: Query): Future[Map[Query, Coordinates]] = {

    val clientNode = cluster.state.members.filter(m => m.roles.contains("Subscriber"))
    assert(clientNode.size == 1, "there must be exactly one client node with role Subscriber")
    // note that the message overhead is attributed only to the root operator since it is retrieved only once!
    val publisherCoordRequest = getCoordinatesOfNodes(cluster, publishers.values.toSeq, Some(query))
    val candidateCoordRequest = getCoordinatesOfMembers(cluster, findPossibleNodesToDeploy(cluster), Some(query))
    val virtualOperatorPlacementRequest = for {
      publisherCoordinates <- publisherCoordRequest
      candidateCoordinates <- candidateCoordRequest
      clientCoordinates <- getCoordinatesOfNode(cluster, clientNode.head, Some(query))
    } yield {
      calculateVirtualPlacementWithCoords(cluster, query, clientCoordinates, publisherCoordinates.map(e => publishers.find(_._2 == e._1).get._1 -> e._2), candidateCoordinates)
    }

    virtualOperatorPlacementRequest.onComplete {
      case Success(_) => SpecialStats.debug(s"$this", s"calculated virtual coordinates for query")
      case Failure (exception) => SpecialStats.debug(s"$this", s"calculation of virtual coordinates failed, cause: \n $exception")
    }
    virtualOperatorPlacementRequest
  }

  def calculateVCSingleOperator(cluster: Cluster, operator: Query, startCoordinates: Coordinates, dependencyCoordinates: List[Coordinates], nnCandidates: Map[Member, Coordinates]): Future[Coordinates]
  /**
    * performs an initial placement of all operators in a query by performing successive (virtual) placement updates for
    * each operator in the virtual coordinate space
    * @param query the query consisting of the operators to be placed
    */
  def calculateVirtualPlacementWithCoords(cluster: Cluster, query: Query,
                                          clientCoordinates: Coordinates,
                                          publisherCoordinates: Map[String, Coordinates],
                                          nnCandidateCoordinates: Map[Member, Coordinates]): Map[Query, Coordinates] = {

    // use LinkedHashMap here to guarantee that parent operators are updated before children
    val operatorDependencyMap = extractOperators(query, None, mutable.LinkedHashMap[Query, QueryDependencies]())
    val operators = operatorDependencyMap.toList.map(_._1).reverse // reverse so that parents are updated before their children
    var currentOperatorCoordinates: Map[Query, Coordinates] = operators.map(o => o -> Coordinates.origin).toMap // start at origin

    for(i <- 0 to iterations) {
      // updates take influence only in next round
      val updateRound = makeMapFuture(
        operators.map(operator => { // run each operator's current round update in parallel
          val dependencyCoordinates = findDependencyCoordinates(operator, operatorDependencyMap.toMap, currentOperatorCoordinates, publisherCoordinates, clientCoordinates)
          operator -> calculateVCSingleOperator(cluster, operator, currentOperatorCoordinates(operator), dependencyCoordinates, nnCandidateCoordinates)
        }).toMap)
      updateRound.onComplete {
        case Success(value) =>
        case Failure(exception) => SpecialStats.debug(s"$this", s"failed in initial iteration $i, \n cause: ${exception} \n ${exception.getStackTrace.map("\n" + _)}")
      }

      val updatedCoordinates = Await.result(updateRound, resolveTimeout.duration) // block here to wait for all operators to finish their update round
      currentOperatorCoordinates = updatedCoordinates
      SpecialStats.debug(s"$this", s"completed initial iteration $i")
    }
    currentOperatorCoordinates
  }

  /**
    * called once the virtual coordinates have been determined to place an operator on a host
    * only used by algorithms that have a separate initial placement logic
    * @param virtualCoordinates
    * @param candidates
    * @param operator
    * @param parents
    * @return hostInfo including the hosting Member, its bdp to the parent nodes, and the msgOverhead of the placement
    */
  def findHost(virtualCoordinates: Coordinates, candidates: Map[Member, Coordinates], operator: Query, parents: List[ActorRef]): Future[HostInfo] = {
    if(!hasInitialPlacementRoutine()) throw new IllegalAccessException("only algorithms with a non-sequential initial placement should call findHost() !")

    for {
      host <- selectHostFromCandidates(virtualCoordinates, candidates, Some(operator))
      bdpUpdate <- this.updateOperatorToParentBDP(cluster, operator, host, parents)
    } yield {
      val hostInfo = HostInfo(host, operator, this.getPlacementMetrics(operator))
      memberLoads.remove(host) // get new value with deployed operator on next request
      placementMetrics.remove(operator) // placement of operator complete, clear entry
      SpecialStats.debug(strategyName(), s"found host: $host")
      hostInfo
    }
  }

  def selectHostFromCandidates(coordinates: Coordinates, memberToCoordinates: Map[Member, Coordinates], operator: Option[Query] = None): Future[Member]

  def initialize(cluster: Cluster, test: Boolean = false, publisherNameMap: Option[Map[String, ActorRef]] = None): Unit = {
    if(initialized) return
    this.cluster = cluster
    this.placementMetrics.clear()
    this.publishers = TCEPUtils.getPublisherActors(cluster)
    SpecialStats.debug(s"$this", s"found publisher hosts $publishers")
    if(!test) cluster.system.scheduler.schedule(new FiniteDuration(0, TimeUnit.SECONDS), new FiniteDuration(refreshInterval, TimeUnit.SECONDS))(refreshTask)
    initialized = true
    log.info(s"initialization complete on ${cluster.selfAddress} \n publishers: ${publishers}")
    SpecialStats.debug(s"$this", s"initialized ${getClass.getName}")
  }

  def refreshTask: Unit = {
    bwCounter += 1
    if(bwCounter % 6 == 0) {
      memberBandwidths.clear()
      memberLoads.clear()
    } // do not clear bandwidth and load cache every 10s, but every minute
    updateCoordinateMap()
  }

  def updateCoordinateMap(): Future[Map[Member, Coordinates]] = {
    makeMapFuture(cluster.state.members.filter(m=> m.status == MemberStatus.up && !m.hasRole("VivaldiRef"))
      .map(m => {
        val request = TCEPUtils.getCoordinatesOfNode(cluster, m.address).map { result => memberCoordinates = memberCoordinates.updated(m, result); result }
        //request.onComplete {
        //  case Success(value) => log.info( s"retrieved coordinates $value for $m")
        //  case Failure(exception) => log.info( s"failed to retrieve coords for $m \n $exception")
        m -> request
      }).toMap)
    //log.info(s"member coordinates: \n ${memberCoordinates.map(m => s"\n ${m._1.address} | ${m._2} " )} \n")
  }

  protected def updateOperatorToParentBDP(cluster: Cluster, operator: Query, host: Member, parents: List[ActorRef]): Future[Map[String, Double]] = {


    val bdpToParents: Future[Map[String, Double]] = for {
      opToParentBDP <- makeMapFuture(parents.map(p => {
                            p.path.name -> TCEPUtils.getBDPBetweenNodes(cluster, host, TCEPUtils.getMemberFromActorRef(cluster, p)) // use TCEPUtils directly since message overhead from this is not placement-related
                            }).toMap)
    } yield {
      val operatorMetrics = placementMetrics.getOrElse(operator, OperatorMetrics())
      operatorMetrics.operatorToParentBDP = opToParentBDP
      placementMetrics += operator -> operatorMetrics
      opToParentBDP
    }

    bdpToParents.onComplete {
      case Success(value) =>
        log.info(s"operator bdp between (${value}) and host $host of ${operator}")
      case Failure(exception) => log.error(s"failed to update bdp between host $host and parents ${parents} of ${operator} \n cause $exception")
    }
    bdpToParents
  }

  /**
    * called by any method that incurs communication with other nodes in the process of placing an operator
    * updates an operator's placement msgOverhead (amount of bytes sent as messages to other nodes)
    * accMsgOverhead: accumulated overhead from all directly placement-related communication that has been made for placing this operator
    * @param operator
    */
  protected def updateOperatorMsgOverhead(operator: Option[Query], msgOverhead: Long): Unit = {
    if(operator.isDefined) {
      val operatorMetrics = placementMetrics.getOrElse(operator.get, OperatorMetrics())
      operatorMetrics.accMsgOverhead += msgOverhead
      placementMetrics.update(operator.get, operatorMetrics)
    }
  }

  protected def getPlacementMetrics(operator: Query): OperatorMetrics = placementMetrics.getOrElse(operator, {
    log.warn(s"could not find placement metrics for operator $operator, returning zero values!")
    OperatorMetrics()
  })

  /**
    * find the n nodes closest to this one
    *
    * @param n       n closest
    * @param candidates map of all neighbour nodes to consider and their vivaldi coordinates
    * @return the nodes closest to this one
    */
  def getNClosestNeighboursByMember(n: Int, candidates: Map[Member, Coordinates] = memberCoordinates): Seq[(Member, Coordinates)] =
    getNClosestNeighboursToCoordinatesByMember(n, DistVivaldiActor.localPos.coordinates, candidates)

  def getClosestNeighbourToCoordinates(coordinates: Coordinates, candidates: Map[Member, Coordinates] = memberCoordinates): (Member, Coordinates) =
    getNClosestNeighboursToCoordinatesByMember(1, coordinates, candidates).head

  def getNClosestNeighboursToCoordinatesByMember(n: Int, coordinates: Coordinates, candidates: Map[Member, Coordinates] = memberCoordinates): Seq[(Member, Coordinates)] =
    getNeighboursSortedByDistanceToCoordinates(candidates, coordinates).take(n)

  def getNeighboursSortedByDistanceToCoordinates(neighbours: Map[Member, Coordinates], coordinates: Coordinates): Seq[(Member, Coordinates)] =
    if(neighbours.nonEmpty) {
      val sorted = neighbours.map(m => m -> m._2.distance(coordinates)).toSeq.sortWith(_._2 < _._2)
      val keys = sorted.map(_._1)
      keys

    } else {
      log.debug("getNeighboursSortedByDistanceToCoordinates() - received empty coordinate map, returning self")
      Seq(cluster.selfMember -> DistVivaldiActor.localPos.coordinates)
    }

  protected def getBandwidthBetweenCoordinates(c1: Coordinates, c2: Coordinates, nnCandidates: Map[Member, Coordinates], operator: Option[Query] = None): Future[Double] = {
    val source: Member = getClosestNeighbourToCoordinates(c1, nnCandidates)._1
    val target: Member = getClosestNeighbourToCoordinates(c2, nnCandidates.filter(!_._1.equals(source)))._1
    //SpecialStats.debug(s"$this", s"retrieving bandwidth between $source and $target")
    val request = getBandwidthBetweenMembers(source, target, operator)
    request
  }

  /**
    * get the bandwidth between source and target Member; use cached values if available to avoid measurement overhead from iperf
    * @param source
    * @param target
    * @return
    */
  protected def getBandwidthBetweenMembers(source: Member, target: Member, operator: Option[Query]): Future[Double] = {
    if(source.equals(target)) Future { 0.0d }
    else if(memberBandwidths.contains((source, target))) {
      Future { memberBandwidths((source, target)) }
    } else if(memberBandwidths.contains((target, source))) {
      Future { memberBandwidths((target, source)) }
    } else {
      val communicationOverhead = SizeEstimator.estimate(SingleBandwidthRequest(cluster.selfMember)) + SizeEstimator.estimate(SingleBandwidthResponse(0.0d))
      this.updateOperatorMsgOverhead(operator, communicationOverhead)
      val request = TCEPUtils.getBandwidthBetweenNodes(cluster, source, target)
      request.onComplete {
        case Success(value) =>
          memberBandwidths += (source, target) -> value
          memberBandwidths += (target, source) -> value
          SpecialStats.debug(s"$this", s"retrieved bandwidth $value between $source and $target, caching it locally: $memberBandwidths")
        case Failure(exception) => SpecialStats.debug(s"$this", s"failed to retrieve bandwidth between $source and $target, cause: \n $exception")
      }
      request
    }
  }

  protected def getAllBandwidthsFromNode(cluster: Cluster, node: Member, operator: Option[Query]): Future[Map[Member, Double]] = {

      for {
        bandwidths <- TCEPUtils.getAllBandwidthsFromNode(cluster, node).mapTo[Map[Member, Double]]
      } yield {
        val communicationOverhead = SizeEstimator.estimate(AllBandwidthsRequest) + SizeEstimator.estimate(AllBandwidthsResponse)
        this.updateOperatorMsgOverhead(operator, communicationOverhead)
        bandwidths.foreach(e => memberBandwidths += (node, e._1) -> e._2)
        bandwidths
      }
  }

  def getMemberByAddress(address: Address): Option[Member] = cluster.state.members.find(m => m.address.equals(address))

  /**
    * retrieve all nodes that can host operators, including !publishers!
    *
    * @param cluster cluster reference to retrieve nodes from
    * @return all cluster members (nodes) that are candidates (EmptyApp) or publishers (PublisherApp)
    */
  def findPossibleNodesToDeploy(cluster: Cluster): Set[Member] = {
    cluster.state.members.filter(x => x.status == MemberStatus.Up
      && x.hasRole("Candidate")
      && !x.address.equals(cluster.selfAddress)
    )
  }

  /**
    * retrieves the vivaldi coordinates of a node from its actorRef (or Member)
    * attempts to contact the node 3 times before returning default coordinates (origin)
    * records localMsgOverhead from communication for placement metrics
    * @param node the actorRef of the node
    * @param operator the operator with which the incurred communication overhead will be associated
    * @return its vivaldi coordinates
    */
  def getCoordinatesOfNode(cluster: Cluster, node: ActorRef,  operator: Option[Query]): Future[Coordinates] = this.getCoordinatesFromAddress(cluster, node.path.address, operator)
  def getCoordinatesOfNode(cluster: Cluster, node: Member, operator: Option[Query]): Future[Coordinates] = this.getCoordinatesFromAddress(cluster, node.address, operator)
  // blocking, !only use in tests!
  def getCoordinatesOfNodeBlocking(cluster: Cluster, node: Member, operator: Option[Query] = None): Coordinates = Await.result(this.getCoordinatesFromAddress(cluster, node.address, operator), requestTimeout)

  protected def getCoordinatesFromAddress(cluster: Cluster, address: Address, operator: Option[Query] = None, attempt: Int = 0): Future[Coordinates] = {

    val maxTries = 3
    val member = getMemberByAddress(address)
    if (member.isDefined && memberCoordinates.contains(member.get)) Future { memberCoordinates(member.get) }
    else {
      this.updateOperatorMsgOverhead(operator, SizeEstimator.estimate(CoordinatesRequest(address)))
      val request: Future[Coordinates] = TCEPUtils.getCoordinatesOfNode(cluster, address)
      request.recoverWith { // retries up to maxTries times if futures does not complete
        case e: Throwable =>
          if (attempt < maxTries) {
            log.warn(s"failed $attempt times to retrieve coordinates of ${member.get}, retrying... \n cause: ${e.toString}")
            this.getCoordinatesFromAddress(cluster, address, operator, attempt + 1)
          } else {
            log.warn(s"failed $attempt times to retrieve coordinates of ${member.get}, returning origin coordinates \n cause: ${e.toString}")
            Future { Coordinates.origin }
          }
      } map { result => {
        this.updateOperatorMsgOverhead(operator, SizeEstimator.estimate(CoordinatesResponse(result)))
        result
      }}
    }
  }

  def getCoordinatesOfMembers(cluster: Cluster, nodes: Set[Member], operator: Option[Query] = None): Future[Map[Member, Coordinates]] = {
    makeMapFuture(nodes.map(node => {
      node -> this.getCoordinatesOfNode(cluster, node, operator)
    }).toMap)
  }
  // callers  have to handle futures
  protected def getCoordinatesOfNodes(cluster: Cluster, nodes: Seq[ActorRef], operator: Option[Query] = None): Future[Map[ActorRef, Coordinates]] =
    makeMapFuture(nodes.map(node => node -> this.getCoordinatesOfNode(cluster, node, operator)).toMap)

  protected def findMachineLoad(cluster: Cluster, nodes: Seq[Member], operator: Option[Query] = None): Future[Map[Member, Double]] = {
    makeMapFuture(nodes.map(n => n -> this.getLoadOfNode(cluster, n, operator).recover {
      case e: Throwable =>
        log.info(s"failed to get load of $n using default load $defaultLoad, cause \n $e")
        defaultLoad
    }).toMap)
  }

  /**
    * retrieves current system load of the node; caches the value if not existing
    * (cache entries are cleared periodically (refreshTask) or if an operator is deployed on that node
    * records localMsgOverhead from communication for placement metrics
    * @param cluster
    * @param node
    * @return
    */
  def getLoadOfNode(cluster: Cluster, node: Member, operator: Option[Query] = None): Future[Double] = {

    if(memberLoads.contains(node)) Future { memberLoads(node) }
    else {
      val request: Future[Double] = TCEPUtils.getLoadOfMember(cluster, node)
      this.updateOperatorMsgOverhead(operator, SizeEstimator.estimate(LoadRequest()))
      request.onComplete {
        case Success(load: Double) =>
          memberLoads += node -> load
        case Failure(exception) =>
      }
      request
    }
  }

  protected def getBDPBetweenCoordinates(cluster: Cluster, sourceCoords: Coordinates, targetCoords: Coordinates, candidates: Map[Member, Coordinates] ): Future[Double] = {

    val latency = sourceCoords.distance(targetCoords)
    if (latency == 0.0d) return Future { 0.0d }
    for {
      bw <- this.getBandwidthBetweenCoordinates(sourceCoords, targetCoords, candidates)
    } yield  {
      if(latency <= 0.0d || bw <= 0.0d) log.warn(s"BDP between non-equal nodes $sourceCoords and $targetCoords is zero!")
      latency * bw
    }
  }

  /**
    * recursively extracts the operators and their dependent child and parent operators
    * @param query current operator
    * @param child parent operator
    * @param acc accumulated operators and dependencies
    * @return the sorted map of all operators and their dependencies, with the root operator as the first map entry
    */
  def extractOperators(query: Query, child: Option[Query], acc: mutable.LinkedHashMap[Query, QueryDependencies]): mutable.LinkedHashMap[Query, QueryDependencies] = {

    query match {
      case b: BinaryQuery => {
        val left = extractOperators(b.sq1, Some(b), mutable.LinkedHashMap(b -> QueryDependencies(Some(List(b.sq1, b.sq2)), child)))
        val right = extractOperators(b.sq2, Some(b), mutable.LinkedHashMap(b -> QueryDependencies(Some(List(b.sq1, b.sq2)), child)))
        left.foreach(l => acc += l)
        right.foreach(r => acc += r)
        acc
      }
      case u: UnaryQuery => extractOperators(u.sq, Some(u), acc += u -> QueryDependencies(Some(List(u.sq)), child))
      case l: LeafQuery => acc += l -> QueryDependencies(None, child) // stream nodes have no parent operators, they depend on publishers
      case _ =>
        throw new RuntimeException(s"unknown query type $query")
    }
  }

  /**
    * returns a list of coordinates on which the operator is dependent, i.e. the coordinates of its parents and children
    * for stream operators the publisher acts as a parent, for the root operator the client node acts as the child
    * @param operator
    * @param operatorDependencyMap
    * @param currentOperatorCoordinates
    * @param publisherCoordinates
    * @param clientCoordinates
    * @return
    */
  def findDependencyCoordinates(operator: Query, operatorDependencyMap: Map[Query, QueryDependencies], currentOperatorCoordinates: Map[Query, Coordinates],
                                publisherCoordinates: Map[String, Coordinates], clientCoordinates: Coordinates): List[Coordinates] = {

    var dependencyCoordinates = List[Coordinates]()
    operator match {
      case s: StreamQuery =>
        val publisherKey = publisherCoordinates.keys.find(_.contains(s.publisherName.substring(2))) // name without "P:"
        if(publisherKey.isDefined)
          dependencyCoordinates = publisherCoordinates(publisherKey.get) :: dependencyCoordinates // stream operator, set publisher coordinates
        else throw new RuntimeException(s"could not find publisher ${s.publisherName} of stream operator among publisher coordinate map \n ${publisherCoordinates.mkString("\n")}")
        val child = operatorDependencyMap(operator).child
          if(child.isDefined) dependencyCoordinates =  currentOperatorCoordinates(child.get) :: dependencyCoordinates
          else {
            //throw new IllegalArgumentException(s"stream operator without child found! $operator"))) :: dependencyCoordinates
            log.warn("found stream operator with no child: this is only okay if the query has only this one operator")
            dependencyCoordinates = clientCoordinates :: dependencyCoordinates
          }

      case _ => // non-stream query
        val dependencies = operatorDependencyMap(operator)
        if(dependencies.child.isEmpty) { // root operator
          dependencyCoordinates = clientCoordinates :: dependencyCoordinates
        } else {
          dependencyCoordinates = currentOperatorCoordinates(dependencies.child.get) :: dependencyCoordinates
        }
          dependencyCoordinates = dependencyCoordinates ++ dependencies.parents
            .getOrElse(throw new IllegalArgumentException(s"non-stream operator without parents found! $operator"))
            .map(parent => currentOperatorCoordinates(parent))

    }
    dependencyCoordinates
  }

  /**
    * @param futureValueMap map with future values
    * @return a future that completes once all the value futures have completed
    */
  def makeMapFuture[K,V](futureValueMap: Map[K, Future[V]]): Future[Map[K,V]] =
    Future.traverse(futureValueMap) { case (k, fv) => fv.map(k -> _) } map(_.toMap)

}

case class HostInfo(member: Member, operator: Query, var operatorMetrics: OperatorMetrics = OperatorMetrics(), var visitedMembers: List[Member] = List(), var graphTotalBDP: Double = 0)
case class OperatorMetrics(var operatorToParentBDP: Map[String, Double] = Map(), var accMsgOverhead: Long = 0)
case class QueryDependencies(parents: Option[List[Query]], child: Option[Query])