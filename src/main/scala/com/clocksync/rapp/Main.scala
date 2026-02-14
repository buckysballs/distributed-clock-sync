package com.clocksync.rapp

import cats.effect.*
import cats.effect.std.Queue
import com.clocksync.rapp.consensus.WeightedAveraging
import com.clocksync.rapp.models.*
import com.clocksync.rapp.pipeline.ClockPipeline
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*

/**
 * BabelApp entry point for the Distributed Clock Sync rApp.
 *
 * Configures L0 + L1 nodes based on NODE_TYPE env var, wires the pipeline:
 *   ClockPipeline.clockL0Cell >>> ClockConsensusStateChannelTyped
 *
 * Registers SynthClockRoutes as custom HTTP routes and starts the OSC bridge.
 */
object Main extends IOApp {

  private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private val banner =
    """
    |  ╔══════════════════════════════════════════╗
    |  ║   Distributed Clock Sync rApp            ║
    |  ║   Weighted Averaging Consensus Protocol  ║
    |  ║   Reality SDK / Babel Framework           ║
    |  ╚══════════════════════════════════════════╝
    """.stripMargin

  override def run(args: List[String]): IO[ExitCode] = {
    val nodeType = sys.env.getOrElse("NODE_TYPE", "genesis")
    val httpPort = sys.env.getOrElse("HTTP_PORT", "9000").toInt
    val oscPort = sys.env.getOrElse("OSC_PORT", "9050").toInt
    val nodeId = sys.env.getOrElse("NODE_ID", s"node-$nodeType")
    val genesisHost = sys.env.getOrElse("GENESIS_HOST", "localhost")
    val initialBpm = sys.env.getOrElse("INITIAL_BPM", "120.0").toDouble
    val midiDevice = sys.env.get("MIDI_DEVICE")

    for {
      _ <- logger.info(banner)
      _ <- logger.info(s"Starting node: type=$nodeType, id=$nodeId, http=$httpPort, osc=$oscPort")

      // Initialize pipeline state
      pipelineState <- ClockPipeline.initState(initialBpm)

      // Create reading submission queue
      readingsQueue <- Queue.unbounded[IO, ClockReading]

      // Start the consensus loop in background
      consensusFiber <- startConsensusLoop(pipelineState, readingsQueue, nodeId).start

      // Start the synth clock update loop (100Hz)
      clockUpdateFiber <- startClockUpdateLoop(pipelineState).start

      // Build HTTP routes
      routes = SynthClockRoutes.routes(pipelineState, readingsQueue, nodeId)
      wsRoutes = SynthClockRoutes.wsRoutes(pipelineState, readingsQueue, nodeId)
      httpApp = Router("/" -> routes).orNotFound

      // Start OSC bridge
      oscFiber <- OscClockBridge.start(pipelineState, readingsQueue, oscPort, nodeId).start

      // Start MIDI bridge
      midiFiber <- MidiClockBridge.start(pipelineState, midiDevice).start

      // Start HTTP server
      _ <- logger.info(s"Starting HTTP server on port $httpPort")
      _ <- EmberServerBuilder.default[IO]
        .withHost(host"0.0.0.0")
        .withPort(Port.fromInt(httpPort).getOrElse(port"9000"))
        .withHttpWebSocketApp(wsBuilder =>
          Router(
            "/" -> routes,
            "/" -> SynthClockRoutes.wsRoutes(pipelineState, readingsQueue, nodeId)(wsBuilder)
          ).orNotFound
        )
        .withShutdownTimeout(5.seconds)
        .build
        .use { server =>
          logger.info(s"Server started at ${server.address}") *>
          IO.never
        }
        .guarantee(
          midiFiber.cancel *>
          consensusFiber.cancel *>
          clockUpdateFiber.cancel *>
          oscFiber.cancel *>
          logger.info("Shutting down Clock Sync node")
        )
    } yield ExitCode.Success
  }

  /**
   * Background loop that collects readings and runs consensus rounds.
   * Collects readings for 100ms, then runs a consensus round with whatever
   * has accumulated.
   */
  private def startConsensusLoop(
    state: ClockPipeline.PipelineState,
    queue: Queue[IO, ClockReading],
    nodeId: String
  ): IO[Unit] = {
    def loop: IO[Unit] =
      for {
        // Wait for at least one reading, then drain the queue
        _ <- IO.sleep(100.millis)

        // Submit our own clock reading
        now = System.nanoTime()
        selfReading = ClockReading(
          nodeId = nodeId,
          monotonicOffsetNanos = now.toDouble,
          roundTripLatencyNanos = 0.0, // self has zero latency
          roundNumber = 0L, // will be set by pipeline
          timestampNanos = now
        )
        _ <- queue.offer(selfReading)

        // Drain all available readings
        readings <- drainQueue(queue)

        // Only run consensus if we have readings
        _ <- if readings.nonEmpty then
          ClockPipeline.runPipeline(state, readings).attempt.flatMap {
            case Right(result) =>
              logger.debug(s"Consensus round ${result.synchronizedClock.roundNumber}: " +
                s"quality=${WeightedAveraging.syncQuality(result.synchronizedClock.convergenceError)}")
            case Left(err) =>
              logger.warn(s"Consensus round failed: ${err.getMessage}")
          }
        else IO.unit

        _ <- loop
      } yield ()

    loop
  }

  /**
   * Drain all currently available items from a queue.
   */
  private def drainQueue[A](queue: Queue[IO, A]): IO[List[A]] = {
    def go(acc: List[A]): IO[List[A]] =
      queue.tryTake.flatMap {
        case Some(item) => go(item :: acc)
        case None => IO.pure(acc.reverse)
      }
    go(Nil)
  }

  /**
   * Background loop that updates the SynthClockState at 100Hz
   * for smooth beat position tracking between consensus rounds.
   */
  private def startClockUpdateLoop(
    state: ClockPipeline.PipelineState
  ): IO[Unit] = {
    def loop: IO[Unit] =
      for {
        _ <- IO.sleep(10.millis) // 100Hz
        bpm <- state.bpmRef.get
        refNanos <- state.referenceNanosRef.get
        offsets <- state.offsetsRef.get
        now = System.nanoTime()
        globalOffset = if offsets.isEmpty then 0.0
          else offsets.values.sum / offsets.size
        adjustedNow = now + globalOffset.toLong
        beatPos = com.clocksync.rapp.consensus.WeightedAveraging
          .computeBeatPosition(adjustedNow, bpm, refNanos)
        phase = com.clocksync.rapp.consensus.WeightedAveraging
          .computePhase(adjustedNow, bpm, refNanos)
        nextBeat = com.clocksync.rapp.consensus.WeightedAveraging
          .computeNextBeatNanos(adjustedNow, bpm, refNanos)
        prevState <- state.synthClockRef.get
        _ <- state.synthClockRef.update(_.copy(
          beatPosition = beatPos,
          barPosition = (beatPos / 4.0).toInt,
          beatInBar = beatPos.toInt % 4,
          phaseRadians = phase,
          globalNanos = adjustedNow,
          nextBeatNanos = nextBeat
        ))
        _ <- loop
      } yield ()

    loop
  }
}
