package tcep.simulation.tcep

import java.io.File
import java.util.concurrent.TimeUnit

import akka.actor.{ActorLogging, ActorRef, Address, CoordinatedShutdown, RootActorPath}
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.{Member, MemberStatus}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import tcep.data.Queries._
import tcep.data.Structures.MachineLoad
import tcep.dsl.Dsl._
import tcep.graph.QueryGraph
import tcep.graph.nodes.traits.Mode.Mode
import tcep.machinenodes.helper.actors.{BandwidthMeasurementComplete, InitialBandwidthMeasurementStart, StartVivaldiUpdates, VivaldiCoordinatesEstablished}
import tcep.placement.benchmarking.PlacementAlgorithm
import tcep.placement.manets.StarksAlgorithm
import tcep.placement.sbon.PietzuchAlgorithm
import tcep.placement.vivaldi.VivaldiCoordinates
import tcep.placement.{GlobalOptimalBDPAlgorithm, PlacementStrategy}
import tcep.utils.{SpecialStats, TCEPUtils}

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

class SimulationSetup(directory: Option[File], mode: Int, durationInMinutes: Option[Int] = None,
                      startingPlacementAlgorithm: Option[String] = Some("Relaxation"),
                      publisherNames: Option[Vector[String]] = None) extends VivaldiCoordinates with ActorLogging {

  var publishers: Map[String, ActorRef] = Map.empty[String, ActorRef]
  var simulationStarted = false
  val minimumNodes = ConfigFactory.load().getInt("constants.minimum-number-of-nodes")
  val minimumBandwidthMeasurements = minimumNodes * (minimumNodes - 1)   // n * (n-1) measurements between n nodes
  var measuredLinks: mutable.Map[(Address, Address), Double] = mutable.Map[(Address, Address), Double]()
  var bandwidthMeasurementsCompleted = 0
  var coordinatesEstablishedCount = 0
  var coordinatesEstablished = false

  val defaultDuration: Long = ConfigFactory.load().getLong("constants.simulation-time")
  val startTime = System.currentTimeMillis()
  val totalDuration = if(durationInMinutes.isDefined) FiniteDuration(durationInMinutes.get, TimeUnit.MINUTES) else FiniteDuration(defaultDuration, TimeUnit.MINUTES)
  val startDelay = new FiniteDuration(5, TimeUnit.SECONDS)
  val samplingInterval = new FiniteDuration(5, TimeUnit.SECONDS)
  val requirementChangeDelay = totalDuration.div(2)
  // publisher naming convention: P:hostname:port, e.g. "P:DoorSensor:2502", or "P:localhost:2501" (for local testing)
  val pNames = Vector("P:SanitizerSensor:3301")
  val windowSize = 5.seconds
  val latencyRequirement = latency < timespan(500.milliseconds) otherwise None
  val messageHopsRequirement = overhead < 3 otherwise None
  val loadRequirement = load < MachineLoad(10.0d) otherwise None
  val frequencyRequirement = frequency > Frequency(50, 5) otherwise None

  println("Using publisher " + pNames)

  override def receive: Receive = {
    super.receive orElse  {

      case BandwidthMeasurementComplete(source: Address, target: Address, bw: Double) =>
        //if(!measuredLinks.contains((target, source)))
          measuredLinks += (source, target) -> bw
        bandwidthMeasurementsCompleted += 1
        //log.info(s"${System.currentTimeMillis() - startTime}ms passed, received bandwidthMeasurementComplete message from $source to $target: $bw")

      case VivaldiCoordinatesEstablished() =>
        coordinatesEstablishedCount += 1
        if(coordinatesEstablishedCount >= minimumNodes) {
          log.info("all nodes established their coordinates")
          coordinatesEstablished = true
        }

      case s: CurrentClusterState => this.currentClusterState(s)
      case _ =>
    }
  }

  override def preStart(): Unit = {
    super.preStart()
    context.system.scheduler.scheduleOnce(new FiniteDuration(5, TimeUnit.SECONDS))(checkAndRunQuery)
  }
  //used for finding Publisher nodes and Benchmark node
  override def currentClusterState(state: CurrentClusterState): Unit = {
    super.currentClusterState(state)
    state.members.filter(x => x.status == MemberStatus.Up && x.hasRole("Publisher")) foreach extractProducers
  }

  //used for finding Publisher nodes and Benchmark node
  override def memberUp(member: Member): Unit = {
    super.memberUp(member)
    if (member.hasRole("Publisher")) extractProducers(member)
    else {
      log.info(s"found member $member with role: ${member.roles}")
    }
  }

  def extractProducers(member: Member): Unit = {
    log.info(s"Found publisher node ${member}")
    implicit val resolveTimeout = Timeout(60, TimeUnit.SECONDS)
    val actorRef = Await.result(context.actorSelection(RootActorPath(member.address) / "user" / "P:*").resolveOne(), resolveTimeout.duration)
    publishers += s"P:${actorRef.path.address.host.getOrElse("UnknownHost")}:${actorRef.path.address.port.getOrElse(0)}" -> actorRef
    log.info(s"saving publisher ${actorRef.path.name}")
  }

  //If
  // 1. all publishers required in the query are available
  // 2. all link bandwidths are measured
  // 3. all nodes have established their coordinates
  // ... then run the simulation
  def checkAndRunQuery(): Unit = {

    val timeSinceStart = System.currentTimeMillis() - startTime
    val upMembers = cluster.state.members.filter(m => m.status == MemberStatus.up && !m.hasRole("VivaldiRef"))
    log.info(s"checking if ready, time since init: ${timeSinceStart}ms," +
      s" completed bandwidthMeasurements: (${measuredLinks.size} of $minimumBandwidthMeasurements)," +
      s" upMembers: (${upMembers.size} of $minimumNodes)" +
      s" started: $simulationStarted")

    if(upMembers.size >= minimumNodes) { // notify all TaskManagers to start bandwidth measurements once all nodes are up
      upMembers.foreach(m => TCEPUtils.selectTaskManagerOn(cluster, m.address) ! InitialBandwidthMeasurementStart())
    }

    if(measuredLinks.size >= minimumBandwidthMeasurements) {
      cluster.state.members.foreach(m => TCEPUtils.selectDistVivaldiOn(cluster, m.address) ! StartVivaldiUpdates())
    }

    if (pNames.toSet.subsetOf(publishers.keySet) && // publishers found
      upMembers.size >= minimumNodes && // nodes up
      measuredLinks.size >= minimumBandwidthMeasurements && // all links bandwidth measured
      coordinatesEstablished && // all nodes have established their coordinates
      !simulationStarted
    ){
      simulationStarted = true
      SpecialStats.debug(s"$this", s"ready to start after $timeSinceStart, setting up simulation...")
      log.info(s"measured bandwidths: \n ${measuredLinks.toList.sorted.map(e => s"\n ${e._1._1} <-> ${e._1._2} : ${e._2} Mbit/s")}")
      this.executeSimulation()

    } else {
      SpecialStats.debug(s"$this", s"$timeSinceStart ms since start, waiting for initialization to finish; " +
        s"measured links: (${measuredLinks.size} of $minimumBandwidthMeasurements);" +
        s"coordinates established: ($coordinatesEstablishedCount of $minimumNodes); " +
        s"publishers: ${publishers.mkString(";")}")
      context.system.scheduler.scheduleOnce(new FiniteDuration(5, TimeUnit.SECONDS))(checkAndRunQuery)
    }
  }

  /**
    * run a simulation with the specified starting parameters
    * @param i                  simulation run number
    * @param placementStrategy  starting placement strategy
    * @param transitionMode     transition mode (MFGS or SMS) to use if requirements change
    * @param initialRequirements query to be executed
    * @param finishedCallback   callback to apply when simulation is finished
    * @param requirementChanges  requirement to change to after requirementChangeDelay
    * @param splcDataCollection boolean to collect additional data for CONTRAST MAPEK implementation (machine learning data)
    * @return queryGraph of the running simulation
    */
  def runSimulation(i: Int, placementStrategy: PlacementStrategy, transitionMode: Mode, finishedCallback: () => Any,
                    initialRequirements: Set[Requirement], requirementChanges: Option[Set[Requirement]] = None, splcDataCollection: Boolean = false): QueryGraph = {

    val query = stream[Int](pNames(0)).customOperator("op-test")

    //val baseQuery = stream[Int](pNames(0)).join(stream[Int](pNames(1)), slidingWindow(windowSize), slidingWindow(windowSize)).test()
    //val query = Filter2(baseQuery, _ => true, initialRequirements) // separate clause without DSL here so we can use multiple requirements from function argument
    val sim = new Simulation(this.context, s"${transitionMode}-${placementStrategy.strategyName()}-$i", directory, query, transitionMode, publishers, Some(placementStrategy), AllRecords())
    val graph = sim.startSimulation(placementStrategy.strategyName(), windowSize.i, startDelay, samplingInterval, totalDuration)(finishedCallback) // (start simulation time, interval, end time (s))
    log.info(s"starting $transitionMode ${placementStrategy.strategyName()} algorithm simulation number $i")
    context.system.scheduler.scheduleOnce(totalDuration)(this.shutdown())
    if(requirementChanges.isDefined) {
      log.info(s"scheduling requirement change after $requirementChangeDelay")
      /* <-- this does not work, (reference to graph is lost?)
      context.system.scheduler.scheduleOnce(requirementChangeDelay)(() => {
        log.info(s"changing requirement to ${requirementChanges.get}")
        initialRequirements.foreach(r => graph.removeDemand(r))
        requirementChanges.get.foreach(r => graph.addDemand(r))
      })
      */
      Thread.sleep(requirementChangeDelay.toMillis) // this blocks the thread, not recommended inside an actor
      initialRequirements.foreach(r => graph.removeDemand(r))
      graph.addDemand(requirementChanges.get.toSeq)
    }

    graph
  }

  /**
    * run a simulation with the specified starting parameters
    * @return queryGraph of the running simulation
    */
  def testCustom(): QueryGraph = {
    val query = stream[Int](pNames(0))
    var fullQuery: Query1[Int] = null
    if (!System.getenv("SERVERLESS").toBoolean) {
      fullQuery = query.forward()
    } else {
      // CHANGE BY MATHEUS: SWITCH CUSTOM OPERATOR NAME TO THE ENVIRONMENT CUSTOM_OPERATOR
      fullQuery = query.customOperator(sys.env("CUSTOM_OPERATOR"))
    }
    val benchmark = fullQuery.benchmark()

    //val baseQuery = stream[Int](pNames(0)).join(stream[Int](pNames(1)), slidingWindow(windowSize), slidingWindow(windowSize)).test()
    //val query = Filter2(baseQuery, _ => true, initialRequirements) // separate clause without DSL here so we can use multiple requirements from function argument
    val sim = new Simulation(this.context, s"CUSTOM-Starks", directory, benchmark, tcep.graph.nodes.traits.Mode.MFGS, publishers, Some(StarksAlgorithm), AllRecords())
    val graph = sim.startSimulation("Starks", windowSize.i, startDelay, samplingInterval, totalDuration)(() => log.info("Finished custom execution")) // (start simulation time, interval, end time (s))
    log.info(s"starting custom starks algorithm simulation")
    context.system.scheduler.scheduleOnce(totalDuration)(this.shutdown())
    graph
  }

  def testGUI(i: Int, j: Int, windowSize: Int): Unit = {

    var mfgsSims: mutable.MutableList[Simulation] = mutable.MutableList()
    var graphs: mutable.MutableList[QueryGraph] = mutable.MutableList()
    var currentTransitionMode: String = null
    var currentStrategyName: String = null
    var simulationStarted = false
    val allReqs = Set(latencyRequirement, loadRequirement, messageHopsRequirement)
    val startSimulationRequest = (transitionMode: String, optimizationCriteria: List[String]) => {
      if (simulationStarted) {
        return
      }
      val mode = transitionMode match {
        case "MFGS" => tcep.graph.nodes.traits.Mode.MFGS
        case "SMS" => tcep.graph.nodes.traits.Mode.SMS
      }

      var globalOptimalBDPQuery: Query = null
      if (optimizationCriteria != null) {
        val newReqs = optimizationCriteria.map(reqName => allReqs.find(_.name == reqName).getOrElse(throw new IllegalArgumentException(s"unknown requirement name: ${reqName}")))
        globalOptimalBDPQuery = stream[Int](pNames(0)).join(stream[Int](pNames(1)), slidingWindow(windowSize.seconds), slidingWindow(windowSize.seconds)).where((_, _) => true, newReqs:_*)
      } else {
        globalOptimalBDPQuery = stream[Int](pNames(0)).join(stream[Int](pNames(1)), slidingWindow(windowSize.seconds), slidingWindow(windowSize.seconds)).where((_, _) => true, latencyRequirement, messageHopsRequirement)
      }


      for (i <- 1 to 5) {
        var percentage: Double = i*10d
        val mfgsSim = new Simulation(this.context, transitionMode, directory, globalOptimalBDPQuery, mode, publishers, Some(PietzuchAlgorithm),AllRecords())
        mfgsSims += mfgsSim

        //start at 20th second, and keep recording data for 5 minutes
        val graph = mfgsSim.startSimulation(s"$transitionMode-Strategy", windowSize, startDelay, samplingInterval, FiniteDuration.apply(0, TimeUnit.SECONDS))(null)
        graphs += graph

        currentStrategyName = graph.getPlacementStrategy().strategyName()
        currentTransitionMode = transitionMode
        simulationStarted = true
      }
    }

    val transitionRequest = (optimizationCriteria: List[String]) => {
      log.info("Received new optimization criteria " + optimizationCriteria.toString())
      //optimizationCriteria.foreach(op => {
        graphs.foreach(graph => {
          val newReqs = optimizationCriteria.map(reqName => allReqs.find(_.name == reqName).getOrElse(throw new IllegalArgumentException(s"unknown requirement name: ${reqName}")))
          allReqs.foreach(req => graph.removeDemand(req))
          graph.addDemand(newReqs)

          /*
          // TODO does not accurately reflect for which qos metrics each algorithm optimizes, but the way BenchmarkingNode works
          if (op == LatencyRequirement.name) { // Relaxation -> latency + load
            graph.removeDemand(messageHopsRequirement)
            graph.addDemand(Seq(latencyRequirement, loadRequirement))
            Thread.sleep(2000)
            currentStrategyName = graph.mapek.knowledge.getStrategy().placement.strategyName()
          } else if (op == MessageHopsRequirement.name) { // MDCEP -> load + hops
            graph.removeDemand(latencyRequirement)
            graph.addDemand(Seq(messageHopsRequirement ,loadRequirement))
            Thread.sleep(2000)
            currentStrategyName = graph.mapek.knowledge.getStrategy().placement.strategyName()
          } else if (op == LoadRequirement.name) { // GlobalOptimalBDP -> latency + hops
            graph.removeDemand(loadRequirement)
            graph.addDemand(Seq(latencyRequirement, messageHopsRequirement))
            Thread.sleep(2000)
            currentStrategyName = graph.mapek.knowledge.getStrategy().placement.strategyName()
          }
          */
        })
    }

    val manualTransitionRequest = (algorithmName: String) => {
      log.info("Received new manual transition request " + algorithmName)
      //optimizationCriteria.foreach(op => {
      graphs.foreach(graph => {
        graph.manualTransition(algorithmName)
      })
    }

    val stop = () => {
      mfgsSims.foreach(sim => {
        sim.stopSimulation()
      })
      graphs.foreach(graph => {
        graph.stop()
      })
      graphs = mutable.MutableList()
      mfgsSims = mutable.MutableList()
      simulationStarted = false
    }

    val status = () => {
      var strategyName = "none"
      var transitionMode = "none"
      if (currentStrategyName != null) {
        strategyName = currentStrategyName
      }
      if (currentTransitionMode != null) {
        transitionMode = currentTransitionMode
      }

      if (graphs.nonEmpty) {
        val graph = graphs.get(0) // we only get the strategy from one graph assuming that all of them execute the same strategy
        currentStrategyName = graph.get.mapek.knowledge.getStrategy().placement.strategyName()
      }

      val response = Map(
        "placementStrategy" -> strategyName,
        "transitionMode" -> transitionMode
      )
      print(response)
      response
    }

    val server = new TCEPSocket(this.context.system)
    server.startServer(startSimulationRequest, transitionRequest, manualTransitionRequest, stop, status)
  }


  def executeSimulation(): Unit = mode match {

    case Mode.TEST_PIETZUCH => for(i <- Range(1,2))
      this.runSimulation(i, PietzuchAlgorithm, tcep.graph.nodes.traits.Mode.MFGS,
        () => log.info(s"Starks algorithm Simulation Ended for $i"),
        Set(latencyRequirement))

    case Mode.TEST_STARKS => for(i <- Range(1,2))
      this.runSimulation(i, StarksAlgorithm, tcep.graph.nodes.traits.Mode.MFGS,
        () => log.info(s"Starks algorithm Simulation Ended for $i"),
        Set(messageHopsRequirement))

    case Mode.TEST_RIZOU => for(i <- Range(1,2))
      this.runSimulation(i, StarksAlgorithm, tcep.graph.nodes.traits.Mode.MFGS,
        () => log.info(s"Starks algorithm Simulation Ended for $i"),
        Set(loadRequirement)) // use load requirement to make it "selectable" for RequirementBasedMAPEK/BenchmarkingNode

    case Mode.TEST_SMS =>
      this.runSimulation(1, PietzuchAlgorithm, tcep.graph.nodes.traits.Mode.SMS,
        () => log.info(s"SMS Relaxation->Starks algorithm Simulation ended"),
        Set(latencyRequirement, frequencyRequirement), Some(Set(messageHopsRequirement, frequencyRequirement)))

    case Mode.TEST_MFGS =>
      this.runSimulation(1, GlobalOptimalBDPAlgorithm, tcep.graph.nodes.traits.Mode.MFGS,
        () => log.info(s"MFGS GlobalOptimalBDP->Starks algorithm Simulation ended"),
        Set(latencyRequirement, messageHopsRequirement), Some(Set(loadRequirement, messageHopsRequirement)))

    case Mode.SPLC_DATACOLLECTION =>
      this.runSimulation(1, PietzuchAlgorithm, tcep.graph.nodes.traits.Mode.MFGS,
        () => log.info(s"MFGS Relaxation->Rizou algorithm Simulation with SPLC data collection enabled ended"),
        Set(latencyRequirement), Some(Set(loadRequirement)), true)

    case Mode.TEST_GUI => this.testGUI(0, 0, 5)
    case Mode.TEST_CUSTOM => this.testCustom()
    case Mode.DO_NOTHING => log.info("simulation ended!")

  }

  def shutdown() = {
    import scala.sys.process._
    ("pkill -f ntpd").!
    CoordinatedShutdown.get(cluster.system).run(CoordinatedShutdown.ClusterDowningReason)
    this.cluster.system.terminate()

  }

}

case class RecordLatency() extends LatencyMeasurement {
  var lastMeasurement: Option[java.time.Duration] = Option.empty

  override def apply(latency: java.time.Duration): Any = {
    lastMeasurement = Some(latency)
  }
}

case class RecordMessageHops() extends MessageHopsMeasurement {
  var lastMeasurement: Option[Int] = Option.empty

  override def apply(overhead: Int): Any = {
    lastMeasurement = Some(overhead)
  }
}

case class RecordAverageLoad() extends LoadMeasurement {
  var lastLoadMeasurement: Option[MachineLoad] = Option.empty

  def apply(load: MachineLoad): Any = {
    lastLoadMeasurement = Some(load)
  }
}

case class RecordFrequency() extends FrequencyMeasurement {
  var lastMeasurement: Option[Int] = Option.empty

  override def apply(frequency: Int): Any = {
    lastMeasurement = Some(frequency)
  }
}

case class RecordTransitionStatus() extends TransitionMeasurement {
  var lastMeasurement: Option[Int] = Option.empty

  override def apply(status: Int): Any = {
    lastMeasurement = Some(status)
  }
}

case class RecordOverhead() extends PlacementOverheadMeasurement {
  var lastOverheadMeasurement: Option[Long] = Option.empty

  override def apply(status: Long): Any = {
    lastOverheadMeasurement = Some(status)
  }
}

case class RecordNetworkUsage() extends NetworkUsageMeasurement {
  var lastUsageMeasurement: Option[Double] = Option.empty

  override def apply(status: Double): Any = {
    lastUsageMeasurement = Some(status)
  }
}

case class RecordPublishingRate() extends PublishingRateMeasurement {
  var lastRateMeasurement: Option[Double] = Option.empty

  override def apply(status: Double): Any = {
    lastRateMeasurement = Some(status)
  }
}

case class AllRecords(recordLatency: RecordLatency = RecordLatency(),
                      recordAverageLoad: RecordAverageLoad = RecordAverageLoad(),
                      recordMessageHops: RecordMessageHops = RecordMessageHops(),
                      recordFrequency: RecordFrequency = RecordFrequency(),
                      recordOverhead: RecordOverhead = RecordOverhead(),
                      recordNetworkUsage: RecordNetworkUsage = RecordNetworkUsage(),
                      recordPublishingRate: RecordPublishingRate = RecordPublishingRate(),
                      recordTransitionStatus: Option[RecordTransitionStatus] = Some(RecordTransitionStatus())) {
  def allDefined: Boolean =
    recordLatency.lastMeasurement.isDefined &&
    recordMessageHops.lastMeasurement.isDefined &&
    recordAverageLoad.lastLoadMeasurement.isDefined &&
    recordFrequency.lastMeasurement.isDefined &&
    recordOverhead.lastOverheadMeasurement.isDefined &&
    recordNetworkUsage.lastUsageMeasurement.isDefined /*&&
    recordPublishingRate.lastRateMeasurement.isDefined*/

  def getRecordsList: List[Measurement] = List(recordLatency, recordAverageLoad, recordMessageHops, recordFrequency, recordOverhead, recordNetworkUsage, recordPublishingRate, recordTransitionStatus.get)
  def getValues = this.getRecordsList.map {
    case l: RecordLatency => l -> l.lastMeasurement
    case l: RecordAverageLoad => l -> l.lastLoadMeasurement
    case h: RecordMessageHops => h -> h.lastMeasurement
    case f: RecordFrequency => f -> f.lastMeasurement
    case o: RecordOverhead => o -> o.lastOverheadMeasurement
    case n: RecordNetworkUsage => n -> n.lastUsageMeasurement
    case p: RecordPublishingRate => p -> p.lastRateMeasurement
    case t: RecordTransitionStatus => t -> t.lastMeasurement
  }
}