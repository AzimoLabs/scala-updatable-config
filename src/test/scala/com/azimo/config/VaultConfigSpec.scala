package com.azimo.config

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{ContentTypes, HttpMethods, HttpRequest}
import akka.stream.ActorMaterializer
import com.azimo.config.definitions.AzimoApplicationConfig.Credentials
import com.azimo.config.definitions.VaultConfiguration
import eu.timepit.refined._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.Try

class VaultConfigSpec extends BaseSpec with VaultProvider {
  implicit val actorSystem: ActorSystem = ActorSystem("test")
  implicit val executionContext: ExecutionContext = actorSystem.dispatcher
  implicit val mat = ActorMaterializer.create(actorSystem)

  "Configuration" should "be updated from vault" in {
    val promise = Promise[String]
    val conf = UpdatableConfiguration[Configuration]
    val vaultAdapter = VaultAdapter.defaultVaultAdapter(
      VaultConfiguration(refineMV("localhost"), refineMV(8200), "test"), Some(process.getConfig.getRootTokenID)
    )
    storeValues(vaultAdapter)
    mountDb()
    val updated = conf
      .checkForUpdates(List(), vaultAdapter, path => {
        if (path == List("testrole")) {
          promise.complete(Try(path.mkString("/")))
        }
      })(
        Configuration("a", 1, Credentials("a", "b"))
      )
    updated.v1 should equal("b")
    updated.v2 should equal(2)
    updated.testrole should not equal Credentials("a", "b")
    promise should be ('completed)
    Thread.sleep(11000) //wait for token to expire to check lease renewal
    import java.sql.DriverManager
    val conn = DriverManager.getConnection(s"jdbc:postgresql://localhost:5432/testdb?sslmode=disable", updated.testrole.username, updated.testrole.password)
    conn.createStatement.execute("select * from pg_tables;")

  }

  "Configuration" should "be updated from vault with lease promise failure after lease exiration/removal" in {
    val promise = Promise[String]
    val conf = UpdatableConfiguration[Configuration]
    val vaultAdapter = VaultAdapter.defaultVaultAdapter(
      VaultConfiguration(refineMV("localhost"), refineMV(8200), "test"), Some(process.getConfig.getRootTokenID)
    )
    storeValues(vaultAdapter)
    mountDb()
    val updated = conf
      .checkForUpdates(List(), vaultAdapter, path => {
        if (path == List("testrole")) {
          promise.complete(Try(path.mkString("/")))
        }
      })(
        Configuration("a", 1, Credentials("a", "b"))
      )

    updated.testrole should not equal Credentials("a", "b")

    vaultAdapter.vault.get.leases().revoke(updated.testrole.lease.get.id)
    Thread.sleep(11000) // wait for scheduler to catchup

    updated.testrole.lease.get.failureSignal.future.futureValue match {
      case _: VaultLeaseTimeFinished => succeed
      case _ => fail() // ?
    }
  }

  private def storeValues(vaultAdapter: VaultAdapter) = {
    val vaultValues = Map[String, AnyRef]("v-1" -> "b", "v2" -> "2")
    val response = vaultAdapter.vault.get.logical().write("secret/test", vaultValues.asJava)
    response.getRestResponse.getStatus should equal(204)
  }

  private def mountDb(): Unit = {
    val r1 = Http().singleRequest(HttpRequest(
      uri = "http://localhost:8200/v1/database/config/test",
      method = HttpMethods.POST
    ).withHeaders(
      RawHeader("X-Vault-Token", process.getConfig.getRootTokenID)
    )
      .withEntity(ContentTypes.`application/json`,
        s"""
           |{
           |  "plugin_name": "postgresql-database-plugin",
           |  "allowed_roles": "testrole",
           |  "connection_url": "postgresql://root:pass@localhost:5432/testdb?sslmode=disable"
           |}
         """.stripMargin)).futureValue

    println(r1)
    r1.status.isSuccess() should be(true)

    val creationStatements = "CREATE ROLE \\\"{{name}}\\\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}' ROLE root; GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO \\\"{{name}}\\\"; GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO \\\"{{name}}\\\";"

    val revocationStatements = "REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM \"{{name}}\"; REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM \"{{name}}\"; REVOKE USAGE ON SCHEMA public FROM \"{{name}}\"; REASSIGN OWNED BY \"{{name}}\" TO root; DROP ROLE IF EXISTS \"{{name}}\";"

    val r2 = Http().singleRequest(HttpRequest(
      uri = "http://localhost:8200/v1/database/roles/testrole",
      method = HttpMethods.POST
    ).withHeaders(
      RawHeader("X-Vault-Token", process.getConfig.getRootTokenID)
    )
      .withEntity(ContentTypes.`application/json`,
        s"""
           |{
           |  "db_name": "test",
           |  "creation_statements": "$creationStatements",
           |  "default_ttl": "10s",
           |  "max_ttl": "24h"
           |}
         """.stripMargin)).futureValue

    println(r2)
    r2.status.isSuccess() should be(true)

    //    val r3 =
  }

  case class Configuration(
                            v1: String,
                            v2: Int,
                            testrole: Credentials
                          )

}
