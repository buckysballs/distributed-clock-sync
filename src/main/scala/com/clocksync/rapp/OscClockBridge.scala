package com.clocksync.rapp

import cats.effect.*
import cats.effect.std.Queue
import com.clocksync.rapp.consensus.WeightedAveraging
import com.clocksync.rapp.models.*
import com.clocksync.rapp.pipeline.ClockPipeline
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

import java.net.{DatagramPacket, DatagramSocket, InetAddress}
import java.nio.{ByteBuffer, ByteOrder}
import scala.concurrent.duration.*

/**
 * OSC (Open Sound Control) UDP bridge for music software integration.
 *
 * Sends clock data as OSC messages for consumption by Ableton, Max/MSP,
 * SuperCollider, and other OSC-compatible music software.
 *
 * Outgoing messages (to configured clients):
 *   /clock/beat  (f:beatPos, f:bpm, f:phase)    at ~100Hz
 *   /clock/pulse ()                               at 24 PPQ
 *   /clock/bar   ()                               on each downbeat
 *   /clock/sync  (f:quality, i:participants, i:round) at 1Hz
 *
 * Incoming messages (received on listen port):
 *   /clock/tempo (f:bpm)  - Submit tempo proposal
 *
 * Uses raw UDP datagrams with OSC binary encoding (no JavaOSC dependency
 * required for the core protocol - we implement the OSC wire format directly
 * for minimal dependencies).
 */
object OscClockBridge {

  private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  /** Default port for OSC communication. */
  val DefaultPort: Int = 9050

  /** Default client target (localhost, can be configured). */
  val DefaultClientHost: String = "127.0.0.1"
  val DefaultClientPort: Int = 9051

  /**
   * Start the OSC bridge: sends beat/pulse/bar/sync messages and listens for tempo proposals.
   */
  def start(
    state: ClockPipeline.PipelineState,
    readingsQueue: Queue[IO, ClockReading],
    port: Int = DefaultPort,
    nodeId: String = "node-0"
  ): IO[Unit] = {
    val clientHost = sys.env.getOrElse("OSC_CLIENT_HOST", DefaultClientHost)
    val clientPort = sys.env.getOrElse("OSC_CLIENT_PORT", DefaultClientPort.toString).toInt

    for {
      _ <- logger.info(s"Starting OSC bridge on port $port, sending to $clientHost:$clientPort")
      socket <- IO(new DatagramSocket(port))
      clientAddr <- IO(InetAddress.getByName(clientHost))

      // Start sender loops in parallel
      beatFiber <- sendBeatLoop(state, socket, clientAddr, clientPort).start
      pulseFiber <- sendPulseLoop(state, socket, clientAddr, clientPort).start
      syncFiber <- sendSyncLoop(state, socket, clientAddr, clientPort).start
      receiveFiber <- receiveLoop(state, socket, nodeId).start

      // Keep running until cancelled
      _ <- IO.never[Unit].guarantee(
        IO(socket.close()) *>
        beatFiber.cancel *>
        pulseFiber.cancel *>
        syncFiber.cancel *>
        receiveFiber.cancel *>
        logger.info("OSC bridge stopped")
      )
    } yield ()
  }

  /**
   * Send /clock/beat messages at ~100Hz.
   * Format: /clock/beat ,fff <beatPos> <bpm> <phase>
   */
  private def sendBeatLoop(
    state: ClockPipeline.PipelineState,
    socket: DatagramSocket,
    clientAddr: InetAddress,
    clientPort: Int
  ): IO[Unit] = {
    def loop: IO[Unit] =
      for {
        _ <- IO.sleep(10.millis)
        clock <- state.synthClockRef.get
        msg = encodeOscMessage(
          "/clock/beat",
          List(
            OscFloat(clock.beatPosition.toFloat),
            OscFloat(clock.bpm.toFloat),
            OscFloat(clock.phaseRadians.toFloat)
          )
        )
        _ <- sendUdp(socket, msg, clientAddr, clientPort)
        // Check for bar (downbeat) transition
        prevBeat <- state.synthClockRef.get.map(_.beatPosition)
        currentBar = (clock.beatPosition / 4.0).toInt
        prevBar = (prevBeat / 4.0).toInt
        _ <- if currentBar > prevBar then {
          val barMsg = encodeOscMessage("/clock/bar", Nil)
          sendUdp(socket, barMsg, clientAddr, clientPort)
        } else IO.unit
        _ <- loop
      } yield ()
    loop
  }

  /**
   * Send /clock/pulse messages at 24 PPQ (MIDI clock rate).
   */
  private def sendPulseLoop(
    state: ClockPipeline.PipelineState,
    socket: DatagramSocket,
    clientAddr: InetAddress,
    clientPort: Int
  ): IO[Unit] = {
    def loop(lastPulse: Long): IO[Unit] =
      for {
        clock <- state.synthClockRef.get
        nanosPerBeat = (60.0 / clock.bpm * 1_000_000_000.0).toLong
        nanosPerPulse = nanosPerBeat / MidiClockPulse.PulsesPerQuarterNote
        currentPulse = (clock.beatPosition * MidiClockPulse.PulsesPerQuarterNote).toLong
        _ <- if currentPulse > lastPulse then {
          val msg = encodeOscMessage("/clock/pulse", Nil)
          sendUdp(socket, msg, clientAddr, clientPort)
        } else IO.unit
        sleepDuration = math.max(1, nanosPerPulse / 2_000_000).millis // poll at 2x pulse rate
        _ <- IO.sleep(sleepDuration)
        _ <- loop(currentPulse)
      } yield ()
    loop(0L)
  }

  /**
   * Send /clock/sync messages at 1Hz.
   * Format: /clock/sync ,fii <quality> <participants> <round>
   */
  private def sendSyncLoop(
    state: ClockPipeline.PipelineState,
    socket: DatagramSocket,
    clientAddr: InetAddress,
    clientPort: Int
  ): IO[Unit] = {
    def loop: IO[Unit] =
      for {
        _ <- IO.sleep(1.second)
        clock <- state.synthClockRef.get
        msg = encodeOscMessage(
          "/clock/sync",
          List(
            OscFloat(clock.syncQuality.toFloat),
            OscInt(clock.participantCount),
            OscInt(clock.roundNumber.toInt)
          )
        )
        _ <- sendUdp(socket, msg, clientAddr, clientPort)
        _ <- loop
      } yield ()
    loop
  }

  /**
   * Receive incoming OSC messages (tempo proposals).
   */
  private def receiveLoop(
    state: ClockPipeline.PipelineState,
    socket: DatagramSocket,
    nodeId: String
  ): IO[Unit] = {
    val buf = new Array[Byte](1024)
    def loop: IO[Unit] =
      for {
        packet <- IO.blocking {
          val packet = new DatagramPacket(buf, buf.length)
          socket.setSoTimeout(1000) // 1 second timeout
          try {
            socket.receive(packet)
            Some(packet)
          } catch {
            case _: java.net.SocketTimeoutException => None
          }
        }
        _ <- packet match {
          case Some(p) =>
            val data = java.util.Arrays.copyOf(p.getData, p.getLength)
            parseOscMessage(data) match {
              case Some(("/clock/tempo", args)) =>
                args.headOption match {
                  case Some(OscFloat(bpm)) =>
                    val proposal = TempoProposal.create(nodeId, bpm.toDouble)
                    state.tempoConsensusRef.update { tc =>
                      tc.copy(
                        pendingProposals = tc.pendingProposals + (proposal.proposalId -> proposal)
                      )
                    } *> logger.info(s"OSC: Received tempo proposal: ${bpm} BPM")
                  case _ => IO.unit
                }
              case _ => IO.unit
            }
          case None => IO.unit
        }
        _ <- loop
      } yield ()
    loop
  }

  // --- OSC Wire Format Encoding ---

  sealed trait OscArg
  case class OscFloat(value: Float) extends OscArg
  case class OscInt(value: Int) extends OscArg
  case class OscString(value: String) extends OscArg

  /**
   * Encode an OSC message to bytes.
   * OSC format: address (null-padded to 4-byte boundary) + type tag string + arguments
   */
  def encodeOscMessage(address: String, args: List[OscArg]): Array[Byte] = {
    val buf = ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN)

    // Write address
    writeOscString(buf, address)

    // Write type tag string
    val typeTags = "," + args.map {
      case _: OscFloat => "f"
      case _: OscInt => "i"
      case _: OscString => "s"
    }.mkString
    writeOscString(buf, typeTags)

    // Write arguments
    args.foreach {
      case OscFloat(v) => buf.putFloat(v)
      case OscInt(v) => buf.putInt(v)
      case OscString(v) => writeOscString(buf, v)
    }

    val result = new Array[Byte](buf.position())
    buf.flip()
    buf.get(result)
    result
  }

  /**
   * Parse an OSC message from bytes.
   */
  def parseOscMessage(data: Array[Byte]): Option[(String, List[OscArg])] = {
    try {
      val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
      val address = readOscString(buf)
      val typeTags = readOscString(buf)

      if !typeTags.startsWith(",") then None
      else {
        val argResults = typeTags.drop(1).map {
          case 'f' => Some(OscFloat(buf.getFloat))
          case 'i' => Some(OscInt(buf.getInt))
          case 's' => Some(OscString(readOscString(buf)))
          case _   => None
        }.toList

        if argResults.exists(_.isEmpty) then None
        else Some((address, argResults.flatten))
      }
    } catch {
      case _: Exception => None
    }
  }

  private def writeOscString(buf: ByteBuffer, s: String): Unit = {
    val bytes = s.getBytes("UTF-8")
    buf.put(bytes)
    buf.put(0.toByte)
    // Pad to 4-byte boundary
    val padding = (4 - ((bytes.length + 1) % 4)) % 4
    for (_ <- 0 until padding) buf.put(0.toByte)
  }

  private def readOscString(buf: ByteBuffer): String = {
    val start = buf.position()
    while buf.get() != 0 do {} // read until null terminator
    val len = buf.position() - start - 1
    // Skip padding to 4-byte boundary
    val padding = (4 - (buf.position() - start) % 4) % 4
    for (_ <- 0 until padding) buf.get()
    val bytes = new Array[Byte](len)
    buf.position(start)
    buf.get(bytes)
    buf.position(start + len + 1 + padding) // skip past null + padding
    new String(bytes, "UTF-8")
  }

  private def sendUdp(
    socket: DatagramSocket,
    data: Array[Byte],
    addr: InetAddress,
    port: Int
  ): IO[Unit] =
    IO.blocking {
      val packet = new DatagramPacket(data, data.length, addr, port)
      socket.send(packet)
    }.handleErrorWith { err =>
      logger.debug(s"OSC send failed: ${err.getMessage}")
    }
}
