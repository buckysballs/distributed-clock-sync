package com.clocksync.rapp

import cats.effect.IO
import cats.effect.std.Semaphore
import cats.effect.kernel.Ref
import com.clocksync.rapp.consensus.*
import com.clocksync.rapp.models.*
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

/**
 * L1 State Channel implementing the safe hylo pattern for clock consensus.
 *
 * Processing states with WellFounded depths (strictly decreasing):
 *   ClockReadingReceived(3) → WeightsComputed(2) → AveragingComputed(1) → ClockSyncComplete(0)
 *
 * Follows ZKWasmStateChannelTyped.scala pattern:
 *   - Safe coalgebra: Unfolds L0 output into initial processing state
 *   - Execute step: Computes topology weights, runs weighted averaging, generates proof
 *   - Safe algebra: Folds result into ClockSyncResult with convergence status
 */
object ClockConsensusStateChannelTyped {

  private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  /**
   * Safe coalgebra: unfold L0 output into the initial processing state.
   * This is the "ana" part of the hylomorphism.
   */
  def safeCoalgebra(input: ClockL0Output): ClockProcessingState =
    ClockProcessingState.ClockReadingReceived(
      readings = input.readings,
      latencies = input.latencies,
      roundNumber = input.roundNumber
    )

  /**
   * Execute one step of the processing pipeline.
   * Each step strictly decreases the depth measure, guaranteeing termination.
   */
  def executeStep(
    state: ClockProcessingState,
    currentBpm: Double,
    previousOffsets: Map[String, Double],
    referenceNanos: Long
  ): IO[ClockProcessingState] = state match {

    case s: ClockProcessingState.ClockReadingReceived =>
      // Depth 3 → 2: Compute topology weights from readings
      for {
        _ <- logger.info(s"[Round ${s.roundNumber}] Computing topology weights for ${s.readings.size} nodes")
        nodeIds = s.readings.keySet
        weights = TopologyManager.computeWeights(nodeIds, s.latencies)
        offsets = if previousOffsets.isEmpty then
          s.readings.map { case (id, r) => id -> r.monotonicOffsetNanos }
        else previousOffsets
      } yield ClockProcessingState.WeightsComputed(
        readings = s.readings,
        weights = weights,
        previousOffsets = offsets,
        roundNumber = s.roundNumber
      )

    case s: ClockProcessingState.WeightsComputed =>
      // Depth 2 → 1: Run weighted averaging consensus round
      for {
        _ <- logger.info(s"[Round ${s.roundNumber}] Running weighted averaging consensus")
        flatWeights = TopologyManager.flattenWeights(s.weights)
        (convergedOffsets, rounds, converged) = WeightedAveraging.runUntilConverged(
          s.previousOffsets, flatWeights
        )
        _ <- logger.info(s"[Round ${s.roundNumber}] Consensus ${if converged then "converged" else "not converged"} after $rounds iterations")
        roundState = WeightedAveraging.buildRoundState(
          s.roundNumber, s.readings, s.weights, s.previousOffsets, convergedOffsets
        )
        syncClock = WeightedAveraging.buildSynchronizedClock(
          convergedOffsets, currentBpm, s.roundNumber
        )
      } yield ClockProcessingState.AveragingComputed(
        roundState = roundState,
        synchronizedClock = syncClock
      )

    case s: ClockProcessingState.AveragingComputed =>
      // Depth 1 → 0: Generate proof hash and finalize
      for {
        _ <- logger.info(s"[Round ${s.roundState.roundNumber}] Finalizing consensus result")
        proofHash = generateProofHash(s.roundState, s.synchronizedClock)
        result = ClockSyncResult(
          synchronizedClock = s.synchronizedClock,
          roundState = s.roundState,
          proofHash = Some(proofHash)
        )
      } yield ClockProcessingState.ClockSyncComplete(result)

    case s: ClockProcessingState.ClockSyncComplete =>
      // Already at depth 0, return as-is
      IO.pure(s)
  }

  /**
   * Run the full safe hylomorphism: unfold → process → fold.
   * Guaranteed to terminate because depth strictly decreases at each step.
   */
  def processInput(
    input: ClockL0Output,
    currentBpm: Double,
    previousOffsets: Map[String, Double],
    referenceNanos: Long
  ): IO[ClockSyncResult] = {
    val initial = safeCoalgebra(input)
    runToCompletion(initial, currentBpm, previousOffsets, referenceNanos)
  }

  /**
   * Iteratively execute steps until we reach depth 0 (ClockSyncComplete).
   */
  private def runToCompletion(
    state: ClockProcessingState,
    currentBpm: Double,
    previousOffsets: Map[String, Double],
    referenceNanos: Long
  ): IO[ClockSyncResult] =
    state match {
      case s: ClockProcessingState.ClockSyncComplete =>
        // Safe algebra: fold the completed state into the final result
        IO.pure(s.result)
      case other =>
        executeStep(other, currentBpm, previousOffsets, referenceNanos).flatMap { next =>
          // Verify depth is strictly decreasing (termination guarantee)
          if next.depth >= other.depth then
            IO.raiseError(new RuntimeException(
              s"WellFounded violation: depth did not decrease from ${other.depth} to ${next.depth}"
            ))
          else
            runToCompletion(next, currentBpm, previousOffsets, referenceNanos)
        }
    }

  /**
   * Generate a proof hash for the consensus round.
   * In production this would invoke the ZK-WASM executor for a STARK proof.
   */
  private def generateProofHash(
    roundState: ConsensusRoundState,
    syncClock: SynchronizedClock
  ): String = {
    import java.security.MessageDigest
    val digest = MessageDigest.getInstance("SHA-256")
    val data = s"${roundState.roundNumber}:${syncClock.globalOffsetNanos}:" +
      s"${syncClock.convergenceError}:${roundState.currentOffsets.values.mkString(",")}"
    digest.update(data.getBytes("UTF-8"))
    digest.digest().map("%02x".format(_)).mkString
  }

  /**
   * Create a state channel processor with concurrency control.
   * Returns a function that processes L0 outputs into sync results,
   * backed by shared mutable state for offsets and BPM.
   */
  def makeProcessor(
    offsetsRef: Ref[IO, Map[String, Double]],
    bpmRef: Ref[IO, Double],
    referenceNanosRef: Ref[IO, Long]
  ): IO[ClockL0Output => IO[ClockSyncResult]] =
    Semaphore[IO](1).map { sem =>
      (input: ClockL0Output) =>
        sem.permit.use { _ =>
          for {
            currentBpm <- bpmRef.get
            prevOffsets <- offsetsRef.get
            refNanos <- referenceNanosRef.get
            result <- processInput(input, currentBpm, prevOffsets, refNanos)
            // Update shared state with new offsets
            _ <- offsetsRef.set(result.roundState.currentOffsets)
          } yield result
        }
    }
}
