//! Clock consensus WASM module for verifiable distributed clock synchronization.
//!
//! Implements the weighted averaging protocol from
//! "Topological Characterization of Consensus in Distributed Systems."
//!
//! All functions operate on flat arrays passed via WASM linear memory.
//! The Scala host writes input arrays, calls the function, and reads outputs.

use std::f64::consts::PI;

/// Compute the weighted average offset for a single node.
///
/// # Arguments
/// * `offsets`    - Pointer to f64 array of all node offsets (length `n`)
/// * `weights`    - Pointer to f64 array of weights for this node's row (length `n`)
/// * `n`          - Number of nodes
/// * `node_index` - Index of the node to compute (used for validation only)
///
/// # Returns
/// The new offset for the specified node: Σ_j w_j * x_j
#[no_mangle]
pub extern "C" fn weighted_average(
    offsets: *const f64,
    weights: *const f64,
    n: u32,
    _node_index: u32,
) -> f64 {
    let n = n as usize;
    let mut sum = 0.0_f64;
    for j in 0..n {
        let offset = unsafe { *offsets.add(j) };
        let weight = unsafe { *weights.add(j) };
        sum += weight * offset;
    }
    sum
}

/// Compute one full round of weighted averaging for all nodes.
///
/// # Arguments
/// * `offsets` - Pointer to f64 array of current offsets (length `n`)
/// * `weights` - Pointer to f64 array of the full weight matrix in row-major order (length `n*n`)
/// * `output`  - Pointer to f64 array where new offsets will be written (length `n`)
/// * `n`       - Number of nodes
#[no_mangle]
pub extern "C" fn compute_round(
    offsets: *const f64,
    weights: *const f64,
    output: *mut f64,
    n: u32,
) {
    let n = n as usize;
    for i in 0..n {
        let mut sum = 0.0_f64;
        for j in 0..n {
            let offset = unsafe { *offsets.add(j) };
            let weight = unsafe { *weights.add(i * n + j) };
            sum += weight * offset;
        }
        unsafe {
            *output.add(i) = sum;
        }
    }
}

/// Compute convergence error: max(offsets) - min(offsets).
///
/// # Arguments
/// * `offsets` - Pointer to f64 array of node offsets (length `n`)
/// * `n`       - Number of nodes
///
/// # Returns
/// The spread (max - min) of all offsets. Zero means perfect consensus.
#[no_mangle]
pub extern "C" fn convergence_error(offsets: *const f64, n: u32) -> f64 {
    let n = n as usize;
    if n == 0 {
        return 0.0;
    }
    let mut min_val = f64::MAX;
    let mut max_val = f64::MIN;
    for i in 0..n {
        let v = unsafe { *offsets.add(i) };
        if v < min_val {
            min_val = v;
        }
        if v > max_val {
            max_val = v;
        }
    }
    max_val - min_val
}

/// Compute beat position from global nanosecond timestamp.
///
/// # Arguments
/// * `global_nanos`    - Current global nanosecond timestamp
/// * `bpm`             - Beats per minute
/// * `reference_nanos` - Reference timestamp corresponding to beat 0
///
/// # Returns
/// Beat position as a floating-point number (e.g., 4.75 = 3/4 through beat 5)
#[no_mangle]
pub extern "C" fn compute_beat_position(
    global_nanos: i64,
    bpm: f64,
    reference_nanos: i64,
) -> f64 {
    let elapsed = (global_nanos - reference_nanos) as f64;
    let nanos_per_beat = 60.0 / bpm * 1_000_000_000.0;
    elapsed / nanos_per_beat
}

/// Compute phase within the current beat in radians [0, 2π).
///
/// # Arguments
/// * `global_nanos`    - Current global nanosecond timestamp
/// * `bpm`             - Beats per minute
/// * `reference_nanos` - Reference timestamp corresponding to beat 0
///
/// # Returns
/// Phase in radians from 0 to 2π
#[no_mangle]
pub extern "C" fn compute_phase(
    global_nanos: i64,
    bpm: f64,
    reference_nanos: i64,
) -> f64 {
    let beat_pos = compute_beat_position(global_nanos, bpm, reference_nanos);
    let fractional = beat_pos - beat_pos.floor();
    fractional * 2.0 * PI
}

/// Allocate memory in WASM linear memory for the host to write data into.
///
/// # Arguments
/// * `size` - Number of bytes to allocate
///
/// # Returns
/// Pointer to the allocated memory
#[no_mangle]
pub extern "C" fn alloc(size: u32) -> *mut u8 {
    let mut buf = Vec::with_capacity(size as usize);
    let ptr = buf.as_mut_ptr();
    std::mem::forget(buf);
    ptr
}

/// Free previously allocated memory.
///
/// # Arguments
/// * `ptr`  - Pointer to the memory to free
/// * `size` - Number of bytes originally allocated
#[no_mangle]
pub extern "C" fn dealloc(ptr: *mut u8, size: u32) {
    unsafe {
        let _ = Vec::from_raw_parts(ptr, 0, size as usize);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_weighted_average_uniform() {
        // 3 nodes with equal weights (1/3 each), offsets [100, 200, 300]
        // Expected: (100 + 200 + 300) / 3 = 200
        let offsets = [100.0_f64, 200.0, 300.0];
        let weights = [1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0];
        let result = weighted_average(offsets.as_ptr(), weights.as_ptr(), 3, 0);
        assert!((result - 200.0).abs() < 1e-10);
    }

    #[test]
    fn test_compute_round_converges() {
        // 3 nodes, symmetric doubly-stochastic matrix
        let mut offsets = [0.0_f64, 1000.0, 2000.0];
        // MH weights for complete graph with 3 nodes: off-diag = 1/3, self = 1/3
        // Actually for MH: a_ij = 1/max(d_i, d_j) = 1/2, a_ii = 1 - 2*(1/2) = 0
        // But that's aggressive. Use softer: off-diag = 0.25, self = 0.5
        let weights = [
            0.5, 0.25, 0.25,
            0.25, 0.5, 0.25,
            0.25, 0.25, 0.5,
        ];
        let mut output = [0.0_f64; 3];

        // Run 20 rounds
        for _ in 0..20 {
            compute_round(offsets.as_ptr(), weights.as_ptr(), output.as_mut_ptr(), 3);
            offsets = output;
        }

        // Should converge to the mean: 1000
        let error = convergence_error(offsets.as_ptr(), 3);
        assert!(error < 1.0, "Error should be < 1 nano, got {}", error);
        assert!((offsets[0] - 1000.0).abs() < 1.0);
    }

    #[test]
    fn test_convergence_error_zero() {
        let offsets = [500.0_f64, 500.0, 500.0];
        let error = convergence_error(offsets.as_ptr(), 3);
        assert_eq!(error, 0.0);
    }

    #[test]
    fn test_convergence_error_spread() {
        let offsets = [100.0_f64, 300.0, 200.0];
        let error = convergence_error(offsets.as_ptr(), 3);
        assert_eq!(error, 200.0);
    }

    #[test]
    fn test_beat_position() {
        // At 120 BPM, 1 beat = 500ms = 500_000_000 nanos
        // After 1 second (1_000_000_000 nanos), we should be at beat 2.0
        let result = compute_beat_position(1_000_000_000, 120.0, 0);
        assert!((result - 2.0).abs() < 1e-10);
    }

    #[test]
    fn test_phase_at_half_beat() {
        // At 120 BPM, half a beat = 250ms = 250_000_000 nanos
        // Phase should be π
        let result = compute_phase(250_000_000, 120.0, 0);
        assert!((result - std::f64::consts::PI).abs() < 1e-6);
    }

    #[test]
    fn test_phase_at_beat_start() {
        // At exactly beat 0, phase should be 0
        let result = compute_phase(0, 120.0, 0);
        assert!(result.abs() < 1e-10);
    }
}
