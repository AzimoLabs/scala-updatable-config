package com.azimo.config

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.azimo.config.definitions.AzimoApplicationConfig._
import com.azimo.config.definitions.VaultConfiguration
import com.bettercloud.vault.api.Logical
import com.bettercloud.vault.response.LogicalResponse
import com.bettercloud.vault.{Vault, VaultConfig}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Promise}
import scala.util.Try

case class Lease(id: String, duration: Long, failureSignal: Promise[VaultLeaseTimeFinished])

case class VaultLeaseTimeFinished(message: String = "Vault cannot extend lease time anymore", exception: Throwable)

case class LeaseRenewalMessage(vault: Vault, lease: Lease)

case class TokenRenewalMessage(vault: Vault)

/**
  * Vault adapter for fetching configuration data from vault.
  *
  * @param vaultConfig basic vault configuration.
  * @param pathsToTry  List of paths to try for a config value in form of functions, i.e.
  *                    having config definition like:
  *                    ```
  *                    case class Config(vC1: String, v2: CompositeConfig)
  *                    case class CompositeConfig(v3: String)
  *                    ```
  *                    the paths to resolve would be List("vC1") and List("v2","v3") - this is the argument passed to the function.
  *                    This allows to check in multiple "buckets" for the value with set priorities - if the value is found then
  *                    the remaining set of path checks are omitted.
  *                    (see defaultVaultAdapter implementation for details - in case of the path List("v1") it checks for value in:
  *                   - secret/service-name/v-c1
  *                   - secret/serviceName/vC1
  *                   - secret/application/v-c1
  *                   - secret/application/vC1).
  */
class VaultAdapter(
                    vaultConfig: VaultConfiguration,
                    pathsToTry: List[List[String] => String],
                    token: Option[String],
                    autoRenewLeases: Boolean = true
                  )(
                    implicit actorSystem: ActorSystem, executionContext: ExecutionContext
                  ) extends ValueGetter with LazyLogging {

  private val config: VaultConfig = new VaultConfig().address(s"http://${vaultConfig.host.value}:${vaultConfig.port.value}").build()

  val vault: Option[Vault] = token.map(secret => new Vault(config.token(secret).build()))

  private val logicalPath: Option[Logical] = vault.map(_.logical())

  private val leaseRenewalActor = actorSystem.actorOf(Props[LeaseRenewalActor])

  vault.map(v => if (autoRenewLeases) {
    VaultAdapter.scheduleTokenRenewal(v, leaseRenewalActor)
  })

  /**
    * Get all properties from folder / context
    *
    * @param path Full path to vault folder e.g. cassandra/creds/service-name-name
    * @return Some(Map) with parameters in context or None if no such folder exists
    */
  def getContextFromPath(
                          path: String
                        ): Option[mutable.Map[String, String]] = {
    logger.info(s"Trying to get context value from Vault -> $path")
    Try(logicalPath.map(_.read(path).getData.asScala).get).toOption
  }

  /**
    * Try to renew token for AppRole
    */
  def renewToken(): Unit = {
    logger.info("About to renew Vault AppRole token.")
    vault.map(_.auth().renewSelf)
    logger.info("Token renewal done!")
  }

  /**
    * Get value from provided path
    *
    * @param path
    * @return
    */
  override def getValue(
                         path: UpdatableConfiguration.ValuePath
                       ): Option[String] = {
    val result = pathsToTry.foldLeft(Option.empty[String]) {
      case (Some(value), _) => Some(value)
      case (None, pathFunc) =>
        checkPath(pathFunc(path.path), camelToREST(path.path.last)) match {
          case None => checkPath(pathFunc(path.path), path.path.last)
          case Some(value) => Some(value)
        }
    }
    result
  }

  /**
    * Try to find database credentials in path. If credentials exists and autoRenewLeases is true we try to lease.
    *
    * @param valuePath - ValuePath
    * @return Option with AzimoApplicationConfig.Credentials
    */
  override def getCredentials(valuePath: UpdatableConfiguration.ValuePath): Option[Credentials] = {
    val result = Try(logicalPath.map(_.read(s"database/creds/${valuePath.path.mkString("/")}")).get).toOption
    val tried = result match {
      case None => Try(logicalPath.map(_.read(s"database/creds/${camelToREST(valuePath.path.mkString("/"))}")).get).toOption
      case Some(v) => Some(v)
    }
    tried.foreach(_ => logger.info(s"Found value in Vault for credentials ${valuePath.path.mkString("/")}"))
    tried.map {
      logicalResponse =>
        val failureSignal = Promise[VaultLeaseTimeFinished]()
        val lease = handleLease(logicalResponse, failureSignal)
        Credentials(
          logicalResponse.getData.asScala("username"),
          logicalResponse.getData.asScala("password"),
          lease
        )
    }
  }

  private def handleLease(
                           logicalResponse: LogicalResponse,
                           failureSignal: Promise[VaultLeaseTimeFinished]
                         ): Option[Lease] = {
    if (logicalResponse.getRenewable) {
      val leaseOpt = Lease(logicalResponse.getLeaseId, logicalResponse.getLeaseDuration, failureSignal)
      if (autoRenewLeases) {
        VaultAdapter.scheduleLeaseRenewal(leaseOpt, leaseRenewalActor, vault.get)
      }
      Some(leaseOpt)
    } else {
      None
    }
  }

  private def checkPath(
                         keyPath: String,
                         valuePath: String
                       ): Option[String] = {
    logger.info(s"Trying path $keyPath/$valuePath")
    val data = Try(logicalPath.map(_.read(keyPath).getData.asScala(valuePath)).get).toOption
    data.foreach(_ => logger.info(s"Found value in Vault for path: $keyPath/$valuePath"))
    data
  }
}

object VaultAdapter {

  def defaultVaultAdapter(
                           vaultConfiguration: VaultConfiguration,
                           token: Option[String] = None
                         )(
                           implicit actorSystem: ActorSystem, executionContext: ExecutionContext
                         ): VaultAdapter = {

    val vaultServicePath = (path: List[String]) => (List("secret", vaultConfiguration.serviceName) ++ path).dropRight(1).mkString("/")
    val vaultAppPath = (path: List[String]) => (List("secret", "application") ++ path).dropRight(1).mkString("/")

    val pathsToTry: List[List[String] => String] = List(
      p => camelToREST(vaultServicePath(p)),
      vaultServicePath,
      p => camelToREST(vaultAppPath(p)),
      vaultAppPath
    )
    new VaultAdapter(vaultConfiguration, pathsToTry, token match {
      case None => sys.props.get("vault.secretId")
      case Some(t) => Some(t)
    })
  }

  private[config] def scheduleLeaseRenewal(
                                            lease: Lease,
                                            leaseRenewalActor: ActorRef,
                                            vault: Vault)(
                                            implicit executionContext: ExecutionContext, actorSystem: ActorSystem
                                          ) = {
    actorSystem.scheduler.scheduleOnce(
      (lease.duration seconds) / 2,
      leaseRenewalActor,
      LeaseRenewalMessage(vault, lease)
    )
  }

  private[config] def scheduleTokenRenewal(
                                            vault: Vault,
                                            leaseRenewalActor: ActorRef
                                          )(
                                            implicit executionContext: ExecutionContext, actorSystem: ActorSystem
                                          ) = {
    actorSystem.scheduler.scheduleOnce(
      10 seconds,
      leaseRenewalActor,
      TokenRenewalMessage(vault)
    )
  }
}


private[config] class LeaseRenewalActor extends Actor {

  implicit val executionContext: ExecutionContextExecutor = context.dispatcher
  implicit val actorSystem: ActorSystem = context.system

  def receive = {
    case LeaseRenewalMessage(vault, lease@Lease(id, duration, failureSignal)) =>
      val tryLease = Try {
        vault.leases().renew(id, duration)
        VaultAdapter.scheduleLeaseRenewal(lease, self, vault)
      }
      if (tryLease.isFailure) failureSignal success VaultLeaseTimeFinished(exception = tryLease.failed.get)

    case TokenRenewalMessage(vault) =>
      vault.auth().renewSelf()
      VaultAdapter.scheduleTokenRenewal(vault, self)
  }
}
