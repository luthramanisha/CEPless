package tcep.config

import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory

/**
  * Created by anjaria on 31.07.17.
  */
trait ConfigurationParser {
  lazy val logger = LoggerFactory.getLogger(getClass)

  def getArgs: Array[String]

  lazy val argList = getArgs.toList
  type OptionMap = Map[Symbol, Any]

  private def readArgs(map: OptionMap, list: List[String]): OptionMap = {
    list match {
      case Nil => map
      case "--ip" :: value :: tail => //TODO: change it to hostname
        readArgs(map ++ Map('ip -> value), tail)
      case "--port" :: value :: tail =>
        readArgs(map ++ Map('port -> value.toInt), tail)
      case "--name" :: value :: tail =>
        readArgs(map ++ Map('name -> value), tail)
      case "--host" :: value :: tail =>
        readArgs(map ++ Map('host -> value), tail)
      case option :: tail => println("Unknown option " + option); Map()
    }
  }

  lazy val options = readArgs(Map(), argList)
  lazy val config = ConfigFactory.parseString(s"akka.remote.netty.tcp.port=${options('port)}")
    .withFallback(ConfigFactory.parseString(s"akka.remote.netty.tcp.hostname=${options('ip)}"))
    .withFallback(ConfigFactory.parseString(s"akka.cluster.roles=[$getRole]"))
    .withFallback(ConfigFactory.load())

  def getRole: String
}
