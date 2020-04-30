name := "Majabigwaduce"

version := "1.0.2"

scalaVersion := "2.13.1"

val akkaGroup = "com.typesafe.akka"
val akkaVersion = "2.6.4"
val scalaTestVersion = "3.1.1"
val configVersion = "1.4.0"
val scalaMockVersion = "4.4.0"
val logBackVersion = "1.2.3"
val scalaXMLVersion = "1.3.0"
scalacOptions in (Compile,doc) ++= Seq("-groups", "-implicits", "-deprecation")

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= Seq(
	akkaGroup %% "akka-actor" % akkaVersion,
	akkaGroup %% "akka-slf4j" % akkaVersion,
	akkaGroup %% "akka-cluster" % akkaVersion,
	akkaGroup %% "akka-remote" % akkaVersion,
	akkaGroup %% "akka-cluster-metrics" % akkaVersion,
	"com.typesafe" % "config" % configVersion,
	"ch.qos.logback" % "logback-classic" % logBackVersion % "runtime",
	akkaGroup %% "akka-testkit" % akkaVersion % "test",
	"org.scalatest" %% "scalatest" % scalaTestVersion % "test",
	"org.scalamock" %% "scalamock" % scalaMockVersion % "test",
// xml and tagsoup are for WebCrawler exemplar
  "org.scala-lang.modules" %% "scala-xml" % scalaXMLVersion % "test",
	"org.ccil.cowan.tagsoup" % "tagsoup" % "1.2.1" % "test"
	
)

unmanagedSourceDirectories in Test += baseDirectory.value / "src/it/scala"
unmanagedResourceDirectories in Test += baseDirectory.value / "src/it/resources"

parallelExecution in Test := false

