//
//  route_check.swift
//  Run the route conversion against synthetic hills with known answers.
//
//      swiftc -O ios/StrideHealth/RouteConverter.swift tools/route_check.swift \
//             -o /tmp/route_check && /tmp/route_check
//
//  The pipeline in `RouteConverter.swift` has a correctness question at nearly
//  every step, and none of them can be checked by looking at a phone. A hill
//  built to be exactly 5% either comes out at 5% or it does not.
//
//  Two of these cases are regression tests rather than demonstrations: the
//  chatter case (a constant grade under GPS noise must not produce a
//  changes-per-kilometre count in the hundreds) and the jitter case (standing
//  still must not invent distance). Both were the failure modes `iOS.md` warned
//  about, and both are silent — they produce a profile that looks plausible in
//  a list and feels wrong underfoot.
//

import Foundation

// MARK: - Building fake walks

/// Deterministic noise. A test that fails one run in five is not a test.
struct Rng {
    var state: UInt64 = 0x9E3779B97F4A7C15
    mutating func next() -> Double {
        state ^= state << 13; state ^= state >> 7; state ^= state << 17
        return Double(state % 1_000_000) / 1_000_000
    }
    /// Uniform in ±`m`.
    mutating func noise(_ m: Double) -> Double { (next() - 0.5) * 2 * m }

    /// Approximately normal, mean 0, unit variance. Twelve uniforms summed —
    /// crude, but the central limit theorem is doing real work and this needs
    /// no logarithms.
    mutating func gauss() -> Double {
        var s = 0.0
        for _ in 0..<12 { s += next() }
        return s - 6
    }
}

/// A slowly wandering altitude error, which is what GPS actually produces.
///
/// **This is the model that matters, and its absence is why the first version
/// of this harness passed a pipeline the phone then failed.** Independent
/// per-sample noise is the easy thing to write and the wrong thing to test
/// against: eighteen fixes land in a 25 m cell, so averaging kills it by √18
/// and anything downstream looks excellent.
///
/// Real vertical error is dominated by satellite geometry and multipath, which
/// change over tens of seconds, not tenths. The error is therefore strongly
/// correlated in time — it drifts a metre or two and holds there for a minute
/// — and a drift that persists over 100 m of walking is *indistinguishable
/// from terrain*. Cell averaging cannot remove it, smoothing cannot remove it,
/// and on genuinely flat ground it is the entire signal.
///
/// Modelled as an AR(1) / Ornstein–Uhlenbeck process: each step reverts toward
/// zero by `dt/tau` and takes a fresh kick, giving a wander of standard
/// deviation `sigma` with a correlation time of `tau` seconds.
struct Drift {
    var rng = Rng()
    var value = 0.0
    let sigma: Double
    let tau: Double

    mutating func step(dt: Double) -> Double {
        let a = exp(-dt / tau)
        value = a * value + (1 - a * a).squareRoot() * sigma * rng.gauss()
        return value
    }
}

let metresPerDegreeLat = 111_320.0

/// Walk due north from a fixed point, one fix per second at `speedMs`,
/// taking altitude from `profile(distance)`.
func walk(metres: Double,
          speedMs: Double = 1.4,
          altNoise: Double = 0,
          altDrift: Double = 0,
          driftTau: Double = 45,
          hAcc: Double = 5,
          vAcc: Double = 4,
          profile: (Double) -> Double) -> [RouteFix] {
    var rng = Rng()
    var drift = Drift(sigma: altDrift, tau: driftTau)
    var out: [RouteFix] = []
    let t0 = Date(timeIntervalSince1970: 1_754_000_000)
    var d = 0.0
    var t = 0.0
    while d <= metres {
        var alt = profile(d)
        if altNoise > 0 { alt += rng.noise(altNoise) }
        if altDrift > 0 { alt += drift.step(dt: 1) }
        out.append(RouteFix(lat: -33.8688 + d / metresPerDegreeLat,
                            lon: 151.2093,
                            altitude: alt,
                            horizontalAccuracy: hAcc,
                            verticalAccuracy: vAcc,
                            timestamp: t0.addingTimeInterval(t)))
        d += speedMs
        t += 1
    }
    return out
}

// MARK: - Reporting

var failures = 0

func check(_ name: String, _ pass: Bool, _ detail: String) {
    print("\(pass ? "  ok  " : "  FAIL") \(name) — \(detail)")
    if !pass { failures += 1 }
}

func profileOf(_ fixes: [RouteFix], baro: Double? = nil) -> RouteProfile {
    guard let p = RouteConverter.convert(fixes, barometricClimbM: baro) else {
        print("  FAIL conversion returned nil for \(fixes.count) fixes")
        exit(1)
    }
    return p
}

func f(_ v: Double, _ places: Int = 1) -> String {
    String(format: "%.\(places)f", v)
}

// MARK: - The checks

@main
enum RouteCheck {

    static func main() {
        print("\nroute_check — the conversion against hills with known answers\n")
        deadbandSweep()
        dwellSweep()
        flat()
        flatButNoisy()
        steadyHill()
        chatter()
        compression()
        stationaryJitter()
        badFixes()
        barometer()
        difficulty()
        thirtyFirstOfJuly()
        print(failures == 0 ? "\n0 failures\n" : "\n\(failures) FAILURES\n")
        exit(failures == 0 ? 0 : 1)
    }

    /// The deadband sits between two failure modes and there is no way to
    /// reason it out from first principles, so it is measured.
    ///
    /// Too wide and the quantiser is bistable: compression turns a real 5% into
    /// 4.73%, and a full-step deadband will hold that on either 4% or 5%
    /// forever depending on which rung the first grid point happened to land
    /// on — a whole hill's climb decided by one noisy sample. Too narrow and
    /// the residual noise walks it across the rounding boundary, which is the
    /// chatter the deadband exists to stop.
    ///
    /// Printed rather than asserted. It is the evidence for the constant in
    /// `Deck.deadband`, and if the resampler or the filter changes it should be
    /// read again.
    static func deadbandSweep() {
        let noisy = walk(metres: 2000, altNoise: 2) { d in 50 + d * 0.03 }
        let clean = walk(metres: 1000) { d in 30 + d * 0.05 }
        let cleanTrack = RouteConverter.resample(
            RouteConverter.horizontalTrack(RouteConverter.usable(clean)), every: 25)
        let noisyTrack = RouteConverter.resample(
            RouteConverter.horizontalTrack(RouteConverter.usable(noisy)), every: 25)
        let cleanG = RouteConverter.grades(RouteConverter.savitzkyGolay(cleanTrack),
                                           spacing: 25).map(RouteConverter.compress)
        let noisyG = RouteConverter.grades(RouteConverter.savitzkyGolay(noisyTrack),
                                           spacing: 25).map(RouteConverter.compress)

        print("  deadband   chatter (changes/km)   clean 5% hill climb (want 50 m)")
        for db in [0.5, 0.6, 0.7, 0.8, 0.9, 1.0] {
            let n = RouteConverter.plan(noisyG, spacing: 25, deadband: db)
            let c = RouteConverter.plan(cleanG, spacing: 25, deadband: db)
            let perKm = Double(n.count) / (2000.0 / 1000)
            print(String(format: "    %.1f          %5.1f                  %3.0f m",
                         db, perKm, RouteConverter.climb(of: c)))
        }
        print("")
    }

    /// Flat ground is one segment. If this fails, everything after it is noise.
    static func flat() {
        let p = profileOf(walk(metres: 2000) { _ in 30 })
        check("flat 2 km", p.changeCount == 1 && p.climbM == 0,
              "\(p.changeCount) segment(s), \(Int(p.climbM)) m climb, "
              + "incline \(p.segments.first.map { f($0.incline) } ?? "—")%")
    }

    /// The reversal dwell trades chatter on flat ground against how promptly a
    /// real summit becomes a descent. Both sides are printed, because a value
    /// that silences the first by flattening the second is not an improvement —
    /// it is the same walk with the hills taken out.
    ///
    /// `peak` is the steepest incline the profile reaches and `climb` its total
    /// ascent: those are the "challenges and rests" that have to survive.
    static func dwellSweep() {
        let flatish = walk(metres: 5060, altNoise: 0.8, altDrift: 1.5) { d in
            30 + 9 * sin(d / 5060 * 2 * .pi) + 9 * sin(d / 5060 * 3 * .pi)
        }
        let hilly = walk(metres: 3030, altNoise: 0.8, altDrift: 1.5) { d in
            40 + 45 * sin(d / 3030 * .pi) + 12 * sin(d / 3030 * 4 * .pi)
        }

        func grades(_ fixes: [RouteFix]) -> [Double] {
            let grid = RouteConverter.resample(
                RouteConverter.horizontalTrack(RouteConverter.usable(fixes)), every: 25)
            return RouteConverter.grades(RouteConverter.savitzkyGolay(grid),
                                         spacing: 25).map(RouteConverter.compress)
        }
        let flatG = grades(flatish), hillyG = grades(hilly)

        print("  dwell    flat 5 km: changes    hilly 3 km: changes / peak / climb")
        for dw in [0.0, 100.0, 150.0, 200.0, 250.0, 300.0, 400.0] {
            let a = RouteConverter.plan(flatG, spacing: 25, dwell: dw)
            let b = RouteConverter.plan(hillyG, spacing: 25, dwell: dw)
            print(String(format: "    %3.0f m        %3d               %3d / %+.0f%% / %3.0f m",
                         dw, a.count, b.count,
                         b.map { $0.incline }.max() ?? 0,
                         RouteConverter.climb(of: b)))
        }
        print("")
    }

    /// The case the first version of this harness missed, and the phone found:
    /// **flat ground with noise on it**.
    ///
    /// `flat()` above uses a perfectly clean signal and passes trivially with
    /// one segment; `chatter()` below uses a 3% grade, where the signal is
    /// several times the noise and dominates it. Neither covers the walk of
    /// 30 July 2026 — 5.06 km with 18 m of climb, an average grade of 0.36%,
    /// which is *below* the noise floor. The real conversion produced **61
    /// incline changes** on it: a deck moving under the walker roughly once a
    /// minute for an hour, over ground that is essentially level.
    ///
    /// This models it: 5 km, a couple of gentle rises totalling ~18 m, and —
    /// the part that reproduces the failure — a **drifting** 1.5 m altitude
    /// error rather than an independent one. See `Drift`.
    static func flatButNoisy() {
        let p = profileOf(walk(metres: 5060, altNoise: 0.8, altDrift: 1.5) { d in
            30 + 9 * sin(d / 5060 * 2 * .pi) + 9 * sin(d / 5060 * 3 * .pi)
        }, baro: 18)
        let perKm = Double(p.changeCount) / (p.distanceM / 1000)
        check("5 km of near-flat ground under noise", p.changeCount <= 30,
              "\(p.changeCount) changes over \(f(p.distanceM / 1000, 2)) km "
              + "= \(f(perKm))/km — one every \(f(p.distanceM / Double(max(p.changeCount, 1)))) m")
    }

    /// 1 km rising 50 m is exactly 5%.
    static func steadyHill() {
        let p = profileOf(walk(metres: 1000) { d in 30 + d * 0.05 })
        let inclines = Set(p.segments.map { $0.incline })
        check("steady 5% hill", inclines.contains(5) && abs(p.climbM - 50) < 8,
              "inclines \(inclines.sorted()), climb \(Int(p.climbM)) m (want ~50)")
    }

    /// The one that matters. ±2 m of vertical noise on a 2 km 3% grade — naive
    /// differencing of this gives grades swinging tens of percent between
    /// adjacent points, and an incline motor asked to follow every one of them.
    static func chatter() {
        let p = profileOf(walk(metres: 2000, altNoise: 2) { d in 50 + d * 0.03 })
        let perKm = Double(p.changeCount) / (p.distanceM / 1000)
        check("3% hill under ±2 m noise", perKm < 12,
              "\(p.changeCount) changes over \(f(p.distanceM / 1000, 2)) km "
              + "= \(f(perKm))/km (want < 12)")

        // And it must still be a 3% hill, not a smoothed-flat one. Rejecting
        // chatter by flattening everything would pass the test above.
        let mean = p.segments.reduce(0.0) { $0 + $1.incline * $1.lengthM } / p.distanceM
        check("…and still reads as a 3% hill", abs(mean - 3) < 1.2,
              "distance-weighted mean incline \(f(mean, 2))%")
    }

    static func compression() {
        let gentle = RouteConverter.compress(-1)
        let up = RouteConverter.compress(2)
        let steep = RouteConverter.compress(-9)
        check("gentle grades stay faithful",
              abs(gentle + 1) < 0.1 && abs(up - 2) < 0.15,
              "−1% → \(f(gentle, 2))%, +2% → \(f(up, 2))%")
        check("steep descent saturates inside the deck",
              steep > Deck.minGrade && steep < -2.5,
              "−9% → \(f(steep, 2))% (floor is \(f(Deck.minGrade))%)")
    }

    /// Ninety seconds at a crossing, GPS wandering ~1.5 m a second. Summed
    /// naively that is a hundred metres of walking that never happened — and
    /// every metre of it is horizontal distance with no rise against it, so it
    /// splices an artificial flat into the middle of the route.
    static func stationaryJitter() {
        var rng = Rng()
        let t0 = Date(timeIntervalSince1970: 1_754_000_000)
        var fixes: [RouteFix] = []
        for i in 0..<90 {
            fixes.append(RouteFix(lat: -33.8688 + rng.noise(1.5) / metresPerDegreeLat,
                                  lon: 151.2093 + rng.noise(1.5) / metresPerDegreeLat,
                                  altitude: 30 + rng.noise(0.5),
                                  horizontalAccuracy: 5, verticalAccuracy: 4,
                                  timestamp: t0.addingTimeInterval(Double(i))))
        }
        let track = RouteConverter.horizontalTrack(RouteConverter.usable(fixes))
        let invented = track.last?.d ?? 0
        check("90 s standing still", invented < 25,
              "\(f(invented)) m of invented distance (want < 25)")
    }

    static func badFixes() {
        let good = walk(metres: 300) { _ in 30 }
        var mixed = good
        // verticalAccuracy <= 0 means "the altitude is invalid", not "slightly
        // off" — and 9999 m landing in a seven-point smoothing window poisons
        // 175 m of road either side of it.
        mixed.append(RouteFix(lat: -33.86, lon: 151.20, altitude: 9999,
                              horizontalAccuracy: 5, verticalAccuracy: -1,
                              timestamp: good.last!.timestamp.addingTimeInterval(1)))
        mixed.append(RouteFix(lat: -33.86, lon: 151.20, altitude: 40,
                              horizontalAccuracy: 300, verticalAccuracy: 4,
                              timestamp: good.last!.timestamp.addingTimeInterval(2)))
        let kept = RouteConverter.usable(mixed)
        check("invalid altitude and 300 m fixes dropped", kept.count == good.count,
              "\(mixed.count) in, \(kept.count) out, \(good.count) good")
    }

    /// A hill GPS under-reads: 40 m by GPS, 60 m by barometer.
    static func barometer() {
        let fixes = walk(metres: 1500) { d in 20 + d * (40.0 / 1500) }
        let unscaled = profileOf(fixes)
        let scaled = profileOf(fixes, baro: 60)
        check("barometer overrides GPS when they disagree",
              scaled.climbScale > 1.2 && abs(scaled.climbM - 60) / 60 < 0.25,
              "GPS \(Int(unscaled.climbM)) m → ×\(f(scaled.climbScale, 2)) "
              + "→ \(Int(scaled.climbM)) m (want ~60)")

        let agree = profileOf(fixes, baro: Double(Int(unscaled.climbM)))
        check("…and leaves an agreeing profile alone", agree.climbScale == 1,
              "scale \(f(agree.climbScale, 2)), climb \(Int(agree.climbM)) m")
    }

    /// Walking a route harder must not make it lurch.
    ///
    /// Found on the belt, not here, which is why this check exists now. A route
    /// walked at 120% moved the deck **two percent at once at nine of its
    /// thirty-four transitions** — felt as a lurch rather than a hill.
    ///
    /// The cause was applying the multiplier to inclines that had already been
    /// quantised: 2% × 1.2 rounds back to 2, but 3% × 1.2 rounds to 4, so a
    /// clean one-step transition became a two-step one. Every guarantee the
    /// pipeline makes — hysteresis, the reversal dwell, one step per grid
    /// point — is downstream of quantisation, so scaling after it discards all
    /// three. The dial has to go *inside* the pipeline, not after it.
    static func difficulty() {
        let fixes = walk(metres: 3030, altNoise: 0.8, altDrift: 1.5) { d in
            40 + 45 * sin(d / 3030 * .pi) + 12 * sin(d / 3030 * 4 * .pi)
        }
        guard let p = RouteConverter.convert(fixes, barometricClimbM: 68) else { return }

        for factor in [0.8, 1.0, 1.2, 1.5] {
            let segs = p.at(difficulty: factor)
            let jumps = zip(segs, segs.dropFirst())
                .map { abs($1.incline - $0.incline) }
            let worst = jumps.max() ?? 0
            let perKm = Double(segs.count) / (p.distanceM / 1000)
            check("at \(Int(factor * 100))% — no transition bigger than one step",
                  worst <= Deck.step + 1e-9,
                  "\(segs.count) changes (\(f(perKm))/km), largest jump \(f(worst))%, "
                  + "climb \(f(RouteConverter.climb(of: segs), 0)) m")
        }

        // And the dial must still do something, or the safe answer would be to
        // ignore it.
        let easy = RouteConverter.climb(of: p.at(difficulty: 0.8))
        let hard = RouteConverter.climb(of: p.at(difficulty: 1.5))
        check("the dial still changes the walk", hard > easy * 1.4,
              "\(f(easy, 0)) m at 80% → \(f(hard, 0)) m at 150%")
    }

    /// The test case NEXT.md names: 3.03 km, 68 m of climb, recorded 31 July.
    /// The 29 July walk is nearly flat and would have proved nothing.
    ///
    /// Rolling ground here rather than a single arc — one long climb with real
    /// undulation on top of it, at ±1.5 m altitude noise, which is ordinary for
    /// a watch under open sky.
    static func thirtyFirstOfJuly() {
        let p = profileOf(walk(metres: 3030, altNoise: 1.5) { d in
            40 + 45 * sin(d / 3030 * .pi)
               + 12 * sin(d / 3030 * 4 * .pi)
        }, baro: 68)

        print("\n  31 July: \(p.checkpoint)")
        print("  \(p.usableFixes) usable fixes → \(p.gridPoints) grid points → "
              + "\(p.changeCount) incline changes\n")

        check("change count in the band iOS.md predicts",
              p.changeCount >= 15 && p.changeCount <= 35,
              "\(p.changeCount) changes (iOS.md: 20–30 for a 5 km walk)")
        check("climb within 15% of the barometer",
              abs(p.climbM - 68) / 68 <= 0.15,
              "\(Int(p.climbM)) m vs 68 m = \(f(abs(p.climbM - 68) / 68 * 100, 0))% out")
        // The other half of the brief: suppressing chatter must not cost the
        // walk its shape. A profile that never gets above 3% has had the
        // challenge taken out of it, and a route with no descent has had the
        // rest taken out — both would pass a change-count check comfortably.
        check("the climbs survive",
              (p.segments.map { $0.incline }.max() ?? 0) >= 6,
              "peak \(f(p.segments.map { $0.incline }.max() ?? 0))% (want ≥ 6%)")
        check("the descents survive",
              (p.segments.map { $0.incline }.min() ?? 0) <= -2,
              "lowest \(f(p.segments.map { $0.incline }.min() ?? 0))% (want ≤ −2%)")
        check("every incline inside the deck's range",
              p.segments.allSatisfy { $0.incline >= Deck.minGrade
                                   && $0.incline <= Deck.maxGrade },
              "min \(f(p.segments.map { $0.incline }.min() ?? 0))%, "
              + "max \(f(p.segments.map { $0.incline }.max() ?? 0))%")
        let jumps = zip(p.segments, p.segments.dropFirst())
            .map { abs($1.incline - $0.incline) }
        check("no segment jumps more than one step",
              jumps.allSatisfy { $0 <= Deck.step + 1e-9 },
              "largest jump \(f(jumps.max() ?? 0))%")
        check("segments are contiguous",
              zip(p.segments, p.segments.dropFirst())
                  .allSatisfy { abs($1.startM - $0.endM) < 1e-6 },
              "\(Int(p.segments.first?.startM ?? -1)) m → "
              + "\(Int(p.segments.last?.endM ?? -1)) m")

        print("\n  profile:")
        for s in p.segments {
            let bar = String(repeating: "█", count: max(1, Int((s.incline + 3) * 2)))
            print(String(format: "    %5.0f–%5.0f m  %+5.1f%%  %@",
                         s.startM, s.endM, s.incline, bar))
        }
    }
}
