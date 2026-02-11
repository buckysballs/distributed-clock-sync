package com.clocksync.rapp.consensus

import com.clocksync.rapp.models.*

/**
 * Computes doubly-stochastic weight matrices from network topology (RTT latencies)
 * using the Metropolis-Hastings weighting scheme.
 *
 * For connected nodes i, j:
 *   a_ij = 1 / max(d_i, d_j)
 * where d_i is the degree of node i.
 *
 * Self-weight:
 *   a_ii = 1 - Σ_{j≠i} a_ij
 *
 * This guarantees doubly-stochastic weights → convergence to the average
 * per the paper's requirements. Lower-latency peers are considered more
 * trustworthy clock sources.
 */
object TopologyManager {

  /**
   * Compute Metropolis-Hastings weights for a fully connected graph.
   *
   * @param nodeIds    All participating node IDs
   * @param latencies  Measured RTT latencies: nodeId -> latency in nanos.
   *                   Used to modulate base MH weights (lower latency = higher weight).
   * @return Weight matrix as nested map: from -> (to -> weight).
   *         Guaranteed doubly stochastic.
   */
  def computeWeights(
    nodeIds: Set[String],
    latencies: Map[String, Double]
  ): Map[String, Map[String, Double]] = {
    if nodeIds.size <= 1 then {
      // Single node: self-weight = 1.0
      nodeIds.headOption.map(id => Map(id -> Map(id -> 1.0))).getOrElse(Map.empty)
    } else {
      val nodes = nodeIds.toList
      // For a fully connected graph, each node's degree = n - 1
      val n = nodes.size
      val degree = n - 1

      // Base Metropolis-Hastings: a_ij = 1 / max(d_i, d_j) = 1 / degree
      // Modulate by inverse latency for latency-awareness
      val baseMHWeight = 1.0 / degree

      // Compute raw latency-modulated weights
      val rawWeights: Map[String, Map[String, Double]] = nodes.map { i =>
        val neighborWeights = nodes.filter(_ != i).map { j =>
          val latI = latencies.getOrElse(i, 1.0)
          val latJ = latencies.getOrElse(j, 1.0)
          // Lower latency pair → higher weight. Use harmonic mean of inverse latencies.
          val latencyFactor = 2.0 / (latI + latJ)
          j -> (baseMHWeight * latencyFactor)
        }.toMap
        i -> neighborWeights
      }.toMap

      // Normalize to ensure doubly stochastic property:
      // 1. Cap off-diagonal weights so row sums ≤ 1
      // 2. Set self-weight = 1 - sum of off-diagonal
      normalizeDoublyStochastic(nodes, rawWeights)
    }
  }

  /**
   * Normalize a raw weight matrix to be doubly stochastic using
   * iterative Sinkhorn-like normalization on the off-diagonal entries,
   * then set diagonal entries so rows sum to 1.
   */
  private def normalizeDoublyStochastic(
    nodes: List[String],
    rawWeights: Map[String, Map[String, Double]]
  ): Map[String, Map[String, Double]] = {
    val n = nodes.size
    if n <= 1 then rawWeights
    else {
      // Step 1: Symmetrize off-diagonal weights: w_ij = w_ji = min(raw_ij, raw_ji)
      var symmetric = rawWeights
      for {
        i <- nodes
        j <- nodes if j != i
      } {
        val wij = rawWeights.getOrElse(i, Map.empty).getOrElse(j, 0.0)
        val wji = rawWeights.getOrElse(j, Map.empty).getOrElse(i, 0.0)
        val sym = math.min(wij, wji)
        symmetric = symmetric.updated(i, symmetric.getOrElse(i, Map.empty).updated(j, sym))
        symmetric = symmetric.updated(j, symmetric.getOrElse(j, Map.empty).updated(i, sym))
      }

      // Step 2: Scale so max row sum of off-diagonals is ≤ 0.9 (leaving room for self-weight)
      val maxRowSum = nodes.map { i =>
        symmetric.getOrElse(i, Map.empty).values.sum
      }.max

      val scale = if maxRowSum > 0.9 then 0.9 / maxRowSum else 1.0

      // Step 3: Build final matrix with self-weights
      nodes.map { i =>
        val offDiag = nodes.filter(_ != i).map { j =>
          j -> symmetric.getOrElse(i, Map.empty).getOrElse(j, 0.0) * scale
        }.toMap
        val selfWeight = 1.0 - offDiag.values.sum
        i -> (offDiag + (i -> selfWeight))
      }.toMap
    }
  }

  /**
   * Convert nested weight map to flat tuple-keyed map for WeightedAveraging.
   */
  def flattenWeights(
    weights: Map[String, Map[String, Double]]
  ): Map[(String, String), Double] =
    weights.flatMap { case (from, toMap) =>
      toMap.map { case (to, w) => (from, to) -> w }
    }

  /**
   * Extract latencies from clock readings (using round-trip latency).
   */
  def extractLatencies(readings: Map[String, ClockReading]): Map[String, Double] =
    readings.map { case (nodeId, reading) =>
      nodeId -> reading.roundTripLatencyNanos
    }

  /**
   * Build connectivity graph (fully connected for all known nodes).
   */
  def connectivity(nodeIds: Set[String]): Map[String, List[String]] =
    nodeIds.map { id =>
      id -> (nodeIds - id).toList.sorted
    }.toMap

  /**
   * Build node sync statuses from current consensus state.
   */
  def buildNodeStatuses(
    offsets: Map[String, Double],
    readings: Map[String, ClockReading],
    globalOffset: Double,
    roundNumber: Long,
    qualityThreshold: Double = 1_000_000.0
  ): List[NodeSyncStatus] =
    offsets.toList.map { case (nodeId, offset) =>
      val deviation = math.abs(offset - globalOffset)
      val quality = math.max(0.0, math.min(1.0, 1.0 - (deviation / qualityThreshold)))
      val latency = readings.get(nodeId).map(_.roundTripLatencyNanos).getOrElse(0.0)
      NodeSyncStatus(
        nodeId = nodeId,
        offsetNanos = offset,
        roundTripLatencyNanos = latency,
        syncQuality = quality,
        lastSeenRound = roundNumber
      )
    }
}
