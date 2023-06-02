package almond.integration

import almond.channels.zeromq.ZeromqThreads
import almond.channels.{Channel, Connection, ConnectionParameters, Message => RawMessage}
import almond.testkit.Dsl._
import almond.testkit.{ClientStreams, TestLogging}
import cats.effect.IO
import cats.effect.unsafe.IORuntime
import com.github.plokhotnyuk.jsoniter_scala.core.writeToArray
import fs2.concurrent.SignallingRef

import java.io.{File, IOException}
import java.nio.channels.ClosedSelectorException
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.DurationInt
import scala.util.control.NonFatal
import scala.util.Properties

object KernelLauncher {

  lazy val localRepoRoot = sys.props.get("almond.test.local-repo")
    .map(os.Path(_, os.pwd))
    .getOrElse {
      sys.error("almond.test.local-repo Java property not set")
    }

  lazy val almondVersion = sys.props.getOrElse(
    "almond.test.version",
    sys.error("almond.test.version Java property not set")
  )

  lazy val cs = sys.props.getOrElse(
    "almond.test.cs-launcher",
    sys.error("almond.test.cs-launcher Java property not set")
  )

  object TmpDir {

    private lazy val baseTmpDir = {
      Option(System.getenv("ALMOND_INTEGRATION_TMP")).getOrElse {
        sys.error("ALMOND_INTEGRATION_TMP not set")
      }
      val base = os.Path(System.getenv("ALMOND_INTEGRATION_TMP"), os.pwd)
      val rng  = new SecureRandom
      val d    = base / s"run-${math.abs(rng.nextInt().toLong)}"
      os.makeDir.all(d)
      Runtime.getRuntime.addShutdownHook(
        new Thread("scala-cli-its-clean-up-tmp-dir") {
          setDaemon(true)
          override def run(): Unit =
            try os.remove.all(d)
            catch {
              case NonFatal(_) =>
                System.err.println(s"Could not remove $d, ignoring it.")
            }
        }
      )
      d
    }

    private val tmpCount = new AtomicInteger

    def withTmpDir[T](f: os.Path => T): T = {
      val tmpDir = baseTmpDir / s"test-${tmpCount.incrementAndGet()}"
      os.makeDir.all(tmpDir)
      val tmpDir0 = os.Path(tmpDir.toIO.getCanonicalFile)
      def removeAll(): Unit =
        try os.remove.all(tmpDir0)
        catch {
          case ex: IOException =>
            System.err.println(s"Ignoring $ex while removing $tmpDir0")
        }
      try f(tmpDir0)
      finally removeAll()
    }

    def tmpDir(): os.Path = {
      val tmpDir = baseTmpDir / s"test-${tmpCount.incrementAndGet()}"
      os.makeDir.all(tmpDir)
      os.Path(tmpDir.toIO.getCanonicalFile)
    }
  }
}

class KernelLauncher(isTwoStepStartup: Boolean, defaultScalaVersion: String) {

  import KernelLauncher._

  private def generateLauncher(extraOptions: Seq[String] = Nil): os.Path = {
    val perms: os.PermSet = if (Properties.isWin) null else "rwx------"
    val tmpDir            = os.temp.dir(prefix = "almond-tests", perms = perms)
    val (jarDest, extraOpts) =
      if (Properties.isWin)
        (tmpDir / "launcher", Seq("--bat"))
      else {
        val launcher = tmpDir / "launcher.jar"
        (launcher, Nil)
      }
    val repoArgs = Seq(
      "--no-default",
      "-r",
      s"ivy:${localRepoRoot.toNIO.toUri.toASCIIString.stripSuffix("/")}/[defaultPattern]",
      "-r",
      "ivy2Local",
      "-r",
      "central",
      "-r",
      "jitpack"
    )
    val launcherArgs =
      if (isTwoStepStartup)
        Seq(s"sh.almond:launcher_3:$almondVersion")
      else
        Seq(
          s"sh.almond:::scala-kernel:$almondVersion",
          "--shared",
          "sh.almond:::scala-kernel-api",
          "--scala",
          defaultScalaVersion
        )
    val res = os.proc(
      cs,
      "bootstrap",
      "--embed-files=false",
      "--default=true",
      "--sources",
      extraOpts,
      repoArgs,
      "-o",
      jarDest,
      launcherArgs,
      extraOptions
    )
      .call(stdin = os.Inherit, stdout = os.Inherit, check = false)
    if (res.exitCode != 0)
      sys.error(
        s"""Error generating an Almond $almondVersion launcher for Scala $defaultScalaVersion
           |
           |If that error is unexpected, you might want to:
           |- remove out/repo
           |    rm -rf out/repo
           |- remove cached version computation in the build:
           |    find out -name "*publishVersion*" -print0 | xargs -0 rm -f
           |
           |Then try again.
           |""".stripMargin
      )
    jarDest
  }

  private lazy val jarLauncher = generateLauncher()

  private lazy val threads = ZeromqThreads.create("almond-tests")

  def session(conn: Connection): Session with AutoCloseable =
    new Session with AutoCloseable {
      def run(streams: ClientStreams): Unit = {

        val poisonPill: (Channel, RawMessage) = null

        val s = SignallingRef[IO, Boolean](false).unsafeRunSync()(IORuntime.global)

        val t = for {
          fib1 <- conn.sink(streams.source).compile.drain.start
          fib2 <- streams.sink(conn.stream().interruptWhen(s)).compile.drain.start
          _ <- fib1.join.attempt.flatMap {
            case Left(e)  => IO.raiseError(new Exception(e))
            case Right(r) => IO.pure(r)
          }
          _ <- s.set(true)
          _ <- fib2.join.attempt.flatMap {
            case Left(e: ClosedSelectorException) => IO.pure(())
            case Left(e)                          => IO.raiseError(new Exception(e))
            case Right(r)                         => IO.pure(r)
          }
        } yield ()

        try Await.result(t.unsafeToFuture()(IORuntime.global), 1.minute)
        catch {
          case NonFatal(e) => throw new Exception(e)
        }
      }

      def close(): Unit =
        conn.close.unsafeRunSync()(IORuntime.global)
    }

  def runner(): Runner with AutoCloseable =
    new Runner with AutoCloseable {

      override def differedStartUp = isTwoStepStartup

      var proc: os.SubProcess = null
      var sessions            = List.empty[Session with AutoCloseable]

      def withSession[T](options: String*)(f: Session => T)(implicit sessionId: SessionId): T =
        withRunnerSession(options, Nil, Nil)(f)
      def withExtraClassPathSession[T](extraClassPath: String*)(options: String*)(f: Session => T)(
        implicit sessionId: SessionId
      ): T =
        withRunnerSession(options, Nil, extraClassPath)(f)
      def withLauncherOptionsSession[T](launcherOptions: String*)(options: String*)(
        f: Session => T
      )(implicit sessionId: SessionId): T =
        withRunnerSession(options, launcherOptions, Nil)(f)

      def apply(options: String*): Session =
        runnerSession(options, Nil, Nil)
      def withExtraClassPath(extraClassPath: String*)(options: String*): Session =
        runnerSession(options, Nil, extraClassPath)
      def withLauncherOptions(launcherOptions: String*)(options: String*): Session =
        runnerSession(options, launcherOptions, Nil)

      def withRunnerSession[T](
        options: Seq[String],
        launcherOptions: Seq[String],
        extraClassPath: Seq[String]
      )(f: Session => T)(implicit sessionId: SessionId): T = {
        implicit val sess = runnerSession(options, launcherOptions, extraClassPath)
        var running       = true

        val currentThread = Thread.currentThread()

        val t: Thread =
          new Thread("watch-kernel-proc") {
            setDaemon(true)
            override def run(): Unit = {
              var done = false
              while (running && !done)
                done = proc.waitFor(100L)
              if (running && done) {
                val retCode = proc.exitCode()
                System.err.println(s"Kernel process exited with code $retCode, interrupting test")
                currentThread.interrupt()
              }
            }
          }

        t.start()
        try {
          val t = f(sess)
          if (Properties.isWin)
            // On Windows, exit the kernel manually from the inside, so that all involved processes
            // exit cleanly. A call to Process#destroy would only destroy the first kernel process,
            // not any of its sub-processes (which would stay around, and such processes would end up
            // filling up memory on Windows).
            exit()
          t
        }
        finally {
          running = false
          close()
        }
      }

      private def runnerSession(
        options: Seq[String],
        launcherOptions: Seq[String],
        extraClassPath: Seq[String]
      ): Session = {

        close()

        val dir      = TmpDir.tmpDir()
        val connFile = dir / "connection.json"

        val params      = ConnectionParameters.randomLocal()
        val connDetails = almond.protocol.Connection.fromParams(params)

        os.write(connFile, writeToArray(connDetails))

        val jarLauncher0 =
          if (isTwoStepStartup || launcherOptions.isEmpty)
            jarLauncher
          else
            generateLauncher(launcherOptions)

        val baseCp =
          if (isTwoStepStartup)
            jarLauncher0.toString
          else
            (extraClassPath :+ jarLauncher0.toString)
              .filter(_.nonEmpty)
              .mkString(File.pathSeparator)
        val baseCmd: os.Shellable =
          Seq[os.Shellable](
            "java",
            "-Xmx1g",
            "-cp",
            baseCp,
            "coursier.bootstrap.launcher.Launcher"
          )

        val extraStartupClassPathOpts =
          if (isTwoStepStartup)
            extraClassPath.flatMap(elem => Seq("--extra-startup-class-path", elem)) ++
              launcherOptions ++
              Seq("--scala", defaultScalaVersion)
          else
            Nil

        val proc0 = os.proc(
          baseCmd,
          "--log",
          "debug",
          "--color=false",
          "--connection-file",
          connFile,
          extraStartupClassPathOpts,
          options
        )
        System.err.println(s"Running ${proc0.command.flatMap(_.value).mkString(" ")}")
        val extraEnv =
          if (isTwoStepStartup) {
            val baseRepos = sys.env.getOrElse(
              "COURSIER_REPOSITORIES",
              "ivy2Local|central"
            )
            Map(
              "COURSIER_REPOSITORIES" ->
                s"$baseRepos|ivy:${localRepoRoot.toNIO.toUri.toASCIIString.stripSuffix("/")}/[defaultPattern]"
            )
          }
          else
            Map.empty[String, String]
        proc = proc0.spawn(
          cwd = dir,
          env = extraEnv,
          stdin = os.Inherit,
          stdout = os.Inherit
        )

        val conn = params.channels(
          bind = false,
          threads,
          TestLogging.logCtx,
          identityOpt = Some(UUID.randomUUID().toString)
        ).unsafeRunSync()(IORuntime.global)

        conn.open.unsafeRunSync()(IORuntime.global)

        val sess = session(conn)
        sessions = sess :: sessions
        sess
      }

      def close(): Unit = {
        sessions.foreach(_.close())
        sessions = Nil
        if (proc != null) {
          if (proc.isAlive()) {
            proc.close()
            val timeout = 3.seconds
            if (!proc.waitFor(timeout.toMillis)) {
              System.err.println(
                s"Test kernel still running after $timeout, destroying it forcibly"
              )
              proc.destroyForcibly()
            }
          }
          proc = null
        }
      }
    }

  def withKernel[T](f: Runner => T): T = {

    var runner0: Runner with AutoCloseable = null

    try {
      runner0 = runner()
      f(runner0)
    }
    finally
      runner0.close()
  }

}
