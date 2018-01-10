import sbt.Keys._

name := "scala-updatable-config"
organization := "com.azimolabs"

scalaVersion := "2.12.3"

homepage := Some(url("https://github.com/username/projectname"))

scmInfo := Some(
  ScmInfo(url("https://github.com/username/projectname"), "git@github.com:username/projectname.git")
)

developers := List(
  Developer("MiLebi", "MiLebi", "mlebida@gmail.com", url("https://github.com/MiLebi"))
)

licenses += ("The Apache Software License, Version 2.0", url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

publishMavenStyle := true

// Add sonatype repository settings
publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)

