// The simplest possible sbt build file is just one line:

scalaVersion := "2.13.12"

name := "fs-kafka-example"
version := "1.0"

val logbackVersion = "1.4.11"
val log4catsVersion = "2.6.0"
val fs2KafkaVersion = "3.1.0"
lazy val javaMailVersion = "1.6.2"

val fs2Kafka = "com.github.fd4s" %% "fs2-kafka" % fs2KafkaVersion

val logback = "ch.qos.logback" % "logback-classic" % logbackVersion
val log4cats = "org.typelevel" %% "log4cats-slf4j" % log4catsVersion
//Compile / run / fork := true

libraryDependencies ++= Seq(
  fs2Kafka,
  log4cats,
  logback
)

val javaEmail = "com.sun.mail" % "javax.mail" % javaMailVersion
// by default sbt run runs the program in the same JVM as sbt
//in order to run the program in a different JVM, we add the following
fork in run := true

//scalacOptions +="-target:17"// ensures the Scala compiler generates bytecode optimized for the Java 17 virtual machine

//We can also set the soruce and target compatibility for the Java compiler by configuring the JavaOptions in build.sbt

// javaOptions ++= Seq(
//   "-soruce","17","target","17"
// )
ThisBuild / semanticdbEnabled := true
