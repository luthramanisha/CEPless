package tcep.simulation.tcep

import java.io.{File, PrintStream}
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.concurrent.TimeUnit

import akka.actor.{ActorContext, ActorRef, Cancellable}
import akka.cluster.Cluster
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import tcep.data.Queries.Query
import tcep.graph.QueryGraph
import tcep.graph.nodes.traits.Mode.Mode
import tcep.graph.qos._
import tcep.machinenodes.{EventPublishedCallback, GraphCreatedCallback}
import tcep.placement.PlacementStrategy

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class Simulation(context: ActorContext, name: String, directory: Option[File], query: Query, mode: Mode, publishers: Map[String, ActorRef], startingPlacementStrategy: Option[PlacementStrategy], allRecords: AllRecords) {

  private val latencyMonitorFactory = PathLatencyMonitorFactory(query, Some(allRecords.recordLatency))
  private val hopsMonitorFactory = MessageHopsMonitorFactory(query, Some(allRecords.recordMessageHops))
  private val loadMonitorFactory = LoadMonitorFactory(query, Some(allRecords.recordAverageLoad))
  private val frequencyMonitorFactory = AverageFrequencyMonitorFactory(query, Some(allRecords.recordFrequency))
  private val recordTransitionStatusFactory = TransitionMonitorFactory(query, allRecords.recordTransitionStatus)
  // private val messagesFactory = MessageMonitorFactory(query, directory)
  private val overheadMonitorFactory = PlacementOverheadMonitorFactory(query, allRecords.recordOverhead)
  private val networkUsageMonitorFactory = NetworkUsageMonitorFactory(query, allRecords.recordNetworkUsage)

  private val monitors: Array[MonitorFactory] = Array(latencyMonitorFactory, hopsMonitorFactory,
    loadMonitorFactory, frequencyMonitorFactory, overheadMonitorFactory, networkUsageMonitorFactory,
    recordTransitionStatusFactory)

  val ldt = LocalDateTime.now
  val out = directory map { directory => new PrintStream(new File(directory, s"$ldt-$name.csv"))
  } getOrElse java.lang.System.out
  protected val log = LoggerFactory.getLogger(getClass)
  protected var queryGraph: QueryGraph = _
  protected var simulation: Cancellable = _
  protected var guiUpdater: Cancellable = _
  protected var callback: () => Any = _
  implicit private val timeout = Timeout(5 seconds)
  private val MillisPerEvent = ConfigFactory.load().getInt("constants.event-interval-millis")

  /**
    *
    * @param startTime Start Time of Simulation (in Seconds)
    * @param interval  Interval for recording data in CSV (in Seconds)
    * @param totalTime Total Time for the simulation (in Seconds)
    * @return
    */
  def startSimulation(algoName: String, windowSize: Int, startTime: FiniteDuration, interval: FiniteDuration, totalTime: FiniteDuration)(callback: () => Any): QueryGraph = {
    this.callback = callback
    val graph = executeQuery()
    startSimulationLog(algoName, startTime, interval, totalTime, callback, windowSize.toString)
    graph
  }

  def executeQuery(): QueryGraph = {
    queryGraph = new QueryGraph(context, Cluster(context.system), query, publishers,  Some(GraphCreatedCallback()), monitors)
    queryGraph.createAndStart(monitors)(Some(EventPublishedCallback()))
    guiUpdater = context.system.scheduler.schedule(0 seconds, 60 seconds)(GUIConnector.sendMembers(queryGraph))
    queryGraph
  }


  def startSimulationLog(algoName: String, startTime: FiniteDuration, interval: FiniteDuration, totalTime: FiniteDuration, callback: () => Any, windowSize: String = "0"): Any = {

    var time = 0L
    val transitionExecutionMode = ConfigFactory.load().getInt("constants.transition-execution-mode")
    var prevTStatus: Long = 0
    var prevTime: Long = 0
    var tStartTime: Long = 0
    var executionMode: String = "Sequential"
    var header: Boolean = false

    def createCSVEntry(): Unit = synchronized {
      if (allRecords.allDefined) {
          val status = queryGraph.mapek.knowledge.transitionStatus
          val placementStrategy = queryGraph.mapek.knowledge.getStrategy().placement.strategyName()

          var EventsPerSecond = 1000.0d / MillisPerEvent
          var totTransitionTime = 0L
          if (status > 0) {
            prevTStatus = 1
          } else {
            prevTStatus = 0
          }
          if (prevTStatus == 0 && status == 1)
            tStartTime = time
          if (prevTStatus == 1 && status == 0)
            totTransitionTime = prevTime - tStartTime
          if (transitionExecutionMode == 1)
            executionMode = "Exponential"
          if (time == 0 && !header) {
            out.append(s"Placement \t CurrentTime \t Time \t latency \t hops \t cpuUsage \t OpeventRate \t MessageOverhead \t NetworkUsage \t TransitionStatus ")
            out.println()
            header = true
          }
          log.info(s"write csv ${allRecords.recordLatency.lastMeasurement}")
          out.append(
              s"$placementStrategy" +
              s"\t ${LocalDateTime.ofInstant(Instant.now, ZoneOffset.UTC).getHour}:${LocalDateTime.ofInstant(Instant.now, ZoneOffset.UTC).getMinute}:${LocalDateTime.ofInstant(Instant.now, ZoneOffset.UTC).getSecond} " +
              s"\t $time \t ${allRecords.recordLatency.lastMeasurement.get.toMillis.toString} " +
              s"\t ${allRecords.recordMessageHops.lastMeasurement.get.toString} " +
              s"\t ${BigDecimal(allRecords.recordAverageLoad.lastLoadMeasurement.get.value).setScale(2, BigDecimal.RoundingMode.HALF_UP).toString} " +
              s"\t ${allRecords.recordFrequency.lastMeasurement.get.toString} " +
              s"\t ${allRecords.recordOverhead.lastOverheadMeasurement.get.toString} " +
              s"\t ${BigDecimal(allRecords.recordNetworkUsage.lastUsageMeasurement.get).setScale(2, BigDecimal.RoundingMode.HALF_UP).toString} " +
              s"\t $status ")
          out.println()
          prevTime = time
          prevTStatus = status
          time += interval.toSeconds

      } else {
        // log.info(s"Data not available yet! $allRecords")
      }
    }

    simulation = context.system.scheduler.schedule(startTime, interval)(createCSVEntry())

    // If the total time is set to 0, run the simulation infinitely until stopped
    if (totalTime.toSeconds != 0) {
      context.system.scheduler.scheduleOnce(totalTime)(stopSimulation())
      context.system.scheduler.scheduleOnce(totalTime)(GUIConnector.sendMembers(queryGraph))
    }
  }

  def stopSimulation(): Unit = {
    simulation.cancel()
    guiUpdater.cancel()
    out.close()
    queryGraph.stop()
    if (callback != null) {
      //wait for all actors to stop
      context.system.scheduler.scheduleOnce(FiniteDuration.apply(1, TimeUnit.SECONDS))(() => callback.apply())
    }
  }
}

