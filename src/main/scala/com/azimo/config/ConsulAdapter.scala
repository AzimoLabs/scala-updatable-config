package com.azimo.config

import java.util

import com.azimo.config.UpdatableConfiguration.ValuePath
import com.azimo.config.definitions.AzimoApplicationConfig._
import com.azimo.config.definitions.{ConsulConfiguration, ServiceAddress}
import com.google.common.net.HostAndPort
import com.orbitz.consul.model.agent.Registration
import com.orbitz.consul.model.catalog.CatalogService
import com.orbitz.consul.model.kv.Value
import com.orbitz.consul.model.session.{ImmutableSession, SessionCreatedResponse}
import com.orbitz.consul.{Consul, KeyValueClient, SessionClient}
import com.typesafe.scalalogging.LazyLogging
import java.util.UUID

import scala.collection.mutable
import scala.util.Try

class ConsulAdapter(consulConfiguration: ConsulConfiguration) extends ValueGetter with LazyLogging {

  import scala.collection.JavaConverters._

  private val consul = Try(
    Consul
      .builder()
      .withHostAndPort(HostAndPort.fromParts(consulConfiguration.host.value, consulConfiguration.port.value))
      .build()
  ).toOption

  private val sessionClient: Option[SessionClient] = consul.map(_.sessionClient())
  private val kvClient: Option[KeyValueClient] = consul.map(_.keyValueClient())

  def registerService(
                       serviceId: String,
                       service: String,
                       address: String,
                       port: Int,
                       httpCheck: String,
                       healthcheckIntervalSeconds: Int = 30
                     ): Unit = {
    consul.foreach(
      _.agentClient().register(port, Registration.RegCheck.http(httpCheck, healthcheckIntervalSeconds), service, serviceId, service)
    )
  }

  def serviceAddress(serviceName: String): Option[CatalogService] = {
    consul.flatMap {
      c =>
        val response = c.catalogClient().getService(serviceName).getResponse
        response.asScala.headOption
    }
  }

  def servicesAddress(serviceName: String): Option[mutable.Buffer[CatalogService]] = {
    consul.flatMap {
      c =>
        val response: util.List[CatalogService] = c.catalogClient().getService(serviceName).getResponse
        Option(response.asScala)
    }
  }

  def serviceAddressByTag(tag: String): Option[ServiceAddress] = {
    consul.flatMap {
      c =>
        val response = c.catalogClient().getServices.getResponse
        val serviceQuery = response.asScala.find(_._2.contains(tag)).map {
          case (serviceId, _) => c.catalogClient().getService(serviceId).getResponse
        }
        serviceQuery
          .flatMap(_.asScala.headOption)
          .map(catalogService => ServiceAddress(catalogService.getServiceAddress, catalogService.getServicePort))
    }
  }

  /**
    * Add value for key.
    *
    * @param key   - key
    * @param value - value
    * @return - Option whether vault save value of not
    */
  def putValue(key: String, value: String): Option[Boolean] = {
    kvClient.flatMap(kvClient => Option(kvClient.putValue(key, value)))
  }

  /**
    * Try to find value for provided key
    *
    * @param key - key to be found
    * @return - Option with value
    */
  def getValueForKey(key: String): Option[Value] = {
    kvClient.map(kvClient => kvClient.getValue(key)).flatMap(e => Option(e.orElse(null)))
  }

  /**
    * Try to remove key
    *
    * @param key - key to be removed
    */
  def deleteFromKv(key: String): Unit = {
    kvClient.foreach(kvClient => kvClient.deleteKey(key))
  }


  /**
    * Release lock on resource and destroy session if needed
    *
    * @param kvKey          - key on which lock should be released (should be lowercase)
    * @param sessionId      - already created session id
    * @param destroySession - boolean which determines whether session should be destroyed after key lock released or not
    */
  def unlockResources(kvKey: String, sessionId: String, destroySession: Boolean = false): Unit = {
    val sessionInfo = sessionClient.flatMap(e => Option(e.getSessionInfo(sessionId).orElse(null)))
    if (sessionInfo.isDefined) {
      kvClient.map(e => e.releaseLock(kvKey.toLowerCase, sessionInfo.get.getId))
      if (destroySession) {
        sessionClient.get.destroySession(sessionInfo.get.getId)
        logger.info(s"Destroyed session with id $sessionId")
      }
      logger.info(s"Lock released on key ${kvKey.toLowerCase}")

    }
    else {
      logger.info(s"Can't find session with id $sessionId so can't release lock on key ${kvKey.toLowerCase}")
    }
  }

  /**
    * Try to create session with given name
    *
    * @return SessionCreateResponse
    */
  def createSession(): Option[SessionCreatedResponse] = {
    sessionClient.map(client => client.createSession(ImmutableSession.builder.name("session_" + UUID.randomUUID.toString).build))
  }

  /** *
    * Destroy session with provided id
    *
    * @param sessionId - id of session to be destroyed
    */
  def destroySession(sessionId: String): Unit = {
    sessionClient.foreach(client => client.destroySession(sessionId))
  }

  /**
    * Lock KeyValue resource
    *
    * @param kvKey     - key on which lock will be requested (should be lowercase)
    * @param sessionId - session id on which lock will be acquired
    * @return Option with result of lock acquisition
    */
  def lockResources(kvKey: String, sessionId: String): Option[Boolean] = {
    val sessionInfo = sessionClient.flatMap(e => Option(e.getSessionInfo(sessionId).orElse(null)))
    if (sessionInfo.isDefined) {
      kvClient.map(kvClient => kvClient.acquireLock(kvKey.toLowerCase, sessionId))
    } else {
      logger.info(s"Can't find session with id $sessionId so can't lock key ${kvKey.toLowerCase}")
      None
    }
  }

  /**
    * Release lock from resource
    *
    * @param key     - key which should be unlocked (should be lowercase)
    * @param session - session id on which lock will be released
    */
  def releaseLockResources(key: String, session: String): Unit = {
    kvClient.flatMap(kvClient => Option(kvClient.releaseLock(key, session)))
  }

  /**
    * Get value from absolute path
    *
    * @param path - absolute path
    * @return Option with value from provided path if exists
    */
  override def getValue(path: ValuePath): Option[String] = {
    val value = kvClient.flatMap(kvClient => Option(kvClient.getValue(camelToREST(path.path.mkString("/"))).orElse(null)))
    value.flatMap(v => Option(v.getValueAsString.orElse(null)))
  }

  override def getCredentials(path: ValuePath): None.type = None
}
