/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.impl.local

import slamdata.Predef.{Seq => _, _}

import quasar.api.datasource.DatasourceType
import quasar.api.resource._
import quasar.connector._
import quasar.connector.ResourceError._
import quasar.connector.datasource._
import quasar.contrib.fs2.convert
import quasar.qscript.InterpretedRead

import scala.Predef.classOf

import java.nio.file.{Files, NoSuchFileException, Path => JPath}
import java.nio.file.attribute.BasicFileAttributes

import cats.data.NonEmptyList
import cats.effect.{ContextShift, Effect, Resource, Timer}
import fs2.Stream
import scalaz.{OptionT, Scalaz}, Scalaz._

import shims.monadToScalaz

/** A Datasource backed by the underlying filesystem local to Quasar.
  *
  * TODO: Currently only supports reading line-delimited JSON files (with
  *       support for the "precise" `Data` codec). Expand this via a pluggable
  *       set of decoders for CSV, XML, avro, etc.
  *
  * @param root the scope of this datasource, all paths will be considered relative to this one.
  * @param queryResult the evaluation strategy
  */
final class EvaluableLocalDatasource[F[_]: ContextShift: Timer] private (
    dsType: DatasourceType,
    root: JPath,
    queryResult: InterpretedRead[JPath] => QueryResult[F])(
    implicit F: Effect[F], RE: MonadResourceErr[F])
    extends DatasourceModule.DS[F] {

  val kind: DatasourceType = dsType

  val loaders: NonEmptyList[Loader[Resource[F, ?], InterpretedRead[ResourcePath], QueryResult[F]]] =
    NonEmptyList.of(Loader.Batch(BatchLoader.Full { (ir: InterpretedRead[ResourcePath]) =>
      Resource.eval(for {
        (jp, _) <- attributesOf(ir.path).getOrElseF(RE.raiseError(pathNotFound(ir.path)))
        candidate <- isCandidate(jp)
        _ <- candidate.unlessM(RE.raiseError(notAResource(ir.path)))
      } yield queryResult(InterpretedRead(jp, ir.stages)))
    }))

  def pathIsResource(path: ResourcePath): Resource[F, Boolean] =
    Resource.eval(
      resolvedResourcePath[F](root, path)
        .flatMap(_.fold(false.pure[F])(isCandidate)))

  def prefixedChildPaths(path: ResourcePath): Resource[F, Option[Stream[F, (ResourceName, ResourcePathType.Physical)]]] = {
    def withType(jp: JPath): F[(ResourceName, ResourcePathType.Physical)] =
      isCandidate(jp)
        .map(_.fold(ResourcePathType.leafResource, ResourcePathType.prefix))
        .strengthL(toResourceName(jp))

    val res = attributesOf(path) map {
      case (jp, attrs) if attrs.isDirectory =>
        convert.fromJavaStream(F.delay(Files.list(jp))).evalMap(withType)

      case _ => Stream.empty
    }

    Resource.eval(res.run)
  }

  ////

  private def attributesOf(resourcePath: ResourcePath): OptionT[F, (JPath, BasicFileAttributes)] =
    for {
      resolved <- OptionT(resolvedResourcePath[F](root, resourcePath))

      attrs <- OptionT(F.recover(F.delay(some(Files.readAttributes(resolved, classOf[BasicFileAttributes])))) {
        case _: NoSuchFileException => none
      })
    } yield (resolved, attrs)

  private def isCandidate(jp: JPath): F[Boolean] =
    F.delay(Files.isRegularFile(jp) && !Files.isHidden(jp))

  @SuppressWarnings(Array("org.wartremover.warts.ToString"))
  private def toResourceName(jp: JPath): ResourceName =
    ResourceName(jp.getFileName.toString)
}

object EvaluableLocalDatasource {
  def apply[F[_]: ContextShift: Effect: MonadResourceErr: Timer](
      dsType: DatasourceType,
      root: JPath)(
      queryResult: InterpretedRead[JPath] => QueryResult[F])
      : DatasourceModule.DS[F] =
    new EvaluableLocalDatasource[F](dsType, root, queryResult)
}
