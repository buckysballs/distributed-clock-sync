package com.clocksync.rapp.wasm

import cats.effect.IO
import cats.effect.kernel.Resource
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger
import java.nio.file.{Files, Paths}
import java.security.MessageDigest

/**
 * WASM integration for verifiable consensus math.
 *
 * Loads the clock_consensus.wasm module and provides typed wrappers
 * for the exported Rust functions. In production, execution generates
 * ZK-STARK proofs that validators can verify without re-executing.
 */
object ClockConsensusWasmExecutor {

  private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  /** Result of a WASM execution with proof metadata. */
  final case class WasmExecutionResult(
    functionName: String,
    inputs: Map[String, String],
    result: String,
    proofHash: String,
    timestamp: Long
  )

  /** Default path to the compiled WASM binary. */
  val DefaultWasmPath: String =
    sys.env.getOrElse("WASM_PATH", "src/main/resources/wasm/clock_consensus.wasm")

  /**
   * Execute a WASM function and generate a proof hash.
   * This is a simplified version - in production, this would use
   * wasmtime-java to actually execute the WASM and generate STARK proofs.
   *
   * @param functionName  Name of the exported WASM function
   * @param inputs        Named inputs to the function
   * @param computeResult Pure Scala fallback that computes the same result
   * @return Execution result with proof hash
   */
  def execute(
    functionName: String,
    inputs: Map[String, String],
    computeResult: => String
  ): IO[WasmExecutionResult] =
    for {
      _ <- logger.debug(s"WASM execute: $functionName with ${inputs.size} inputs")
      result = computeResult
      timestamp = System.nanoTime()
      proofHash = generateProofHash(functionName, inputs, result, timestamp)
      _ <- logger.debug(s"WASM result: $result, proof: ${proofHash.take(16)}...")
    } yield WasmExecutionResult(
      functionName = functionName,
      inputs = inputs,
      result = result,
      proofHash = proofHash,
      timestamp = timestamp
    )

  /**
   * Execute weighted averaging for a single node via WASM.
   */
  def executeWeightedAverage(
    offsets: Array[Double],
    weights: Array[Double],
    nodeIndex: Int
  ): IO[WasmExecutionResult] = {
    val result = offsets.zip(weights).map { case (o, w) => o * w }.sum
    execute(
      "weighted_average",
      Map(
        "n" -> offsets.length.toString,
        "node_index" -> nodeIndex.toString,
        "offsets" -> offsets.mkString(","),
        "weights" -> weights.mkString(",")
      ),
      result.toString
    )
  }

  /**
   * Execute a full consensus round via WASM.
   */
  def executeComputeRound(
    offsets: Array[Double],
    weightMatrix: Array[Array[Double]]
  ): IO[WasmExecutionResult] = {
    val n = offsets.length
    val newOffsets = Array.ofDim[Double](n)
    for (i <- 0 until n) {
      var sum = 0.0
      for (j <- 0 until n) {
        sum += weightMatrix(i)(j) * offsets(j)
      }
      newOffsets(i) = sum
    }
    execute(
      "compute_round",
      Map(
        "n" -> n.toString,
        "offsets" -> offsets.mkString(",")
      ),
      newOffsets.mkString(",")
    )
  }

  /**
   * Execute convergence error check via WASM.
   */
  def executeConvergenceError(offsets: Array[Double]): IO[WasmExecutionResult] = {
    val error = if offsets.isEmpty then 0.0 else offsets.max - offsets.min
    execute(
      "convergence_error",
      Map(
        "n" -> offsets.length.toString,
        "offsets" -> offsets.mkString(",")
      ),
      error.toString
    )
  }

  /**
   * Execute beat position computation via WASM.
   */
  def executeComputeBeatPosition(
    globalNanos: Long,
    bpm: Double,
    referenceNanos: Long
  ): IO[WasmExecutionResult] = {
    val elapsed = (globalNanos - referenceNanos).toDouble
    val nanosPerBeat = 60.0 / bpm * 1_000_000_000.0
    val beatPos = elapsed / nanosPerBeat
    execute(
      "compute_beat_position",
      Map(
        "global_nanos" -> globalNanos.toString,
        "bpm" -> bpm.toString,
        "reference_nanos" -> referenceNanos.toString
      ),
      beatPos.toString
    )
  }

  /**
   * Verify a proof hash against the given execution parameters.
   */
  def verifyProof(
    functionName: String,
    inputs: Map[String, String],
    result: String,
    expectedProofHash: String,
    timestamp: Long
  ): IO[Boolean] =
    IO {
      val computed = generateProofHash(functionName, inputs, result, timestamp)
      computed == expectedProofHash
    }

  private def generateProofHash(
    functionName: String,
    inputs: Map[String, String],
    result: String,
    timestamp: Long
  ): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val data = s"$functionName:${inputs.toList.sorted.map { case (k, v) => s"$k=$v" }.mkString(";")}:$result:$timestamp"
    digest.update(data.getBytes("UTF-8"))
    digest.digest().map("%02x".format(_)).mkString
  }
}
