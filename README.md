# Updatable Scala Config

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.azimo/scala-updatable-config_2.12/badge.svg)](https://search.maven.org/#artifactdetails%7Ccom.azimo%7Cscala-updatable-config_2.12%7C0.1.0%7Cjar)


Library based on pure-config for get updates from Consul or Vault backend.


## Usage

Usage:
1. Define your configuration in application.conf as usual
2. Prepare ADT representation for the configuration (case classes)
3. In your code use the following to instantiate configuration:

```yaml
azimo {

  cfg {
    use-only-file = false
  }

  consul {
    host = "consul.host"
    host = ${?CONSUL_IP4_ADDR}
    port = 8500
    port = ${?CONSUL_PORT}
  }

  vault {
    host = "vault.host"
    host = ${?VAULT_IP4_ADDR}
    port = 8200
    port = ${?VAULT_PORT}
    service-name: "SERVICE-NAME"
  }
  
  externalservice {
    address: "localhost"
    endpoint: "/endpoint"
    port: 8000
  }
}
```

```scala
import akka.actor.ActorSystem
import com.azimo.config.definitions.AzimoApplicationConfig._
import com.azimo.config.definitions.{ConsulConfiguration, VaultConfiguration}
import pureconfig._
import eu.timepit.refined._
import eu.timepit.refined.pureconfig._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import eu.timepit.refined.string.{MatchesRegex, Uri}
import cats.instances.all._
import com.azimo.config.{ConsulAdapter, UpdatableConfiguration, VaultAdapter}
import scala.concurrent.ExecutionContext
import scala.util.Success

object ApplicationConfig {

  implicit val actorSystem: ActorSystem = ActorSystem.create()
  implicit val executionContext: ExecutionContext = actorSystem.dispatcher


  private val updatableConfig = UpdatableConfiguration[ApplicationConfig]

  private val fileConfiguration: ApplicationConfig = loadConfig[ApplicationConfig]("azimo") match {
    case Left(err) => throw new RuntimeException(err.toString)
    case Right(conf) => conf
  }

  private val vaultConfigGetter: VaultAdapter = VaultAdapter.defaultVaultAdapter(fileConfiguration.vault)

  private def vaultConfiguration = updatableConfig.checkForUpdates(List(""), vaultConfigGetter, _ => ())(fileConfiguration)

  def consul = new ConsulAdapter(fileConfiguration.consul)

  val configuration: ApplicationConfig = if (fileConfiguration.cfg.useOnlyFile) {
    fileConfiguration
  } else {
    updatableConfig.checkForUpdates(List("example-project-group", "service-name"), consul, _ => ())(fileConfiguration)
  }
  
  // If you want to get e.g. credentials for PostgreSQL:
  private val postgresCredentials = vaultConfigGetter.getCredentials(UpdatableConfiguration.ValuePath(path = List("credentials_path_here")))
  val postgresUsername: String = postgresCredentials.map(_.username).getOrElse(configuration.db.default.user)
  val postgresPassword: String = postgresCredentials.map(_.password).getOrElse(configuration.db.default.password)
  
  // If you want to get information about external service from consul
  def haproxy: Option[(String, Int)] = {
    consul.serviceAddress("haproxy").map(c => c.getServiceAddress -> c.getServicePort)
  } 
  
  def externalService(): ExternalServiceConfiguration = {
    haproxy.map(item => configuration.externalservice.copy(
      address = item._1,
      port = item._2,
    )).getOrElse(configuration.externalservice)
  } 
}


case class ApplicationConfig(
                              cfg: ConfigUsage,
                              consul: ConsulConfiguration,
                              vault: VaultConfiguration,
                              db: DatabaseConfig,
                              externalservice: ExternalServiceConfiguration,
                              externalservice2: ExternalServiceConfiguration
                            )

case class ConfigUsage(useOnlyFile: Boolean = false)

case class DatabaseConfig(
                           default: DefaultDatabaseConfig
                         )

case class DefaultDatabaseConfig(
                                  user: String,
                                  password: String,
                                  url: URI,
                                  poolInitialSize: Int,
                                  poolMaxSize: Int,
                                  poolConnectionTimeoutMillis: Int,
                                  poolValidationQuery: String,
                                  poolFactoryName: String,
                                  driver: String,
                                )
                                
case class ExternalServiceConfiguration(
                      address: String,
                      endpoint: String,
                      port: Int
                    )

```


## Tricks

* Every run of `checkForUpdates` function returns new updated config object.
* Callback parameter to `checkForUpdates` function allows you to react to changes in configuration, the parameter to callback is the path to a changed configuration value i.e. `List("azimo", "kafka", "bootstrapServers")`.
* The first parameter to `checkForUpdates` function is the prefix for searching in kv store for new values.
* At the moment the base names of parameters work alongside with "REST" names, i.e. both `bootstrapServers` and `bootstrap-servers` work (the second version takes priority). This can be reimplemented in `ValueGetter` function.

## TODO

- support coproducts

## Download

### Library dependency

```sbtshell
"com.azimo" %% "scala-updatable-config" % "0.1.1"
```

## License

    Copyright (C) 2016 Azimo

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.


# Towards financial services available to all
We’re working throughout the company to create faster, cheaper, and more available financial services all over the world, and here are some of the techniques that we’re utilizing. There’s still a long way ahead of us, and if you’d like to be part of that journey, check out our [careers page](bit.ly/3vajnu6).
