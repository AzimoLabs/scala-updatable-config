package com.azimo.config

import cats.Monoid
import com.azimo.config.UpdatableConfiguration.ValuePath
import com.azimo.config.definitions.AzimoApplicationConfig.Credentials
import com.typesafe.scalalogging.LazyLogging
import eu.timepit.refined.api.{RefType, Validate}
import eu.timepit.refined.auto._
import shapeless.labelled.FieldType
import shapeless.{::, HList, HNil, LabelledGeneric, Lazy, Witness, labelled}

import scala.util.Try

trait ValueGetter {
  def getValue(path: ValuePath): Option[String]

  def getCredentials(path: ValuePath): Option[Credentials] = None
}

trait UpdatableConfiguration[T] {

  /**
    * Updates the configuration based on values received from valueGetter parameter.
    *
    * @param path         allows to set up prefix for path of a value used referenced by valueGetter.
    * @param valueGetter  function returning whether there is a value to fetch at the path or not.
    * @param callback     called when a value changed.
    * @param currentValue base value which is compared when checking if the configuration should be updated or not.
    * @return new configuration value.
    */
  def checkForUpdates(path: List[String], valueGetter: ValueGetter, callback: List[String] => Unit)(currentValue: T): T

}

/**
  * Provide all implicit conversions from possible values in consul
  */
object UpdatableConfiguration extends LazyLogging {

  /**
    * ValuePath for key in Consul or Vault
    * @param path - path in form of list, e.g. path "/one/two/three" should be passed as List("one", "two", "three")
    */
  case class ValuePath(path: List[String])

  private implicit def listToValuePath(p: List[String]): ValuePath = ValuePath(p)

  def apply[T](implicit consulConfig: UpdatableConfiguration[T]): UpdatableConfiguration[T] = consulConfig

  implicit val strConfig: UpdatableConfiguration[String] = new UpdatableConfiguration[String] {

    override def checkForUpdates(
                                  basePath: List[String],
                                  valueGetter: ValueGetter,
                                  callback: (List[String]) => Unit
                                )(
                                  currentValue: String
                                ): String = {
      valueGetter.getValue(basePath).filter(_ != currentValue).map {
        newValue =>
          callback(basePath)
          newValue
      }.getOrElse(currentValue)
    }
  }

  implicit val intConfig: UpdatableConfiguration[Int] = new UpdatableConfiguration[Int] {

    override def checkForUpdates(
                                  basePath: List[String],
                                  valueGetter: ValueGetter,
                                  callback: (List[String]) => Unit
                                )(
                                  currentValue: Int
                                ): Int = {
      valueGetter.getValue(basePath).flatMap(v => Try(v.toInt).toOption).filter(_ != currentValue).map {
        newValue =>
          callback(basePath)
          newValue
      }.getOrElse(currentValue)
    }
  }

  implicit val longConfig: UpdatableConfiguration[Long] = new UpdatableConfiguration[Long] {

    override def checkForUpdates(
                                  basePath: List[String],
                                  valueGetter: ValueGetter,
                                  callback: (List[String]) => Unit
                                )(
                                  currentValue: Long
                                ): Long = {
      valueGetter.getValue(basePath).flatMap(v => Try(v.toLong).toOption).filter(_ != currentValue).map {
        newValue =>
          callback(basePath)
          newValue
      }.getOrElse(currentValue)
    }
  }

  implicit val boolConfig: UpdatableConfiguration[Boolean] = new UpdatableConfiguration[Boolean] {

    override def checkForUpdates(
                                  basePath: List[String],
                                  valueGetter: ValueGetter,
                                  callback: (List[String]) => Unit
                                )(
                                  currentValue: Boolean
                                ): Boolean = {
      valueGetter.getValue(basePath).flatMap(v => Try(v.toBoolean).toOption).filter(_ != currentValue).map {
        newValue =>
          callback(basePath)
          newValue
      }.getOrElse(currentValue)
    }
  }

  implicit def optionConfig[T](
                                implicit tConf: UpdatableConfiguration[T], monoid: Lazy[Monoid[T]]
                              ): UpdatableConfiguration[Option[T]] = new UpdatableConfiguration[Option[T]] {

    override def checkForUpdates(
                                  path: List[String],
                                  valueGetter: ValueGetter,
                                  callback: (List[String]) => Unit
                                )(
                                  currentValue: Option[T]
                                ): Option[T] = {
      currentValue match {
        case Some(v) => Some(tConf.checkForUpdates(path, valueGetter, callback)(v))
        case None =>
          val newValue = valueGetter.getValue(path)
          newValue match {
            case None => None
            case Some(_) => Some(tConf.checkForUpdates(path, valueGetter, callback)(monoid.value.empty))
          }
      }
    }
  }

  implicit def credentialsConfig: UpdatableConfiguration[Credentials] = new UpdatableConfiguration[Credentials] {
    override def checkForUpdates(
                                  path: List[String],
                                  valueGetter: ValueGetter,
                                  callback: List[String] => Unit
                                )(
                                  currentValue: Credentials
                                ): Credentials = {
      valueGetter.getCredentials(path)
        .map {
          newCredentials =>
            callback(path)
            newCredentials
        }
        .getOrElse(currentValue)
    }
  }

  implicit def productConfig[P, H <: HList](
                                             implicit
                                             generic: LabelledGeneric.Aux[P, H],
                                             hConfig: Lazy[UpdatableConfiguration[H]]
                                           ): UpdatableConfiguration[P] = new UpdatableConfiguration[P] {
    override def checkForUpdates(basePath: List[String], valueGetter: ValueGetter, callback: (List[String]) => Unit)(currentValue: P): P = {
      val newVal = hConfig.value.checkForUpdates(basePath, valueGetter, callback)(generic.to(currentValue))
      generic.from(newVal)
    }
  }

  implicit val hnilConfig: UpdatableConfiguration[HNil] = new UpdatableConfiguration[HNil] {
    override def checkForUpdates(basePath: List[String], valueGetter: ValueGetter, callback: (List[String]) => Unit)(currentValue: HNil): HNil = {
      HNil
    }
  }

  implicit def hlistConfig[K <: Symbol, H, T <: HList](
                                                        implicit
                                                        witness: Witness.Aux[K],
                                                        hConfig: Lazy[UpdatableConfiguration[H]],
                                                        tConfig: UpdatableConfiguration[T]
                                                      ): UpdatableConfiguration[FieldType[K, H] :: T] = new UpdatableConfiguration[FieldType[K, H] :: T] {

    override def checkForUpdates(basePath: List[String], valueGetter: ValueGetter, callback: (List[String]) => Unit)(currentValue: FieldType[K, H] :: T): FieldType[K, H] :: T = {
      val head = hConfig.value.checkForUpdates(basePath ::: List(witness.value.name), valueGetter, callback)(currentValue.head)
      val tail = tConfig.checkForUpdates(basePath, valueGetter, callback)(currentValue.tail)
      labelled.field[K](head) :: tail
    }
  }

  implicit def refTypeConfigConvert[F[_, _], T, P](
                                                    implicit
                                                    conf: UpdatableConfiguration[T],
                                                    refType: RefType[F],
                                                    validate: Validate[T, P]
                                                  ): UpdatableConfiguration[F[T, P]] = new UpdatableConfiguration[F[T, P]] {

    override def checkForUpdates(basePath: List[String], valueGetter: ValueGetter, callback: (List[String]) => Unit)(currentValue: F[T, P]): F[T, P] = {
      val newValue = conf.checkForUpdates(basePath, valueGetter, callback)(currentValue)
      refType.refine(newValue) match {
        case Left(str) =>
          logger.error(s"value $newValue at $basePath wrong in consul because: $str")
          currentValue
        case Right(res) => res
      }
    }
  }

  implicit def mapConfigConvert[V, M <: Map[String, V]](
                                                         implicit
                                                         conf: UpdatableConfiguration[V]
                                                       ): UpdatableConfiguration[Map[String, V]] = new UpdatableConfiguration[Map[String, V]] {

    override def checkForUpdates(basePath: List[String], valueGetter: ValueGetter, callback: (List[String]) => Unit)(currentValue: Map[String, V]): Map[String, V] = {
      currentValue.map {
        case (k, v) => k -> conf.checkForUpdates(basePath ::: List(k), valueGetter, callback)(v)
      }
    }
  }
}