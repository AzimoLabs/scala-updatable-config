package com.azimo.config.definitions

import com.azimo.config.Lease
import eu.timepit.refined.W
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Greater
import eu.timepit.refined.string.Uri

import scala.util.matching.Regex

object AzimoApplicationConfig {

  type URI = String Refined Uri

  type Port = Int Refined Greater[W.`1024`.T]

  val camelToRESTRegex: Regex = "[A-Z\\d]".r

  def camelToREST(name: String) = {
    camelToRESTRegex.replaceAllIn(name, { m => "-" + m.group(0).toLowerCase() })
  }

  case class Credentials(
                          username: String,
                          password: String,
                          lease: Option[Lease] = None
                        )

}
