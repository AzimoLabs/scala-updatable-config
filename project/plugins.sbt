import sbt.Level
logLevel := Level.Warn
addSbtPlugin("no.arktekk.sbt" % "aether-deploy" % "0.21")
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.7")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.0")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.0-M1")