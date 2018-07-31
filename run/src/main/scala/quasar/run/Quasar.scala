/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.run

import slamdata.Predef.Double

import quasar.api.QueryEvaluator
import quasar.api.datasource.{DatasourceRef, Datasources}
import quasar.common.PhaseResultTell
import quasar.contrib.pathy.ADir
import quasar.contrib.std.uuid._
import quasar.ejson.EJson
import quasar.fp.numeric.Positive
import quasar.impl.DatasourceModule
import quasar.impl.datasource.local.LocalDatasourceModule
import quasar.impl.datasources.{DatasourceManagement, DefaultDatasources}
import quasar.impl.evaluate.FederatingQueryEvaluator
import quasar.impl.external.{ExternalConfig, ExternalDatasources}
import quasar.impl.schema.SstConfig
import quasar.mimir.{MimirRepr, Precog}
import quasar.mimir.evaluate.MimirQueryFederation
import quasar.mimir.storage.{MimirIndexedStore, StoreKey}
import quasar.run.implicits._
import quasar.run.optics._

import java.util.UUID
import scala.concurrent.ExecutionContext

import argonaut.Json
import argonaut.JsonScalaz._
import cats.~>
import cats.effect.{ConcurrentEffect, IO, Timer}
import cats.syntax.flatMap._
import fs2.{Scheduler, Stream}
import matryoshka.data.Fix
import pathy.Path._
import scalaz.IMap
import scalaz.syntax.foldable._
import scalaz.syntax.functor._
import shims._
import spire.std.double._

final class Quasar[F[_]](
    val datasources: Datasources[F, Stream[F, ?], UUID, Json, SstConfig[Fix[EJson], Double]],
    val queryEvaluator: QueryEvaluator[F, SqlQuery, Stream[F, MimirRepr]])

object Quasar {
  // The location of the datasource refs tables within `mimir`.
  val DatasourceRefsLocation: ADir =
    rootDir </> dir("quasar") </> dir("datasource-refs")

  /** What it says on the tin.
    *
    * TODO: If we want to divest from `mimir` completely, we'll need to convert
    *       all the abstractions that use it into arguments to this constructor.
    *
    * @param precog Precog instance to use by Quasar
    * @param extConfig datasource plugin configuration
    * @param sstSampleSize the number of records to sample when generating SST schemas
    */
  def apply[F[_]: ConcurrentEffect: MonadQuasarErr: PhaseResultTell: Timer](
      precog: Precog,
      extConfig: ExternalConfig,
      sstSampleSize: Positive)(
      implicit ec: ExecutionContext)
      : Stream[F, Quasar[F]] = {

    for {
      extMods <- ExternalDatasources[F](extConfig)

      modules = extMods.insert(
        LocalDatasourceModule.kind,
        DatasourceModule.Lightweight(LocalDatasourceModule))

      refs =
        MimirIndexedStore.transformValue(
          MimirIndexedStore.transformIndex(
            MimirIndexedStore[F](precog, DatasourceRefsLocation),
            "UUID",
            StoreKey.stringIso composePrism stringUuidP),
          "DatasourceRef",
          rValueDatasourceRefP(rValueJsonP))

      configured <- refs.entries.fold(IMap.empty[UUID, DatasourceRef[Json]])(_ + _)

      scheduler <- Scheduler(corePoolSize = 1, threadPrefix = "quasar-scheduler")

      mr <- Stream.bracket(
        DatasourceManagement[Fix, F, UUID, Double](modules, configured, sstSampleSize, scheduler))(
        Stream.emit(_),
        { case (_, r) => r.get.flatMap(_.traverse_(_.dispose)) })

      (mgmt, running) = mr

      freshUUID = ConcurrentEffect[F].delay(UUID.randomUUID)

      datasources = DefaultDatasources(freshUUID, refs, mgmt, mgmt)

      federation = MimirQueryFederation[Fix, F](precog)

      (queryEvaluatorIO: QueryEvaluator[F, SqlQuery, Stream[IO, MimirRepr]]) =
        Sql2QueryEvaluator(FederatingQueryEvaluator(federation, ResourceRouter(running.get)))

      queryEvaluator = queryEvaluatorIO.map(_.translate(λ[IO ~> F](_.to[F])))

    } yield new Quasar(datasources, queryEvaluator)
  }
}
