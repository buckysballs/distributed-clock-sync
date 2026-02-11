package com.clocksync.rapp

import cats.effect.IO
import com.clocksync.rapp.consensus.*
import com.clocksync.rapp.models.*
import weaver.*

object ClockConsensusSpec extends SimpleIOSuite {

  // --- WeightedAveraging Tests ---

  test("computeNextOffset: uniform weights produce average") {
    val offsets = Map("A" -> 100.0, "B" -> 200.0, "C" -> 300.0)
    val weights = Map(
      ("A", "A") -> 1.0/3, ("A", "B") -> 1.0/3, ("A", "C") -> 1.0/3,
      ("B", "A") -> 1.0/3, ("B", "B") -> 1.0/3, ("B", "C") -> 1.0/3,
      ("C", "A") -> 1.0/3, ("C", "B") -> 1.0/3, ("C", "C") -> 1.0/3
    )
    val result = WeightedAveraging.computeNextOffset("A", offsets, weights)
    IO(expect(math.abs(result - 200.0) < 1e-10))
  }

  test("computeRound: single round moves offsets toward average") {
    val offsets = Map("A" -> 0.0, "B" -> 1000.0, "C" -> 2000.0)
    val weights = Map(
      ("A", "A") -> 0.5, ("A", "B") -> 0.25, ("A", "C") -> 0.25,
      ("B", "A") -> 0.25, ("B", "B") -> 0.5, ("B", "C") -> 0.25,
      ("C", "A") -> 0.25, ("C", "B") -> 0.25, ("C", "C") -> 0.5
    )
    val before = WeightedAveraging.convergenceError(offsets)
    val next = WeightedAveraging.computeRound(offsets, weights)
    val after = WeightedAveraging.convergenceError(next)
    IO(expect(after < before))
  }

  test("runUntilConverged: 3-node complete graph converges to mean") {
    val offsets = Map("A" -> 0.0, "B" -> 1000.0, "C" -> 2000.0)
    val weights = Map(
      ("A", "A") -> 0.5, ("A", "B") -> 0.25, ("A", "C") -> 0.25,
      ("B", "A") -> 0.25, ("B", "B") -> 0.5, ("B", "C") -> 0.25,
      ("C", "A") -> 0.25, ("C", "B") -> 0.25, ("C", "C") -> 0.5
    )
    // Use tight epsilon (0.01 nanos) to force convergence all the way to the mean
    val (result, rounds, converged) = WeightedAveraging.runUntilConverged(
      offsets, weights, epsilon = 0.01, maxIterations = 500
    )
    val mean = 1000.0 // (0 + 1000 + 2000) / 3

    IO {
      expect(converged) and
      expect(rounds > 0) and
      expect(math.abs(result("A") - mean) < 1.0) and
      expect(math.abs(result("B") - mean) < 1.0) and
      expect(math.abs(result("C") - mean) < 1.0)
    }
  }

  test("runUntilConverged: large offset spread converges") {
    // Simulating nodes with very different clock readings (10ms spread)
    val offsets = Map(
      "node1" -> 0.0,
      "node2" -> 5_000_000.0,  // 5ms ahead
      "node3" -> 10_000_000.0  // 10ms ahead
    )
    val weights = Map(
      ("node1", "node1") -> 0.5, ("node1", "node2") -> 0.25, ("node1", "node3") -> 0.25,
      ("node2", "node1") -> 0.25, ("node2", "node2") -> 0.5, ("node2", "node3") -> 0.25,
      ("node3", "node1") -> 0.25, ("node3", "node2") -> 0.25, ("node3", "node3") -> 0.5
    )
    val (result, _, converged) = WeightedAveraging.runUntilConverged(
      offsets, weights, epsilon = 1000.0, maxIterations = 200
    )

    IO {
      expect(converged) and
      expect(WeightedAveraging.convergenceError(result) < 1000.0)
    }
  }

  test("convergenceError: identical offsets have zero error") {
    val offsets = Map("A" -> 500.0, "B" -> 500.0, "C" -> 500.0)
    IO(expect(WeightedAveraging.convergenceError(offsets) == 0.0))
  }

  test("convergenceError: empty map has zero error") {
    IO(expect(WeightedAveraging.convergenceError(Map.empty) == 0.0))
  }

  test("isConverged: single node always converged") {
    IO(expect(WeightedAveraging.isConverged(Map("A" -> 42.0))))
  }

  test("syncQuality: zero error gives quality 1.0") {
    IO(expect(WeightedAveraging.syncQuality(0.0) == 1.0))
  }

  test("syncQuality: error at threshold gives quality 0.0") {
    IO(expect(WeightedAveraging.syncQuality(1_000_000.0) == 0.0))
  }

  test("syncQuality: half threshold gives quality 0.5") {
    IO(expect(math.abs(WeightedAveraging.syncQuality(500_000.0) - 0.5) < 1e-10))
  }

  test("computeBeatPosition: 120 BPM, 1 second = 2 beats") {
    val pos = WeightedAveraging.computeBeatPosition(1_000_000_000L, 120.0, 0L)
    IO(expect(math.abs(pos - 2.0) < 1e-10))
  }

  test("computePhase: half beat = pi radians") {
    val phase = WeightedAveraging.computePhase(250_000_000L, 120.0, 0L)
    IO(expect(math.abs(phase - math.Pi) < 1e-6))
  }

  test("computeNextBeatNanos: correct next beat timestamp") {
    // At 120 BPM, beats are at 0, 500ms, 1000ms, ...
    // At 100ms, next beat should be at 500ms = 500_000_000 nanos
    val next = WeightedAveraging.computeNextBeatNanos(100_000_000L, 120.0, 0L)
    IO(expect(next == 500_000_000L))
  }

  // --- TopologyManager Tests ---

  test("computeWeights: single node gets self-weight 1.0") {
    val weights = TopologyManager.computeWeights(Set("A"), Map("A" -> 1.0))
    IO(expect(weights("A")("A") == 1.0))
  }

  test("computeWeights: two nodes have symmetric weights") {
    val weights = TopologyManager.computeWeights(
      Set("A", "B"),
      Map("A" -> 1000.0, "B" -> 1000.0)
    )
    IO {
      expect(math.abs(weights("A")("B") - weights("B")("A")) < 1e-10) and
      expect(math.abs(weights("A").values.sum - 1.0) < 1e-10) and
      expect(math.abs(weights("B").values.sum - 1.0) < 1e-10)
    }
  }

  test("computeWeights: rows sum to 1.0 (stochastic)") {
    val weights = TopologyManager.computeWeights(
      Set("A", "B", "C"),
      Map("A" -> 1000.0, "B" -> 2000.0, "C" -> 1500.0)
    )
    IO {
      expect(math.abs(weights("A").values.sum - 1.0) < 1e-10) and
      expect(math.abs(weights("B").values.sum - 1.0) < 1e-10) and
      expect(math.abs(weights("C").values.sum - 1.0) < 1e-10)
    }
  }

  test("computeWeights: lower latency peers get higher weight") {
    val weights = TopologyManager.computeWeights(
      Set("A", "B", "C"),
      Map("A" -> 100.0, "B" -> 100.0, "C" -> 10000.0) // C has 100x latency
    )
    // From A's perspective, B should have higher weight than C
    IO(expect(weights("A")("B") > weights("A")("C")))
  }

  test("flattenWeights: produces correct tuple keys") {
    val nested = Map(
      "A" -> Map("A" -> 0.5, "B" -> 0.5),
      "B" -> Map("A" -> 0.5, "B" -> 0.5)
    )
    val flat = TopologyManager.flattenWeights(nested)
    IO {
      expect(flat(("A", "A")) == 0.5) and
      expect(flat(("A", "B")) == 0.5) and
      expect(flat.size == 4)
    }
  }

  // --- TempoConsensusState Tests ---

  test("TempoConsensusState: proposal reaches quorum with majority votes") {
    val state = TempoConsensusState(
      pendingProposals = Map("p1" -> TempoProposal("p1", "node1", 140.0, 0L, Long.MaxValue)),
      votes = Map.empty,
      currentBpm = 120.0,
      participantCount = 3
    )
    val vote1 = TempoVote("p1", "node2", accept = true, System.nanoTime())
    val (s1, bpm1) = state.addVote(vote1)
    // 1 of 3 accept → no quorum yet
    val vote2 = TempoVote("p1", "node3", accept = true, System.nanoTime())
    val (s2, bpm2) = s1.addVote(vote2)
    // 2 of 3 accept → quorum!
    IO {
      expect(bpm1.isEmpty) and
      expect(bpm2.contains(140.0))
    }
  }

  test("TempoConsensusState: rejected votes don't reach quorum") {
    val state = TempoConsensusState(
      pendingProposals = Map("p1" -> TempoProposal("p1", "node1", 140.0, 0L, Long.MaxValue)),
      votes = Map.empty,
      currentBpm = 120.0,
      participantCount = 3
    )
    val vote1 = TempoVote("p1", "node2", accept = false, System.nanoTime())
    val (s1, bpm1) = state.addVote(vote1)
    val vote2 = TempoVote("p1", "node3", accept = false, System.nanoTime())
    val (s2, bpm2) = s1.addVote(vote2)
    IO {
      expect(bpm1.isEmpty) and
      expect(bpm2.isEmpty)
    }
  }

  test("TempoConsensusState: pruneExpired removes old proposals") {
    val now = System.nanoTime()
    val state = TempoConsensusState(
      pendingProposals = Map(
        "active" -> TempoProposal("active", "n1", 130.0, now, now + 30_000_000_000L),
        "expired" -> TempoProposal("expired", "n2", 140.0, now - 60_000_000_000L, now - 30_000_000_000L)
      ),
      votes = Map("active" -> Nil, "expired" -> Nil),
      currentBpm = 120.0,
      participantCount = 2
    )
    val pruned = state.pruneExpired(now)
    IO {
      expect(pruned.pendingProposals.contains("active")) and
      expect(!pruned.pendingProposals.contains("expired")) and
      expect(!pruned.votes.contains("expired"))
    }
  }

  // --- State Channel Processing Tests ---

  test("ClockConsensusStateChannelTyped: processes readings through safe hylo") {
    val readings = Map(
      "A" -> ClockReading("A", 0.0, 1000.0, 1L, System.nanoTime()),
      "B" -> ClockReading("B", 500.0, 1200.0, 1L, System.nanoTime()),
      "C" -> ClockReading("C", 1000.0, 800.0, 1L, System.nanoTime())
    )
    val input = ClockL0Output(
      readings = readings,
      latencies = Map("A" -> 1000.0, "B" -> 1200.0, "C" -> 800.0),
      roundNumber = 1L
    )

    ClockConsensusStateChannelTyped.processInput(
      input,
      currentBpm = 120.0,
      previousOffsets = Map.empty,
      referenceNanos = 0L
    ).map { result =>
      expect(result.synchronizedClock.participantCount == 3) and
      expect(result.synchronizedClock.roundNumber == 1L) and
      expect(result.proofHash.isDefined) and
      expect(result.processingDepth == 0)
    }
  }

  test("ClockProcessingState: depths are strictly decreasing") {
    IO {
      val received = ClockProcessingState.ClockReadingReceived(Map.empty, Map.empty, 0L)
      val computed = ClockProcessingState.WeightsComputed(Map.empty, Map.empty, Map.empty, 0L)
      val averaged = ClockProcessingState.AveragingComputed(
        ConsensusRoundState(0, Map.empty, Map.empty, Map.empty, Map.empty, true, 0.0, 0),
        SynchronizedClock(0.0, 120.0, 0.0, 0, 0)
      )
      val complete = ClockProcessingState.ClockSyncComplete(
        ClockSyncResult(SynchronizedClock(0.0, 120.0, 0.0, 0, 0),
          ConsensusRoundState(0, Map.empty, Map.empty, Map.empty, Map.empty, true, 0.0, 0),
          None)
      )

      expect(received.depth == 3) and
      expect(computed.depth == 2) and
      expect(averaged.depth == 1) and
      expect(complete.depth == 0) and
      expect(received.depth > computed.depth) and
      expect(computed.depth > averaged.depth) and
      expect(averaged.depth > complete.depth)
    }
  }
}
