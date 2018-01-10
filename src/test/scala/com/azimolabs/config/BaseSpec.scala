package com.azimolabs.config

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

abstract class BaseSpec extends FlatSpec with BeforeAndAfterAll with ScalaFutures with Matchers {

  implicit override val patienceConfig = PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(5, Millis)))

}
