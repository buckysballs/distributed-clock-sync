package com.clocksync.rapp.consensus

import com.clocksync.rapp.models.*

/**
 * Pure implementation of the weighted averaging consensus protocol from
 * "Topological Characterization of Consensus in Distributed Systems."
 *
 * Core update rule:
 *   x_i(t+1) = Σ_j a_ij(t) * x_j(t)
 *
 * Each node iteratively averages its clock offset with neighbors' offsets,
 * weighted by topology-derived coefficients. Convergence is guaranteed when
 * the weight matrix is doubly stochastic and the graph is strongly connected.
 */
object WeightedAveraging {

  /**
   * Compute the next offset for a single node using the weighted averaging rule.
   *
   * @param nodeId     The node whose offset we're computing
   * @param allOffsets Current offsets for all nodes: nodeId -> offset (nanos)
   * @param weights    Weight matrix: (from, to) -> weight. Must be doubly stochastic.
   * @return The new offset for this node
   */
  def computeNextOffset(
    nodeId: String,
    allOffsets: Map[String, Double],
    weights: Map[(String, String), Double]
  ): Double =
    allOffsets.foldLeft(0.0) { case (sum, (neighborId, offset)) =>
      sum + weights.getOrElse((nodeId, neighborId), 0.0) * offset
    }

  /**
   * Compute one full round of weighted averaging for all nodes.
   *
   * @param offsets Current offsets for all nodes
   * @param weights Weight matrix (doubly stochastic)
   * @return Updated offsets after one consensus round
   */
  def computeRound(
    offsets: Map[String, Double],
    weights: Map[(String, String), Double]
  ): Map[String, Double] =
    offsets.map { case (nodeId, _) =>
      nodeId -> computeNextOffset(nodeId, offsets, weights)
    }

  /**
   * Run multiple consensus rounds until convergence or max iterations.
   *
   * @param offsets        Initial offsets
   * @param weights        Weight matrix
   * @param epsilon        Convergence threshold in nanos (default: 1000 = 1 microsecond)
   * @param maxIterations  Maximum number of rounds
   * @return (final offsets, rounds executed, converged?)
   */
  def runUntilConverged(
    offsets: Map[String, Double],
    weights: Map[(String, String), Double],
    epsilon: Double = 1000.0,
    maxIterations: Int = 100
  ): (Map[String, Double], Int, Boolean) = {
    var current = offsets
    var round = 0
    while round < maxIterations && !isConverged(current, epsilon) do {
      current = computeRound(current, weights)
      round += 1
    }
    (current, round, isConverged(current, epsilon))
  }

  /**
   * Compute the convergence error: max spread across all node offsets.
   * When this reaches zero, all nodes agree on the global clock.
   */
  def convergenceError(offsets: Map[String, Double]): Double =
    if offsets.isEmpty then 0.0
    else offsets.values.max - offsets.values.min

  /**
   * Check if offsets have converged within the given threshold.
   *
   * @param epsilon Threshold in nanoseconds. Default 1000 (1 microsecond).
   *                At 200 BPM, 1 beat = 300ms, so 1us = 0.0003% error.
   */
  def isConverged(offsets: Map[String, Double], epsilon: Double = 1000.0): Boolean =
    offsets.size <= 1 || convergenceError(offsets) < epsilon

  /**
   * Compute the global synchronized offset as the average of all converged offsets.
   */
  def globalOffset(offsets: Map[String, Double]): Double =
    if offsets.isEmpty then 0.0
    else offsets.values.sum / offsets.size

  /**
   * Derive sync quality from convergence error.
   * Maps error range [0, threshold] to quality [1.0, 0.0].
   *
   * @param error     Current convergence error in nanos
   * @param threshold Error at which quality drops to 0 (default: 1ms = 1_000_000 nanos)
   */
  def syncQuality(error: Double, threshold: Double = 1_000_000.0): Double =
    math.max(0.0, math.min(1.0, 1.0 - (error / threshold)))

  /**
   * Build a ConsensusRoundState from readings and computed consensus.
   */
  def buildRoundState(
    roundNumber: Long,
    readings: Map[String, ClockReading],
    weights: Map[String, Map[String, Double]],
    previousOffsets: Map[String, Double],
    currentOffsets: Map[String, Double]
  ): ConsensusRoundState = {
    val error = convergenceError(currentOffsets)
    ConsensusRoundState(
      roundNumber = roundNumber,
      readings = readings,
      weights = weights,
      previousOffsets = previousOffsets,
      currentOffsets = currentOffsets,
      converged = isConverged(currentOffsets),
      convergenceError = error,
      processingDepth = 0
    )
  }

  /**
   * Derive a SynchronizedClock from converged offsets.
   */
  def buildSynchronizedClock(
    offsets: Map[String, Double],
    bpm: Double,
    roundNumber: Long
  ): SynchronizedClock = {
    val error = convergenceError(offsets)
    SynchronizedClock(
      globalOffsetNanos = globalOffset(offsets),
      bpmEstimate = bpm,
      convergenceError = error,
      participantCount = offsets.size,
      roundNumber = roundNumber
    )
  }

  /**
   * Compute beat position from global nanosecond offset.
   *
   * @param globalNanos     Current global nanosecond timestamp
   * @param bpm             Beats per minute
   * @param referenceNanos  Reference timestamp (beat 0)
   * @return Beat position as a floating-point number
   */
  def computeBeatPosition(globalNanos: Long, bpm: Double, referenceNanos: Long): Double = {
    val elapsedNanos = (globalNanos - referenceNanos).toDouble
    val nanosPerBeat = 60.0 / bpm * 1_000_000_000.0
    elapsedNanos / nanosPerBeat
  }

  /**
   * Compute phase in radians [0, 2π) within the current beat.
   */
  def computePhase(globalNanos: Long, bpm: Double, referenceNanos: Long): Double = {
    val beatPos = computeBeatPosition(globalNanos, bpm, referenceNanos)
    val fractional = beatPos - math.floor(beatPos)
    fractional * 2.0 * math.Pi
  }

  /**
   * Compute nanosecond timestamp of the next beat.
   */
  def computeNextBeatNanos(globalNanos: Long, bpm: Double, referenceNanos: Long): Long = {
    val beatPos = computeBeatPosition(globalNanos, bpm, referenceNanos)
    val nextBeat = math.ceil(beatPos)
    val nanosPerBeat = 60.0 / bpm * 1_000_000_000.0
    referenceNanos + (nextBeat * nanosPerBeat).toLong
  }
}
