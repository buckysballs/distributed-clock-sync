package com.clocksync.rapp

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*
import com.clocksync.rapp.consensus.WeightedAveraging
import com.clocksync.rapp.models.*
import com.clocksync.rapp.pipeline.ClockPipeline
import fs2.Stream
import io.circe.syntax.*
import io.circe.parser.decode
import org.http4s.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.dsl.io.*
import org.http4s.headers.*
import org.http4s.server.websocket.WebSocketBuilder2

import scala.concurrent.duration.*

/**
 * HTTP/SSE/WebSocket API routes for mixer software integration.
 *
 * Follows ZKWasmExecutionRoutes pattern from rAppGenius.
 * All routes backed by shared Cats Effect Ref[IO, SynthClockState].
 */
object SynthClockRoutes {

  /**
   * Standard HTTP + SSE routes.
   */
  def routes(
    state: ClockPipeline.PipelineState,
    readingsQueue: Queue[IO, ClockReading],
    nodeId: String
  ): HttpRoutes[IO] = HttpRoutes.of[IO] {

    // GET /health - Health check
    case GET -> Root / "health" =>
      Ok(io.circe.Json.obj(
        "status" -> io.circe.Json.fromString("ok"),
        "nodeId" -> io.circe.Json.fromString(nodeId),
        "timestamp" -> io.circe.Json.fromLong(System.nanoTime())
      ))

    // GET /clock - Full SynthClockState
    case GET -> Root / "clock" =>
      state.synthClockRef.get.flatMap(clock => Ok(clock.asJson))

    // GET /clock/beat - Lightweight beat info
    case GET -> Root / "clock" / "beat" =>
      state.synthClockRef.get.flatMap(clock => Ok(BeatInfo.from(clock).asJson))

    // GET /clock/midi - MIDI clock pulse (24 PPQ)
    case GET -> Root / "clock" / "midi" =>
      state.synthClockRef.get.flatMap(clock => Ok(MidiClockPulse.from(clock).asJson))

    // POST /clock/tempo - Submit tempo proposal
    case req @ POST -> Root / "clock" / "tempo" =>
      for {
        body <- req.as[io.circe.Json]
        proposedBpm = body.hcursor.get[Double]("bpm").getOrElse(120.0)
        proposal = TempoProposal.create(nodeId, proposedBpm)
        _ <- state.tempoConsensusRef.update { tc =>
          tc.copy(
            pendingProposals = tc.pendingProposals + (proposal.proposalId -> proposal)
          )
        }
        resp <- Ok(proposal.asJson)
      } yield resp

    // POST /clock/tempo/vote - Vote on pending tempo proposal
    case req @ POST -> Root / "clock" / "tempo" / "vote" =>
      for {
        vote <- req.as[TempoVote]
        result <- state.tempoConsensusRef.modify { tc =>
          val (updated, newBpm) = tc.addVote(vote)
          (updated, newBpm)
        }
        // If quorum reached, update BPM
        _ <- result match {
          case Some(newBpm) =>
            state.bpmRef.set(newBpm) *>
            state.tempoConsensusRef.update(_.copy(currentBpm = newBpm))
          case None => IO.unit
        }
        resp <- Ok(io.circe.Json.obj(
          "accepted" -> io.circe.Json.fromBoolean(true),
          "quorumReached" -> io.circe.Json.fromBoolean(result.isDefined),
          "newBpm" -> result.fold(io.circe.Json.Null)(io.circe.Json.fromDoubleOrNull(_))
        ))
      } yield resp

    // GET /clock/tempo/pending - View pending proposals
    case GET -> Root / "clock" / "tempo" / "pending" =>
      for {
        tc <- state.tempoConsensusRef.get
        pruned = tc.pruneExpired(System.nanoTime())
        _ <- state.tempoConsensusRef.set(pruned)
        resp <- Ok(io.circe.Json.obj(
          "proposals" -> pruned.pendingProposals.values.toList.asJson,
          "votes" -> pruned.votes.asJson,
          "currentBpm" -> io.circe.Json.fromDoubleOrNull(pruned.currentBpm),
          "participantCount" -> io.circe.Json.fromInt(pruned.participantCount)
        ))
      } yield resp

    // GET /clock/sync/status - Convergence info
    case GET -> Root / "clock" / "sync" / "status" =>
      for {
        clock <- state.synthClockRef.get
        offsets <- state.offsetsRef.get
        error = WeightedAveraging.convergenceError(offsets)
        status = SyncStatusInfo(
          converged = WeightedAveraging.isConverged(offsets),
          convergenceError = error,
          participantCount = clock.participantCount,
          roundNumber = clock.roundNumber,
          syncQuality = clock.syncQuality,
          currentBpm = clock.bpm
        )
        resp <- Ok(status.asJson)
      } yield resp

    // GET /clock/sync/topology - Node latencies, connectivity
    case GET -> Root / "clock" / "sync" / "topology" =>
      state.topologyRef.get.flatMap(topo => Ok(topo.asJson))

    // GET /clock/stream - SSE stream at ~100Hz
    case GET -> Root / "clock" / "stream" =>
      val stream: Stream[IO, ServerSentEvent] =
        Stream.awakeEvery[IO](10.millis).evalMap { _ =>
          state.synthClockRef.get.map { clock =>
            ServerSentEvent(data = Some(clock.asJson.noSpaces), eventType = Some("clock"))
          }
        }
      Ok(stream).map(_.withContentType(
        `Content-Type`(MediaType.`text/event-stream`)
      ))

    // POST /clock/reading - Submit a clock reading (for peer nodes)
    case req @ POST -> Root / "clock" / "reading" =>
      for {
        reading <- req.as[ClockReading]
        _ <- readingsQueue.offer(reading)
        resp <- Ok(io.circe.Json.obj("accepted" -> io.circe.Json.fromBoolean(true)))
      } yield resp
  }

  /**
   * WebSocket routes.
   *
   * /clock/ws - Bidirectional WebSocket:
   *   Server pushes SynthClockState at ~100Hz
   *   Client can send tempo proposals as JSON
   */
  def wsRoutes(
    state: ClockPipeline.PipelineState,
    readingsQueue: Queue[IO, ClockReading],
    nodeId: String
  )(wsBuilder: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "clock" / "ws" =>
      val send: Stream[IO, org.http4s.websocket.WebSocketFrame] =
        Stream.awakeEvery[IO](10.millis).evalMap { _ =>
          state.synthClockRef.get.map { clock =>
            org.http4s.websocket.WebSocketFrame.Text(clock.asJson.noSpaces)
          }
        }

      val receive: fs2.Pipe[IO, org.http4s.websocket.WebSocketFrame, Unit] =
        _.evalMap {
          case org.http4s.websocket.WebSocketFrame.Text(text, _) =>
            // Try to parse as tempo proposal
            decode[io.circe.Json](text) match {
              case Right(json) =>
                json.hcursor.get[Double]("bpm") match {
                  case Right(bpm) =>
                    val proposal = TempoProposal.create(nodeId, bpm)
                    state.tempoConsensusRef.update { tc =>
                      tc.copy(
                        pendingProposals = tc.pendingProposals + (proposal.proposalId -> proposal)
                      )
                    }
                  case Left(_) => IO.unit
                }
              case Left(_) => IO.unit
            }
          case _ => IO.unit
        }

      wsBuilder.build(send, receive)
  }
}
