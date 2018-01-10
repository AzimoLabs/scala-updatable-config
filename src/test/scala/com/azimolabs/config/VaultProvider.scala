package com.azimolabs.config

import org.scalatest.{BeforeAndAfterAll, Suite}

trait VaultProvider extends BeforeAndAfterAll with PostgresProvider {
  self: Suite =>

  import com.github.golovnin.embedded.vault.{VaultServerConfig, VaultServerExecutable, VaultServerProcess, VaultServerStarter}

  def maxLeaseTime = "60s"

  val config: VaultServerConfig = new VaultServerConfig.Builder()
    .version(() => "0.7.3")
    .listenerHost("localhost")
    .listenerPort(8200)
    .maxLeaseTTL(maxLeaseTime)
    .defaultLeaseTTL(maxLeaseTime)
    .build
  val starter: VaultServerStarter = VaultServerStarter.getDefaultInstance
  val executable: VaultServerExecutable = starter.prepare(config)
  val process: VaultServerProcess = executable.start
  postgres.start("localhost", 5432, "testdb", "root", "pass")

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    import sys.process._
    Process(s"${executable.getFile.executable().getAbsolutePath} mount database", None, "VAULT_ADDR" -> "http://localhost:8200").!
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    process.stop()
  }

}
