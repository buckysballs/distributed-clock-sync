package com.clocksync.rapp.models

import io.circe.*
import io.circe.generic.semiauto.*

/** A node's local clock snapshot submitted to L0. */
final case class ClockReading(
  nodeId: String,
  monotonicOffsetNanos: Double,
  roundTripLatencyNanos: Double,
  roundNumber: Long,
  timestampNanos: Long
)

object ClockReading {
  given Encoder[ClockReading] = deriveEncoder
  given Decoder[ClockReading] = deriveDecoder
}

/** Full state of one consensus round. */
final case class ConsensusRoundState(
  roundNumber: Long,
  readings: Map[String, ClockReading],
  weights: Map[String, Map[String, Double]],
  previousOffsets: Map[String, Double],
  currentOffsets: Map[String, Double],
  converged: Boolean,
  convergenceError: Double,
  processingDepth: Int
)

object ConsensusRoundState {
  given Encoder[ConsensusRoundState] = deriveEncoder
  given Decoder[ConsensusRoundState] = deriveDecoder
}

/** Output of consensus: the synchronized global clock. */
final case class SynchronizedClock(
  globalOffsetNanos: Double,
  bpmEstimate: Double,
  convergenceError: Double,
  participantCount: Int,
  roundNumber: Long
)

object SynchronizedClock {
  given Encoder[SynchronizedClock] = deriveEncoder
  given Decoder[SynchronizedClock] = deriveDecoder
}

/** L0 pipeline input: a batch of clock readings from the network. */
final case class ClockL0Input(
  readings: List[ClockReading],
  roundNumber: Long,
  processingDepth: Int = 3
)

object ClockL0Input {
  given Encoder[ClockL0Input] = deriveEncoder
  given Decoder[ClockL0Input] = deriveDecoder
}

/** L0 pipeline output passed to L1 state channel. */
final case class ClockL0Output(
  readings: Map[String, ClockReading],
  latencies: Map[String, Double],
  roundNumber: Long,
  processingDepth: Int = 2
)

object ClockL0Output {
  given Encoder[ClockL0Output] = deriveEncoder
  given Decoder[ClockL0Output] = deriveDecoder
}

/** Final result from the L1 state channel after consensus. */
final case class ClockSyncResult(
  synchronizedClock: SynchronizedClock,
  roundState: ConsensusRoundState,
  proofHash: Option[String],
  processingDepth: Int = 0
)

object ClockSyncResult {
  given Encoder[ClockSyncResult] = deriveEncoder
  given Decoder[ClockSyncResult] = deriveDecoder
}

/**
 * Processing states for the safe hylo pattern in the L1 state channel.
 * Depth strictly decreases: 3 -> 2 -> 1 -> 0
 */
enum ClockProcessingState(val depth: Int) {
  case ClockReadingReceived(
    readings: Map[String, ClockReading],
    latencies: Map[String, Double],
    roundNumber: Long
  ) extends ClockProcessingState(3)

  case WeightsComputed(
    readings: Map[String, ClockReading],
    weights: Map[String, Map[String, Double]],
    previousOffsets: Map[String, Double],
    roundNumber: Long
  ) extends ClockProcessingState(2)

  case AveragingComputed(
    roundState: ConsensusRoundState,
    synchronizedClock: SynchronizedClock
  ) extends ClockProcessingState(1)

  case ClockSyncComplete(
    result: ClockSyncResult
  ) extends ClockProcessingState(0)
}

object ClockProcessingState {
  given Encoder[ClockProcessingState] = Encoder.instance {
    case s: ClockProcessingState.ClockReadingReceived =>
      Json.obj(
        "type" -> Json.fromString("ClockReadingReceived"),
        "depth" -> Json.fromInt(s.depth),
        "roundNumber" -> Json.fromLong(s.roundNumber)
      )
    case s: ClockProcessingState.WeightsComputed =>
      Json.obj(
        "type" -> Json.fromString("WeightsComputed"),
        "depth" -> Json.fromInt(s.depth),
        "roundNumber" -> Json.fromLong(s.roundNumber)
      )
    case s: ClockProcessingState.AveragingComputed =>
      Json.obj(
        "type" -> Json.fromString("AveragingComputed"),
        "depth" -> Json.fromInt(s.depth),
        "synchronizedClock" -> Encoder[SynchronizedClock].apply(s.synchronizedClock)
      )
    case s: ClockProcessingState.ClockSyncComplete =>
      Json.obj(
        "type" -> Json.fromString("ClockSyncComplete"),
        "depth" -> Json.fromInt(s.depth),
        "result" -> Encoder[ClockSyncResult].apply(s.result)
      )
  }
}
