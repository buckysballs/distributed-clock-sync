package com.clocksync.rapp.pipeline

import cats.effect.IO
import cats.effect.kernel.Ref
import com.clocksync.rapp.ClockConsensusStateChannelTyped
import com.clocksync.rapp.consensus.*
import com.clocksync.rapp.models.*
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

/**
 * Clock pipeline: L0 cell processing + L1 state channel composition.
 *
 * Pipeline: ClockL0Cell >>> ClockConsensusStateChannelTyped
 *
 * The L0 cell collects clock readings from the network and packages them
 * for the L1 state channel, which runs the consensus algorithm.
 */
object ClockPipeline {

  private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  /**
   * Shared state for the pipeline, accessible by both the consensus engine
   * and the API routes.
   */
  final case class PipelineState(
    offsetsRef: Ref[IO, Map[String, Double]],
    bpmRef: Ref[IO, Double],
    referenceNanosRef: Ref[IO, Long],
    synthClockRef: Ref[IO, SynthClockState],
    tempoConsensusRef: Ref[IO, TempoConsensusState],
    topologyRef: Ref[IO, TopologyInfo],
    roundNumberRef: Ref[IO, Long]
  )

  /**
   * Initialize shared pipeline state.
   */
  def initState(initialBpm: Double = 120.0): IO[PipelineState] =
    for {
      offsets <- Ref.of[IO, Map[String, Double]](Map.empty)
      bpm <- Ref.of[IO, Double](initialBpm)
      refNanos <- Ref.of[IO, Long](System.nanoTime())
      synthClock <- Ref.of[IO, SynthClockState](SynthClockState.initial)
      tempoCons <- Ref.of[IO, TempoConsensusState](TempoConsensusState.initial(initialBpm))
      topology <- Ref.of[IO, TopologyInfo](TopologyInfo(Nil, Map.empty, Map.empty))
      roundNum <- Ref.of[IO, Long](0L)
    } yield PipelineState(offsets, bpm, refNanos, synthClock, tempoCons, topology, roundNum)

  /**
   * L0 Cell: Process incoming clock readings into an L0 output.
   * Collects readings, extracts latencies, increments round number.
   */
  def clockL0Cell(
    state: PipelineState,
    readings: List[ClockReading]
  ): IO[ClockL0Output] =
    for {
      roundNum <- state.roundNumberRef.updateAndGet(_ + 1)
      _ <- logger.info(s"L0 Cell: Processing ${readings.size} readings for round $roundNum")
      readingsMap = readings.map(r => r.nodeId -> r).toMap
      latencies = TopologyManager.extractLatencies(readingsMap)
    } yield ClockL0Output(
      readings = readingsMap,
      latencies = latencies,
      roundNumber = roundNum
    )

  /**
   * Full pipeline: L0 Cell >>> L1 State Channel.
   * Processes readings through consensus and updates shared state.
   */
  def runPipeline(
    state: PipelineState,
    readings: List[ClockReading]
  ): IO[ClockSyncResult] =
    for {
      // L0 Cell
      l0Output <- clockL0Cell(state, readings)

      // L1 State Channel (consensus)
      currentBpm <- state.bpmRef.get
      prevOffsets <- state.offsetsRef.get
      refNanos <- state.referenceNanosRef.get
      result <- ClockConsensusStateChannelTyped.processInput(
        l0Output, currentBpm, prevOffsets, refNanos
      )

      // Update shared state
      _ <- state.offsetsRef.set(result.roundState.currentOffsets)
      _ <- updateSynthClock(state, result)
      _ <- updateTopology(state, result)

      _ <- logger.info(
        s"Pipeline complete: round=${result.synchronizedClock.roundNumber}, " +
        s"error=${result.synchronizedClock.convergenceError}ns, " +
        s"participants=${result.synchronizedClock.participantCount}"
      )
    } yield result

  /**
   * Update the SynthClockState from consensus results.
   */
  private def updateSynthClock(
    state: PipelineState,
    result: ClockSyncResult
  ): IO[Unit] =
    for {
      bpm <- state.bpmRef.get
      refNanos <- state.referenceNanosRef.get
      now = System.nanoTime()
      adjustedNow = now + result.synchronizedClock.globalOffsetNanos.toLong
      beatPos = WeightedAveraging.computeBeatPosition(adjustedNow, bpm, refNanos)
      phase = WeightedAveraging.computePhase(adjustedNow, bpm, refNanos)
      nextBeat = WeightedAveraging.computeNextBeatNanos(adjustedNow, bpm, refNanos)
      quality = WeightedAveraging.syncQuality(result.synchronizedClock.convergenceError)
      barPos = (beatPos / 4.0).toInt
      beatInBar = (beatPos.toInt % 4)
      newState = SynthClockState(
        bpm = bpm,
        beatPosition = beatPos,
        barPosition = barPos,
        beatInBar = beatInBar,
        phaseRadians = phase,
        globalNanos = adjustedNow,
        nextBeatNanos = nextBeat,
        syncQuality = quality,
        participantCount = result.synchronizedClock.participantCount,
        roundNumber = result.synchronizedClock.roundNumber
      )
      _ <- state.synthClockRef.set(newState)
    } yield ()

  /**
   * Update topology info from consensus results.
   */
  private def updateTopology(
    state: PipelineState,
    result: ClockSyncResult
  ): IO[Unit] = {
    val nodeIds = result.roundState.readings.keySet
    val globalOff = WeightedAveraging.globalOffset(result.roundState.currentOffsets)
    val statuses = TopologyManager.buildNodeStatuses(
      result.roundState.currentOffsets,
      result.roundState.readings,
      globalOff,
      result.roundState.roundNumber
    )
    val conn = TopologyManager.connectivity(nodeIds)
    val info = TopologyInfo(statuses, conn, result.roundState.weights)
    state.topologyRef.set(info)
  }
}
