resolvers ++= Seq(
  "Akka Repository" at "http://repo.akka.io/releases/",
  Resolver.sonatypeRepo("public"),
  Resolver.bintrayRepo("reactivehub", "maven"),
  Resolver.bintrayRepo("lonelyplanet", "maven"),
  Resolver.jcenterRepo,
  DefaultMavenRepository
)

lazy val loggingDependencies = Seq(
  "ch.qos.logback" % "logback-classic" % "1.1.8" % "optional",
  "ch.qos.logback" % "logback-core" % "1.1.8" % "optional",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0" % "optional",
  "net.logstash.logback" % "logstash-logback-encoder" % "4.8" % "optional"
)

lazy val configDependencies = Seq(
  "com.github.pureconfig" %% "pureconfig" % "0.8.0",
  "eu.timepit" %% "refined" % "0.8.4",
  "eu.timepit" %% "refined-pureconfig" % "0.8.5",
  "eu.timepit" %% "refined-scalacheck" % "0.8.5",
  "eu.timepit" %% "refined-scalaz" % "0.8.5",
  "eu.timepit" %% "refined-scodec" % "0.8.5",
  "com.orbitz.consul" % "consul-client" % "1.0.0",
  "com.bettercloud" % "vault-java-driver" % "3.0.0",
  "org.typelevel" %% "cats" % "0.9.0",
  "com.typesafe.akka" %% "akka-actor" % "2.5.4"
)

lazy val testDependencies = Seq(
  "org.scalatest" %% "scalatest" % "3.0.1" % "test",
  "org.scalacheck" %% "scalacheck" % "1.13.4" % "test",
  "com.github.golovnin" % "embedded-vault" % "0.7.3.0" % "test",
  "org.postgresql" % "postgresql" % "42.1.4" % "test",
  "ru.yandex.qatools.embed" % "postgresql-embedded" % "2.4" % "test",
  "com.typesafe.akka" %% "akka-http" % "10.0.10" % "test"
)

libraryDependencies ++= loggingDependencies ++ configDependencies ++ testDependencies