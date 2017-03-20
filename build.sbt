name := "couchbase-listener"

version := "1.1"

scalaVersion := "2.12.1"

resolvers += Resolver.bintrayRepo("hseeberger", "maven")

libraryDependencies += "com.couchbase.client" % "dcp-client" % "0.8.0"
libraryDependencies += "com.typesafe.akka" %% "akka-stream" % "2.4.17"
libraryDependencies += "org.slf4j" % "slf4j-api" % "1.7.24"
libraryDependencies += "org.slf4j" % "slf4j-simple" % "1.7.24"
libraryDependencies += "de.heikoseeberger" %% "akka-sse" % "2.0.0"
libraryDependencies += "com.typesafe.play" %% "play-json" % "2.6.0-M1"
libraryDependencies += "com.couchbase.client" % "java-client" % "2.4.3"
libraryDependencies += "io.reactivex" % "rxscala_2.11" % "0.26.5"

enablePlugins(JavaAppPackaging)
