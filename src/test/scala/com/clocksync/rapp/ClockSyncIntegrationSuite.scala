package com.clocksync.rapp

import cats.effect.IO
import cats.effect.std.Queue
import com.clocksync.rapp.consensus.*
import com.clocksync.rapp.models.*
import com.clocksync.rapp.pipeline.ClockPipeline
import weaver.*

object ClockSyncIntegrationSuite extends SimpleIOSuite {

  test("Full pipeline: readings -> consensus -> synth clock state") {
    for {
      state <- ClockPipeline.initState(120.0)
      readings = List(
        ClockReading("node-A", 0.0, 1000.0, 1L, System.nanoTime()),
        ClockReading("node-B", 500_000.0, 1200.0, 1L, System.nanoTime()),
        ClockReading("node-C", 1_000_000.0, 800.0, 1L, System.nanoTime())
      )
      result <- ClockPipeline.runPipeline(state, readings)
      synthClock <- state.synthClockRef.get
      topology <- state.topologyRef.get
    } yield {
      expect(result.synchronizedClock.participantCount == 3) and
      expect(result.synchronizedClock.convergenceError >= 0.0) and
      expect(synthClock.bpm == 120.0) and
      expect(synthClock.participantCount == 3) and
      expect(topology.nodes.size == 3)
    }
  }

  test("Pipeline: multiple rounds improve convergence") {
    for {
      state <- ClockPipeline.initState(120.0)
      readings = List(
        ClockReading("A", 0.0, 500.0, 1L, System.nanoTime()),
        ClockReading("B", 5_000_000.0, 600.0, 1L, System.nanoTime()),
        ClockReading("C", 10_000_000.0, 400.0, 1L, System.nanoTime())
      )
      r1 <- ClockPipeline.runPipeline(state, readings)
      // Second round with updated offsets from first round
      readings2 = readings.map(r => r.copy(
        monotonicOffsetNanos = r1.roundState.currentOffsets.getOrElse(r.nodeId, r.monotonicOffsetNanos)
      ))
      r2 <- ClockPipeline.runPipeline(state, readings2)
    } yield {
      // Second round should have same or better convergence
      expect(r2.synchronizedClock.convergenceError <= r1.synchronizedClock.convergenceError + 1.0)
    }
  }

  test("Pipeline: single node pipeline works") {
    for {
      state <- ClockPipeline.initState(140.0)
      readings = List(
        ClockReading("solo", 0.0, 0.0, 1L, System.nanoTime())
      )
      result <- ClockPipeline.runPipeline(state, readings)
      synthClock <- state.synthClockRef.get
    } yield {
      expect(result.synchronizedClock.participantCount == 1) and
      expect(result.synchronizedClock.convergenceError == 0.0) and
      expect(synthClock.bpm == 140.0)
    }
  }

  test("Tempo voting: full flow from proposal to acceptance") {
    for {
      state <- ClockPipeline.initState(120.0)
      // Create proposal
      proposal = TempoProposal.create("node-1", 140.0)
      _ <- state.tempoConsensusRef.update { tc =>
        tc.copy(
          pendingProposals = tc.pendingProposals + (proposal.proposalId -> proposal),
          participantCount = 3
        )
      }
      // Vote accept from node-2
      vote1 = TempoVote(proposal.proposalId, "node-2", accept = true, System.nanoTime())
      result1 <- state.tempoConsensusRef.modify { tc =>
        val (updated, bpm) = tc.addVote(vote1)
        (updated, bpm)
      }
      // Vote accept from node-3 (reaching quorum)
      vote2 = TempoVote(proposal.proposalId, "node-3", accept = true, System.nanoTime())
      result2 <- state.tempoConsensusRef.modify { tc =>
        val (updated, bpm) = tc.addVote(vote2)
        (updated, bpm)
      }
      // Apply BPM change
      _ <- result2 match {
        case Some(newBpm) => state.bpmRef.set(newBpm)
        case None => IO.unit
      }
      bpm <- state.bpmRef.get
    } yield {
      expect(result1.isEmpty) and      // First vote: no quorum yet
      expect(result2.contains(140.0)) and  // Second vote: quorum!
      expect(bpm == 140.0)
    }
  }

  test("OSC message encoding/decoding roundtrip") {
    import OscClockBridge.*
    val original = ("/clock/beat", List(OscFloat(4.5f), OscFloat(120.0f), OscFloat(3.14f)))
    val encoded = encodeOscMessage(original._1, original._2)
    val decoded = parseOscMessage(encoded)

    IO {
      decoded match {
        case Some((addr, args)) =>
          expect(addr == "/clock/beat") and
          expect(args.length == 3) and {
            args match {
              case List(OscFloat(a), OscFloat(b), OscFloat(c)) =>
                expect(math.abs(a - 4.5f) < 0.001f) and
                expect(math.abs(b - 120.0f) < 0.001f) and
                expect(math.abs(c - 3.14f) < 0.01f)
              case _ => failure("Wrong arg types")
            }
          }
        case None => failure("Failed to decode OSC message")
      }
    }
  }

  test("OSC message encoding: integer arguments") {
    import OscClockBridge.*
    val original = ("/clock/sync", List(OscFloat(0.95f), OscInt(3), OscInt(42)))
    val encoded = encodeOscMessage(original._1, original._2)
    val decoded = parseOscMessage(encoded)

    IO {
      decoded match {
        case Some((addr, args)) =>
          expect(addr == "/clock/sync") and
          expect(args.length == 3) and {
            args match {
              case List(OscFloat(q), OscInt(p), OscInt(r)) =>
                expect(math.abs(q - 0.95f) < 0.001f) and
                expect(p == 3) and
                expect(r == 42)
              case _ => failure("Wrong arg types")
            }
          }
        case None => failure("Failed to decode OSC message")
      }
    }
  }

  test("WASM executor: weighted average matches Scala implementation") {
    import com.clocksync.rapp.wasm.ClockConsensusWasmExecutor
    val offsets = Array(100.0, 200.0, 300.0)
    val weights = Array(1.0/3, 1.0/3, 1.0/3)

    for {
      result <- ClockConsensusWasmExecutor.executeWeightedAverage(offsets, weights, 0)
      scalaResult = WeightedAveraging.computeNextOffset(
        "A",
        Map("A" -> 100.0, "B" -> 200.0, "C" -> 300.0),
        Map(
          ("A", "A") -> 1.0/3, ("A", "B") -> 1.0/3, ("A", "C") -> 1.0/3
        )
      )
    } yield {
      expect(result.functionName == "weighted_average") and
      expect(math.abs(result.result.toDouble - scalaResult) < 1e-10) and
      expect(result.proofHash.nonEmpty)
    }
  }

  test("SynthClockState: MIDI pulse computation") {
    val state = SynthClockState(
      bpm = 120.0,
      beatPosition = 4.5,
      barPosition = 1,
      beatInBar = 0,
      phaseRadians = math.Pi,
      globalNanos = 2_250_000_000L,
      nextBeatNanos = 2_500_000_000L,
      syncQuality = 0.99,
      participantCount = 3,
      roundNumber = 10
    )
    val pulse = MidiClockPulse.from(state)
    IO {
      expect(pulse.bpm == 120.0) and
      expect(pulse.nanosPerPulse == 500_000_000L / 24) and // 500ms per beat / 24 PPQ
      expect(pulse.pulseNumber == (4.5 * 24).toLong)
    }
  }
}
