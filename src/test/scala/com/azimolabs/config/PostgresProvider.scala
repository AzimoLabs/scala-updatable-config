package com.azimolabs.config

import org.scalatest.{BeforeAndAfterAll, Suite}
import ru.yandex.qatools.embed.postgresql.EmbeddedPostgres
import ru.yandex.qatools.embed.postgresql.distribution.Version

trait PostgresProvider extends BeforeAndAfterAll {
  self: Suite =>

  val postgres = new EmbeddedPostgres(Version.V9_6_3)


  override protected def beforeAll(): Unit = {
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    postgres.stop()
  }

}
