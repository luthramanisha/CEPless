name := "tcep"

version := "0.0.1-SNAPSHOT"

scalaVersion := "2.12.1"

libraryDependencies ++= Seq(
  //"com.typesafe.akka" %% "akka-actor"   % "2.5.9",
  "com.typesafe.akka" % "akka-remote_2.12" % "2.5.9",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.9"  % "test",
  "com.typesafe.akka" %% "akka-http"   % "10.1.5",
  "ch.megard" %% "akka-http-cors" % "0.3.1",
  "com.typesafe.akka" %% "akka-stream" % "2.5.9",
  "com.typesafe.akka" %% "akka-multi-node-testkit" % "2.5.9" % "test",
  "com.espertech"     %  "esper"        % "5.5.0",
  "org.scalatest"     %% "scalatest"    % "3.0.1"   % "test",
  "org.mockito"       % "mockito-core"  % "2.23.0"  % "test",
  "com.typesafe.akka" % "akka-cluster-metrics_2.12" % "2.5.9",
  "com.typesafe.akka" %% "akka-slf4j" % "2.5.9",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.twitter" % "chill-akka_2.12" % "0.9.2",
  "org.scalaj" %% "scalaj-http" % "2.4.1")

libraryDependencies += "org.scala-lang" % "scala-reflect" % "2.12.1"
libraryDependencies += "org.infinispan" % "infinispan-core" % "9.4.16.Final"
libraryDependencies += "org.infinispan" % "infinispan-client-hotrod" % "9.4.16.Final"
libraryDependencies += "org.infinispan" % "infinispan-commons" % "9.4.16.Final"
libraryDependencies += "org.apache.kafka" % "kafka-clients" % "0.10.0.0"
libraryDependencies += "io.lettuce" % "lettuce-core" % "5.2.0.RELEASE"



import com.github.retronym.SbtOneJar._

oneJarSettings
libraryDependencies += "commons-lang" % "commons-lang" % "2.6"
libraryDependencies += "com.google.guava" % "guava" % "19.0"

//mainClass in(Compile, run) := Some("de.kom.tud.cep.Main")
//mainClass in oneJar := Some("de.kom.tud.cep.Main")
mainClass in oneJar := Some("tcep.broker.BrokerApp")
mainClass in oneJar := Some("tcep.simulation.trasitivecep.SimulationRunner")

import com.typesafe.sbt.SbtMultiJvm.multiJvmSettings

lazy val root = (project in file("."))
  .enablePlugins(MultiJvmPlugin) // use the plugin
  .configs(MultiJvm) // load the multi-jvm configuration
  .settings(multiJvmSettings: _*) // apply the default settings
  .settings(
  parallelExecution in Test := false // do not run test cases in parallel
)
 