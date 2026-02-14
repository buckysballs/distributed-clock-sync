package com.clocksync.rapp

import cats.effect.*
import com.clocksync.rapp.models.*
import com.clocksync.rapp.pipeline.ClockPipeline
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

import javax.sound.midi.{MidiDevice, MidiSystem, Receiver, ShortMessage}
import scala.concurrent.duration.*

/**
 * MIDI Clock bridge for hardware synthesizer integration.
 *
 * Sends MIDI timing clock (0xF8) at 24 PPQ, plus transport
 * messages (Start 0xFA, Stop 0xFC) to a locally-connected
 * MIDI device (e.g. Arturia DrumBrute Impact via USB).
 *
 * Device selection via MIDI_DEVICE env var (substring match).
 * If unset, logs available devices and exits cleanly.
 */
object MidiClockBridge {

  private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  /**
   * Start the MIDI clock bridge. Finds the target device, sends
   * a Start message, then continuously sends clock pulses.
   * Returns immediately if no matching device is found.
   */
  def start(
    state: ClockPipeline.PipelineState,
    deviceName: Option[String]
  ): IO[Unit] =
    findMidiDevice(deviceName).flatMap {
      case None => IO.unit
      case Some((device, receiver)) =>
        val acquire = IO.blocking(device.open()) *>
          logger.info(s"MIDI output: ${device.getDeviceInfo.getName}")
        val use = sendTransportStart(receiver) *> sendPulseLoop(state, receiver)
        val release =
          IO(receiver.close()).attempt *>
          IO.blocking(device.close()).attempt *>
          logger.info("MIDI bridge stopped")

        (acquire *> use).guarantee(release)
    }

  /**
   * Find a MIDI output device matching the given name substring.
   * Logs all available devices for discoverability.
   */
  private def findMidiDevice(name: Option[String]): IO[Option[(MidiDevice, Receiver)]] =
    IO.blocking {
      val infos = MidiSystem.getMidiDeviceInfo
      infos.toList
    }.flatMap { infos =>
      for {
        _ <- logger.info(s"Available MIDI devices: ${infos.map(_.getName).mkString(", ")}")
        result <- name match {
          case None =>
            logger.info("MIDI_DEVICE not set, MIDI output disabled") *>
              IO.pure(None)
          case Some(search) =>
            IO.blocking {
              infos.find { info =>
                info.getName.toLowerCase.contains(search.toLowerCase)
              }.flatMap { info =>
                val device = MidiSystem.getMidiDevice(info)
                // A device with max receivers != 0 can transmit MIDI out
                if device.getMaxReceivers != 0 then
                  Some((device, device.getReceiver))
                else None
              }
            }.flatMap {
              case Some(pair) => IO.pure(Some(pair))
              case None =>
                logger.warn(s"No MIDI output device matching '$search' found") *>
                  IO.pure(None)
            }
        }
      } yield result
    }

  /**
   * Send MIDI Start (0xFA) to begin transport.
   */
  private def sendTransportStart(receiver: Receiver): IO[Unit] =
    IO.blocking {
      receiver.send(new ShortMessage(0xFA), -1)
    } *> logger.info("MIDI: sent Start (0xFA)")

  /**
   * Pulse loop: polls SynthClockState and sends 0xF8 for each
   * new pulse, at 2x the pulse rate (same pattern as OscClockBridge).
   */
  private def sendPulseLoop(state: ClockPipeline.PipelineState, receiver: Receiver): IO[Unit] = {
    val clockMsg = new ShortMessage(0xF8)

    def loop(lastPulse: Long): IO[Unit] =
      for {
        clock <- state.synthClockRef.get
        nanosPerBeat = (60.0 / clock.bpm * 1_000_000_000.0).toLong
        nanosPerPulse = nanosPerBeat / MidiClockPulse.PulsesPerQuarterNote
        currentPulse = (clock.beatPosition * MidiClockPulse.PulsesPerQuarterNote).toLong
        _ <- if currentPulse > lastPulse then {
          // Send one 0xF8 per pulse advanced (handles missed pulses)
          val pulsesToSend = math.min(currentPulse - lastPulse, MidiClockPulse.PulsesPerQuarterNote.toLong)
          (1L to pulsesToSend).toList.foldLeft(IO.unit) { (acc, _) =>
            acc *> IO.blocking(receiver.send(clockMsg, -1))
          }
        } else IO.unit
        sleepDuration = math.max(1, nanosPerPulse / 2_000_000).millis
        _ <- IO.sleep(sleepDuration)
        _ <- loop(currentPulse)
      } yield ()

    loop(0L)
  }
}
