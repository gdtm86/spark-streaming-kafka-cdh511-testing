import _root_.sbtassembly.AssemblyPlugin.autoImport._
import _root_.sbtassembly.PathList

name := "spark-streaming-kafka-cdh511-testing"

version := "1.0"

scalaVersion := "2.11.9"


libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-streaming" % "2.1.0.cloudera1",
  "org.apache.spark" %% "spark-core" % "2.1.0.cloudera1" excludeAll ExclusionRule(organization = "javax.servlet"),
  "org.apache.spark" %% "spark-streaming-kafka-0-10" % "2.1.0.cloudera1",
  "org.apache.hbase" % "hbase-client" %  "1.2.0-cdh5.11.0",
  "org.apache.hbase" % "hbase-common" % "1.2.0-cdh5.11.0"
)


assemblyMergeStrategy in assembly := {
  case PathList("javax", "servlet", xs @ _*) => MergeStrategy.last
  case PathList("javax", "activation", xs @ _*) => MergeStrategy.last
  case PathList("org", "apache", xs @ _*) => MergeStrategy.last
  case PathList("com", "google", xs @ _*) => MergeStrategy.last
  case PathList("com", "esotericsoftware", xs @ _*) => MergeStrategy.last
  case PathList("com", "codahale", xs @ _*) => MergeStrategy.last
  case PathList("com", "yammer", xs @ _*) => MergeStrategy.last
  case "about.html" => MergeStrategy.rename
  case "META-INF/ECLIPSEF.RSA" => MergeStrategy.last
  case "META-INF/mailcap" => MergeStrategy.last
  case "META-INF/mimetypes.default" => MergeStrategy.last
  case "plugin.properties" => MergeStrategy.last
  case "log4j.properties" => MergeStrategy.last
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

resolvers ++= Seq(
  "Akka Repository" at "http://repo.akka.io/releases/",
  "Maven Central Server" at "http://repo1.maven.org/maven2",
  "Cloudera" at "https://repository.cloudera.com/artifactory/cloudera-repos",
  "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/"
)