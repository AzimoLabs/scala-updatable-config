package com.azimolabs.config

import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string._
import cats.instances.all.catsKernelStdMonoidForString
import eu.timepit.refined.auto._

import scala.concurrent.Promise


class ConfigSpec extends BaseSpec {

  "Configuration object" should "be updated from kv store notifying about updates" in {

    val conf = UpdatableConfiguration[Configuration]

    val promise = Promise[String]

    val updates = Map[String, String](
      "prefix/v1" -> "updatedValue",
      "prefix/v2" -> "2",
      "prefix/v3" -> "true",
      "prefix/v4" -> "2",
      "prefix/v5/test" -> "test123",
      "prefix/v6" -> "test"
    )

    val getter = new ValueGetter {
      override def getValue(path: UpdatableConfiguration.ValuePath): Option[String] = updates.get(path.path.mkString("/"))
    }

    val updated = conf.checkForUpdates(List("prefix"), getter, path => if (!promise.isCompleted) promise.success(path.mkString("/")))(
      Configuration(
        v1 = "value",
        v2 = 0,
        v3 = false,
        v4 = 1L,
        v5 = CompositeConfiguration("test"),
        v6 = None
      )
    )
    updated should be(Configuration(
      v1 = "updatedValue",
      v2 = 2,
      v3 = true,
      v4 = 2L,
      v5 = CompositeConfiguration("test123"),
      v6 = Some("test")
    ))

    promise should be('completed)

  }

  case class Configuration(
                            v1: String,
                            v2: Int,
                            v3: Boolean,
                            v4: Long,
                            v5: CompositeConfiguration,
                            v6: Option[String]
                          )

  case class CompositeConfiguration(
                                     test: String Refined StartsWith[W.`"t"`.T]
                                   )

}


