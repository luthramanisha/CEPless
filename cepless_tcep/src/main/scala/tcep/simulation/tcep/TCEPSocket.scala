package tcep.simulation.tcep

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives.{entity, _}
import akka.stream.ActorMaterializer
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.typesafe.config.{Config, ConfigFactory}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.util.parsing.json.{JSON, JSONArray, JSONObject}

class TCEPSocket(actorSystem: ActorSystem) {

  implicit val system: ActorSystem = actorSystem
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  private def config: Config = ConfigFactory.load()

  def startServer(startRequest: (String, List[String]) => Unit, transitionRequest: List[String] => Unit, manualTransitionRequest: String => Unit, stopRequest: () => Unit, statusRequest: () => Map[String, String]): Unit = {

    val algorithmNames = config.getConfig("benchmark.general").getStringList("algorithms")
    var algorithms = new ListBuffer[JSONObject]()

    algorithmNames.forEach(algo => {
      val algoConf = config.getConfig("benchmark").getConfig(algo)
      val optimizations: List[String] = algoConf.getStringList("optimizationCriteria").toList

      algorithms += JSONObject(Map(
        "algorithm" -> algo,
        "optimizationCriteria" -> JSONArray(optimizations)
      ))
    })

    val modes: List[String] = List("MFGS", "SMS")
    val algorithmsResponse = JSONObject(Map(
      "algorithms" -> JSONArray(algorithms.toList),
      "modes" -> JSONArray(modes)
    )).toString()

    val route =
      cors() {
        path("algorithms") {
          get {
            complete(HttpEntity(ContentTypes.`application/json`, algorithmsResponse))
          }
        }
      } ~
      cors() {
        path("status") {
          get {
            print(statusRequest())
            val response = JSONObject(statusRequest()).toString()
            complete(HttpEntity(ContentTypes.`application/json`, response))
          }
        }
      } ~
      cors() {
        path("start") {
          post {
            entity(as[String]) { entity =>
              val result = JSON.parseFull(entity)
              result match {
                case Some(body: Map[String, Any]) => {
                  val criteria: List[String] = body.getOrElse("criteria", List("BDP")).asInstanceOf[List[String]]
                  startRequest(body.getOrElse("mode", "MFGS").asInstanceOf[String], criteria)
                  complete(HttpEntity("success"))
                }

                case None => complete(HttpEntity("Parsing failed"))
                case other => complete(HttpEntity("Unknown data structure: " + other))
              }
            }
          }
        }
      } ~
        cors() {
          path("stop") {
            post {
              entity(as[String]) { entity =>
                stopRequest()
                complete(HttpEntity("success"))
              }
            }
          }
        } ~
        cors() {
          path("autoTransition") {
            post {
              entity(as[String]) { entity =>
                // TODO: enable/disable auto transition
                complete(HttpEntity("success"))
              }
            }
          }
        } ~
      cors() {
        path("manualTransition") {
          post {
            entity(as[String]) { entity =>
              val result = JSON.parseFull(entity)
              result match {
                case Some(body: Map[String, String]) => {
                  manualTransitionRequest(body.getOrElse("algorithm", "Relaxation"))
                  complete(HttpEntity("success"))
                }

                case None => complete(HttpEntity("Parsing failed"))
                case other => complete(HttpEntity("Unknown data structure: " + other))
              }
            }
          }
        }
      } ~
      cors() {
        path("transition") {
          post {
            entity(as[String]) { entity =>
              print(entity)
              val result = JSON.parseFull(entity)
              result match {
                case Some(criteria: List[Any]) =>
                  transitionRequest(criteria.map(_.toString))
                  complete(HttpEntity("success"))
                case None => complete(HttpEntity("Parsing failed"))
                case other => complete(HttpEntity("Unknown data structure: " + other))
              }

            }
          }
        }
      }

    val server = Http().bindAndHandle(route, "0.0.0.0", 25001)

    println(s"Awaiting GUI requests at port 3001")
  }
}