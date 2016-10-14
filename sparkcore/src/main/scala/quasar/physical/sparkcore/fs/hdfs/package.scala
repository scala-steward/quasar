/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.physical.sparkcore.fs

import quasar.Predef._
import quasar.fs._
import quasar.fs.QueryFile.ResultHandle
import quasar.fs.mount._, FileSystemDef._
import quasar.physical.sparkcore.fs.{readfile => corereadfile}
import quasar.physical.sparkcore.fs.{queryfile => corequeryfile}
import quasar.physical.sparkcore.fs.hdfs.writefile.HdfsWriteCursor
import quasar.effect._
import quasar.fs.ReadFile.ReadHandle
import quasar.fs.WriteFile.WriteHandle
import quasar.fp.TaskRef
import quasar.fp.free._
import quasar.EnvironmentError
import quasar.fs.mount.ConnectionUri
import quasar.contrib.pathy._

import org.apache.hadoop.fs.{FileSystem => HdfsFileSystem}
import org.apache.hadoop.conf.Configuration;
import java.net.URI

import pathy.Path._
import org.apache.spark._
import scalaz._, Scalaz._
import scalaz.concurrent.Task

package object hdfs {

  import corequeryfile.RddState

  val FsType = FileSystemType("spark-hdfs")

  type EffM1[A] = Coproduct[KeyValueStore[ResultHandle, RddState, ?], Read[SparkContext, ?], A]
  type Eff0[A] = Coproduct[KeyValueStore[ReadHandle, SparkCursor, ?], EffM1, A]
  type Eff1[A] = Coproduct[KeyValueStore[WriteHandle, HdfsWriteCursor, ?], Eff0, A]
  type Eff2[A] = Coproduct[Task, Eff1, A]
  type Eff3[A] = Coproduct[PhysErr, Eff2, A]
  type Eff[A]  = Coproduct[MonotonicSeq, Eff3, A]

  final case class SparkFSConf(sparkConf: SparkConf, hdfsUriStr: String, prefix: ADir)

  def parseUri(uri: ConnectionUri): DefinitionError \/ SparkFSConf = {

    def error(msg: String): DefinitionError \/ SparkFSConf =
      NonEmptyList(msg).left[EnvironmentError].left[SparkFSConf]

    def forge(master: String, hdfsUriStr: String, rootPath: String): DefinitionError \/ SparkFSConf =
      posixCodec.parseAbsDir(rootPath)
        .map { prefix =>
        SparkFSConf(new SparkConf().setMaster(master), hdfsUriStr, sandboxAbs(prefix))
      }.fold(error(s"Could not extrat a path from $rootPath"))(_.right[DefinitionError])

    uri.value.split('|').toList match {
      case master :: hdfsUriStr :: prefixPath :: Nil => forge(master, hdfsUriStr, prefixPath)
      case _ =>
        error("Missing master and prefixPath (seperated by |)" +
          " e.g spark://spark_host:port|hdfs://hdfs_host:port|/user/")
    }
  }

  final case class SparkHdfsFSDef[S[_]](run: Free[Eff, ?] ~> Free[S, ?], close: Free[S, Unit])

  private def sparkFsDef[S[_]](sparkConf: SparkConf)(implicit
    S0: Task :<: S,
    S1: PhysErr :<: S
  ): Free[S, SparkHdfsFSDef[S]] = {

    val genSc = Task.delay {
      new SparkContext(sparkConf.setAppName("quasar"))
    }

    lift((TaskRef(0L) |@|
      TaskRef(Map.empty[ResultHandle, RddState]) |@|
      TaskRef(Map.empty[ReadHandle, SparkCursor]) |@|
      TaskRef(Map.empty[WriteHandle, HdfsWriteCursor]) |@|
      genSc) {
      // TODO better names!
      (genState, rddStates, sparkCursors, writeCursors, sc) =>
      val interpreter: Eff ~> S = (MonotonicSeq.fromTaskRef(genState) andThen injectNT[Task, S]) :+:
      injectNT[PhysErr, S] :+:
      injectNT[Task, S]  :+:
      (KeyValueStore.impl.fromTaskRef[WriteHandle, HdfsWriteCursor](writeCursors) andThen injectNT[Task, S])  :+:
      (KeyValueStore.impl.fromTaskRef[ReadHandle, SparkCursor](sparkCursors) andThen injectNT[Task, S]) :+:
      (KeyValueStore.impl.fromTaskRef[ResultHandle, RddState](rddStates) andThen injectNT[Task, S]) :+:
      (Read.constant[Task, SparkContext](sc) andThen injectNT[Task, S])

      SparkHdfsFSDef(mapSNT[Eff, S](interpreter), lift(Task.delay(sc.stop())).into[S])
    }).into[S]
  }

  private def fsInterpret(sparkFsConf: SparkFSConf): FileSystem ~> Free[Eff, ?] = {

    def hdfsPathStr: AFile => Task[String] = (afile: AFile) => Task.delay {
      sparkFsConf.hdfsUriStr + posixCodec.unsafePrintPath(afile)
    }

    def fileSystem: Task[HdfsFileSystem] = Task.delay {
      val conf = new Configuration()
      conf.setBoolean("fs.hdfs.impl.disable.cache", true)
      HdfsFileSystem.get(new URI(sparkFsConf.hdfsUriStr), conf)
    }

    interpretFileSystem(
      corequeryfile.chrooted[Eff](queryfile.input(fileSystem), FsType, sparkFsConf.prefix),
      corereadfile.chrooted(readfile.input[Eff](hdfsPathStr, fileSystem), sparkFsConf.prefix),
      writefile.chrooted[Eff](sparkFsConf.prefix, fileSystem),
      managefile.chrooted[Eff](sparkFsConf.prefix, fileSystem))
  }

  def definition[S[_]](implicit S0: Task :<: S, S1: PhysErr :<: S):
      FileSystemDef[Free[S, ?]] =
    FileSystemDef.fromPF {
      case (FsType, uri) =>
        for {
          sparkFsConf <- EitherT(parseUri(uri).point[Free[S, ?]])
          res <- {
            sparkFsDef(sparkFsConf.sparkConf).map {
              case SparkHdfsFSDef(run, close) =>
                FileSystemDef.DefinitionResult[Free[S, ?]](
                  fsInterpret(sparkFsConf) andThen run,
                  close)
            }.liftM[DefErrT]
          }
        }  yield res
    }
}