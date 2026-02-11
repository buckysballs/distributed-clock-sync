package com.clocksync.rapp.models

import io.circe.*
import io.circe.generic.semiauto.*

/** Musical time state exposed to mixer clients. */
final case class SynthClockState(
  bpm: Double,
  beatPosition: Double,
  barPosition: Int,
  beatInBar: Int,
  phaseRadians: Double,
  globalNanos: Long,
  nextBeatNanos: Long,
  syncQuality: Double, // 0.0 to 1.0
  participantCount: Int,
  roundNumber: Long
)

object SynthClockState {
  given Encoder[SynthClockState] = deriveEncoder
  given Decoder[SynthClockState] = deriveDecoder

  val initial: SynthClockState = SynthClockState(
    bpm = 120.0,
    beatPosition = 0.0,
    barPosition = 0,
    beatInBar = 0,
    phaseRadians = 0.0,
    globalNanos = System.nanoTime(),
    nextBeatNanos = 0L,
    syncQuality = 0.0,
    participantCount = 0,
    roundNumber = 0L
  )
}

/** Lightweight beat info for the /clock/beat endpoint. */
final case class BeatInfo(
  beatPosition: Double,
  bpm: Double,
  phaseRadians: Double
)

object BeatInfo {
  given Encoder[BeatInfo] = deriveEncoder
  given Decoder[BeatInfo] = deriveDecoder

  def from(state: SynthClockState): BeatInfo =
    BeatInfo(state.beatPosition, state.bpm, state.phaseRadians)
}

/** A proposal to change the global tempo. */
final case class TempoProposal(
  proposalId: String,
  proposerNodeId: String,
  proposedBpm: Double,
  timestampNanos: Long,
  expiresAtNanos: Long
)

object TempoProposal {
  given Encoder[TempoProposal] = deriveEncoder
  given Decoder[TempoProposal] = deriveDecoder

  /** Proposals expire after 30 seconds. */
  val ExpiryDurationNanos: Long = 30_000_000_000L

  def create(proposerNodeId: String, proposedBpm: Double): TempoProposal = {
    val now = System.nanoTime()
    TempoProposal(
      proposalId = s"$proposerNodeId-$now",
      proposerNodeId = proposerNodeId,
      proposedBpm = proposedBpm,
      timestampNanos = now,
      expiresAtNanos = now + ExpiryDurationNanos
    )
  }
}

/** A node's vote on a pending tempo proposal. */
final case class TempoVote(
  proposalId: String,
  voterNodeId: String,
  accept: Boolean,
  timestampNanos: Long
)

object TempoVote {
  given Encoder[TempoVote] = deriveEncoder
  given Decoder[TempoVote] = deriveDecoder
}

/** Tracks pending proposals and votes for tempo consensus. */
final case class TempoConsensusState(
  pendingProposals: Map[String, TempoProposal],
  votes: Map[String, List[TempoVote]], // proposalId -> votes
  currentBpm: Double,
  participantCount: Int
) {
  /** Check if a proposal has achieved quorum (>50% accept). */
  def hasQuorum(proposalId: String): Boolean = {
    val proposalVotes = votes.getOrElse(proposalId, Nil)
    val acceptCount = proposalVotes.count(_.accept)
    acceptCount > participantCount / 2
  }

  /** Remove expired proposals. */
  def pruneExpired(nowNanos: Long): TempoConsensusState = {
    val (active, _) = pendingProposals.partition { case (_, p) =>
      p.expiresAtNanos > nowNanos
    }
    val activeIds = active.keySet
    copy(
      pendingProposals = active,
      votes = votes.filter { case (id, _) => activeIds.contains(id) }
    )
  }

  /** Add a vote and return updated state + whether the proposal just reached quorum. */
  def addVote(vote: TempoVote): (TempoConsensusState, Option[Double]) = {
    val updatedVotes = votes.updated(
      vote.proposalId,
      votes.getOrElse(vote.proposalId, Nil) :+ vote
    )
    val updated = copy(votes = updatedVotes)
    val newBpm = if updated.hasQuorum(vote.proposalId) then
      pendingProposals.get(vote.proposalId).map(_.proposedBpm)
    else None
    (updated, newBpm)
  }
}

object TempoConsensusState {
  given Encoder[TempoConsensusState] = deriveEncoder
  given Decoder[TempoConsensusState] = deriveDecoder

  def initial(bpm: Double = 120.0): TempoConsensusState =
    TempoConsensusState(
      pendingProposals = Map.empty,
      votes = Map.empty,
      currentBpm = bpm,
      participantCount = 1
    )
}

/** MIDI clock pulse info (24 PPQ). */
final case class MidiClockPulse(
  pulseNumber: Long,
  nanosPerPulse: Long,
  bpm: Double,
  beatPosition: Double
)

object MidiClockPulse {
  given Encoder[MidiClockPulse] = deriveEncoder
  given Decoder[MidiClockPulse] = deriveDecoder

  val PulsesPerQuarterNote: Int = 24

  def from(state: SynthClockState): MidiClockPulse = {
    val nanosPerBeat = (60.0 / state.bpm * 1_000_000_000.0).toLong
    val nanosPerPulse = nanosPerBeat / PulsesPerQuarterNote
    val pulseNumber = (state.beatPosition * PulsesPerQuarterNote).toLong
    MidiClockPulse(pulseNumber, nanosPerPulse, state.bpm, state.beatPosition)
  }
}

/** Per-node sync quality and latency info. */
final case class NodeSyncStatus(
  nodeId: String,
  offsetNanos: Double,
  roundTripLatencyNanos: Double,
  syncQuality: Double,
  lastSeenRound: Long
)

object NodeSyncStatus {
  given Encoder[NodeSyncStatus] = deriveEncoder
  given Decoder[NodeSyncStatus] = deriveDecoder
}

/** Topology info for the /clock/sync/topology endpoint. */
final case class TopologyInfo(
  nodes: List[NodeSyncStatus],
  connectivity: Map[String, List[String]],
  weightMatrix: Map[String, Map[String, Double]]
)

object TopologyInfo {
  given Encoder[TopologyInfo] = deriveEncoder
  given Decoder[TopologyInfo] = deriveDecoder
}

/** Sync status for the /clock/sync/status endpoint. */
final case class SyncStatusInfo(
  converged: Boolean,
  convergenceError: Double,
  participantCount: Int,
  roundNumber: Long,
  syncQuality: Double,
  currentBpm: Double
)

object SyncStatusInfo {
  given Encoder[SyncStatusInfo] = deriveEncoder
  given Decoder[SyncStatusInfo] = deriveDecoder
}
