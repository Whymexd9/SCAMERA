// P366 (owner, 2026-10-27, vivo X200 Ultra ISZ at sea at noon: «вот такие фото получаю с исз, исправь» - pink blotches in the
// white wake foam): the lamp-flicker corrections of an ultrashort frame (the row profile of P197, the tile field of P290) are held
// where no lamp field can be (HybridTuning::usSceneEv / usDaylightEv / usIncoherent). Synthetic plain Bayer frames (BGGR):
//   - a sunlit sea: smooth water with clipped foam; between the base and the ultrashort frame the water moved (the ultrashort
//     frame x0.8 .. 1.2 on a ~40 px pattern of waves). With both rules off the tile field fires ("lamps on other flicker phases")
//     and recolours the frame (the owner's shot); by default it is held as incoherent, and at the owner's EV100 11.2 as daylight:
//     the frame bit for bit as captured, no flicker reported (HybridUsLocal::flicker, hybridUsFlicker);
//   - a lamp on another flicker phase (P290's desk lamp, x0.7 in its light): coherent - applied by default and at EV100 5, held
//     at EV100 11.2, applied again at EV100 15 with usDaylightEv 0;
//   - a row-wise flicker (P197): corrected at EV100 5, held at EV100 11.2 (the frame as captured); held without a tile field
//     (usLocal 0) it counts as measured, no flicker (review: -1 = unmeasured left the gain against N on), in the plain path too;
//   - the plain path (hybridPlainUsRows) with the sunlit frame: not copied, not corrected, the held line reported, usFlicker 0;
//   - (review) one decision per burst, by the median of its frames' incoherence (hybridUsBurstIncoherence): three ultrashort
//     frames on the sunlit base, two of moving water and one under a lamp field, are all held; one of moving water and two under
//     the lamp field are all corrected - on the mosaic paths' hybridUsRowsChecks and on the plain path alike.
// scam-hybrid.h is not self-contained: the worker includes scam-capture.h first.
#include "../app/src/main/cpp/scam-capture.h"
#include "../app/src/main/cpp/scam-hybrid.h"
#include <cassert>
#include <cmath>
#include <cstdio>
#include <random>
#include <string>
#include <vector>
using namespace scam;

static const int w = 1024, h = 768, cfa = 3;
static const float black = 64, white = 1023, expected = 0.125f;
static std::mt19937 rng(366);

static int colourOf(int x, int y) { const int p = ((y & 1) << 1) | (x & 1); return p == cfa ? 0 : p == (cfa ^ 3) ? 2 : 1; }
static float colourScale(int c) { return c == 0 ? 0.5f : c == 2 ? 0.7f : 1.f; }
// smooth water 250..400 DN above black at the base's exposure (each 16 px tile smooth: the tile check measures it)
static float water(int x, int y) { return 250.f + 150.f * (0.5f + 0.5f * std::sin(x * 0.011f + 0.4f) * std::cos(y * 0.013f)); }
struct Blob { float x, y, r; };
static std::vector<Blob> foam() {
    std::uniform_real_distribution<float> ux(40.f, w - 40.f), uy(40.f, h - 40.f), ur(10.f, 22.f);
    std::vector<Blob> b;
    for (int i = 0; i < 28; ++i) b.push_back({ux(rng), uy(rng), ur(rng)});
    return b;
}
static float foamAt(const std::vector<Blob>& b, int x, int y) {
    float s = 0.f;
    for (const Blob& f : b) { const float r2 = (x - f.x) * (x - f.x) + (y - f.y) * (y - f.y); s += 4000.f * std::exp(-r2 / (2.f * f.r * f.r)); }
    return s;
}
// the waves between the base and the ultrashort frame: x0.8 .. 1.2, three crossing trains of ~36 - 48 px
static float waves(int x, int y) {
    const float a = std::sin(0.157f * x + 0.050f * y + 1.1f), b = std::sin(-0.061f * x + 0.140f * y + 2.3f), c = std::sin(0.118f * x - 0.098f * y + 0.2f);
    return 1.f + 0.2f * (a + b + c) / 3.f * 1.6f;
}
template <class F> static std::vector<uint16_t> make(float gain, F signal) {
    std::normal_distribution<float> noise(0.f, 0.8f);
    std::vector<uint16_t> v(size_t(w) * h);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            v[size_t(y) * w + x] = uint16_t(std::lround(std::clamp(black + signal(x, y) * colourScale(colourOf(x, y)) * gain + noise(rng), 0.f, white)));
    return v;
}
static HybridInput pairInput() {
    HybridInput in; in.w = w; in.h = h; in.cfa = cfa; in.white = white; in.black = {black, black, black, black};
    HybridFrame b; b.role = kRoleNormal; b.exposure = 1.f; in.frames.push_back(b);
    HybridFrame u; u.role = kRoleUltrashort; u.exposure = expected; in.frames.push_back(u);
    return in;
}
static HybridTuning tuning(float ev, float daylightEv, float incoherent) {
    HybridTuning t; t.usSceneEv = ev; t.usDaylightEv = daylightEv; t.usIncoherent = incoherent;
    return t;
}
static bool has(const std::vector<std::string>& lines, const char* text) {
    for (const auto& l : lines) if (l.find(text) != std::string::npos) return true;
    return false;
}

int main() {
    std::setvbuf(stdout, nullptr, _IONBF, 0);
    const HybridInput in = pairInput();
    std::vector<std::string> lines;
    auto report = [&](const std::string& s) { lines.push_back(s); std::printf("  %s\n", s.c_str()); };
    const float nan = std::numeric_limits<float>::quiet_NaN();
    {   // the defaults: no EV (replays), daylight from 10, incoherent from 0.5
        const HybridTuning t;
        assert(std::isnan(t.usSceneEv) && t.usDaylightEv == 10.f && t.usIncoherent == 0.5f && !hybridUsGate(t).daylight());
        assert(hybridUsGate(tuning(11.2f, 10.f, 0.5f)).daylight() && !hybridUsGate(tuning(9.9f, 10.f, 0.5f)).daylight());
        assert(!hybridUsGate(tuning(15.f, 0.f, 0.5f)).daylight());
    }

    std::printf("sunlit sea, the water moved between the frames (P366):\n");
    const std::vector<Blob> blobs = foam();
    const auto seaBase = make(1.f, [&](int x, int y) { return water(x, y) + foamAt(blobs, x, y); });
    const auto seaUs = make(expected, [&](int x, int y) { return (water(x, y) + foamAt(blobs, x, y)) * waves(x, y); });
    {   // both rules off: the tile field fires on the moving water - what the owner's shot did
        auto us = seaUs; lines.clear();
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, seaBase.data(), us.data(), w, h, tuning(nan, 0.f, 0.f), "CHECK", report);
        assert(c.rows.local.tiles >= 64 && c.rows.local.maxDev >= 0.08f);
        assert(c.rows.local.on() && c.corrected && us != seaUs && has(lines, "corrected (lamps on other flicker phases"));
        std::printf("  rules off: near the clip %.1f %%, corrected\n", 100.0 * c.rows.local.maxDev);
    }
    float seaIncoherence = 0.f;
    {   // the default (no EV: the coherence rule alone): held as incoherent, the frame as captured, no flicker
        auto us = seaUs; lines.clear();
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, seaBase.data(), us.data(), w, h, HybridTuning(), "CHECK", report);
        const HybridUsLocal& l = c.rows.local;
        assert(!l.on() && l.held == 2 && l.incoherence >= 0.5f && l.maxDev >= 0.08f && !c.corrected && us == seaUs);
        assert(l.flicker() == 0.f && hybridUsFlicker({c}) == 0.f && has(lines, "as captured (incoherent"));
        seaIncoherence = l.incoherence;
    }
    {   // the owner's EV100 11.2: held as daylight (before the coherence is even measured)
        auto us = seaUs; lines.clear();
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, seaBase.data(), us.data(), w, h, tuning(11.2f, 10.f, 0.f), "CHECK", report);
        assert(!c.rows.local.on() && c.rows.local.held == 1 && c.rows.local.incoherence < 0.f && !c.corrected && us == seaUs);
        assert(has(lines, "as captured (daylight: EV100 11.2 >= 10.0"));
    }

    std::printf("lamp on another flicker phase (P290's desk lamp; the base clips under it):\n");
    const float cx = 300.f, cy = 384.f;
    auto bulb = [&](int x, int y) { const float r2 = (x - cx) * (x - cx) + (y - cy) * (y - cy); return 3000.f * std::exp(-r2 / (2.f * 90.f * 90.f)); };
    auto dim = [&](int x, int y) { const float r2 = (x - cx) * (x - cx) + (y - cy) * (y - cy); return 1.f - 0.3f * std::exp(-r2 / (2.f * 250.f * 250.f)); };
    auto room = [&](int x, int y) { return 120.f + 640.f * (0.5f + 0.5f * std::sin(x * 0.031f) * std::cos(y * 0.027f)) + bulb(x, y); };
    const auto lampBase = make(1.f, room);
    const auto lampUs = make(expected, [&](int x, int y) { return room(x, y) * dim(x, y); });
    float lampIncoherence = 1.f;
    for (const float ev : {nan, 5.f}) {
        auto us = lampUs; lines.clear();
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, lampBase.data(), us.data(), w, h, tuning(ev, 10.f, 0.5f), "CHECK", report);
        const HybridUsLocal& l = c.rows.local;
        assert(l.on() && l.held == 0 && c.corrected && us != lampUs && l.maxDev > 0.2f);
        assert(l.incoherence >= 0.f && l.incoherence < 0.25f && l.flicker() == l.maxDev && has(lines, "incoherence"));
        lampIncoherence = l.incoherence;
    }
    {   // at daylight brightness no lamp lights the scene: held
        auto us = lampUs; lines.clear();
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, lampBase.data(), us.data(), w, h, tuning(11.2f, 10.f, 0.5f), "CHECK", report);
        assert(!c.rows.local.on() && c.rows.local.held == 1 && !c.corrected && us == lampUs && hybridUsFlicker({c}) == 0.f);
    }
    {   // usDaylightEv 0: the daylight rule off - applied whatever the EV
        auto us = lampUs; lines.clear();
        const HybridUsCheck c = hybridUsRowsCheck(in, 1, lampBase.data(), us.data(), w, h, tuning(15.f, 0.f, 0.5f), "CHECK", report);
        assert(c.rows.local.on() && c.corrected && us != lampUs);
    }
    std::printf("  incoherence: lamp %.3f, moving water %.3f\n", lampIncoherence, seaIncoherence);

    std::printf("row-wise flicker (P197; two periods of a sinus down the frame):\n");
    {
        auto flat = [](int x, int y) { return 120.f + 640.f * (0.5f + 0.5f * std::sin(x * 0.031f) * std::cos(y * 0.027f)); };
        const auto rowBase = make(1.f, flat);
        auto flick = [&](int x, int y) { const float s = float(y) / float(h - 1), a = colourOf(x, y) == 0 ? 0.6f : colourOf(x, y) == 1 ? 0.45f : 0.3f;
                                          return flat(x, y) * (1.f + a * std::sin(6.2831853f * 2.f * s)); };
        const auto rowUs = make(expected, flick);
        {
            auto us = rowUs; lines.clear();
            const HybridUsCheck c = hybridUsRowsCheck(in, 1, rowBase.data(), us.data(), w, h, tuning(5.f, 10.f, 0.5f), "CHECK", report);
            assert(c.rows.apply && !c.rows.daylight && c.corrected && us != rowUs);
        }
        {
            auto us = rowUs; lines.clear();
            const HybridUsCheck c = hybridUsRowsCheck(in, 1, rowBase.data(), us.data(), w, h, tuning(11.2f, 10.f, 0.5f), "CHECK", report);
            assert(c.rows.measured && !c.rows.apply && c.rows.daylight && c.rows.maxDev > 0.2f && !c.corrected && us == rowUs);
            assert(has(lines, "rows against the base") && has(lines, "as captured (daylight"));
        }
        {   // review: held as daylight without a tile field - measured, no flicker (0), not unmeasured (-1)
            HybridTuning t = tuning(11.2f, 10.f, 0.5f); t.usLocal = 0;
            auto us = rowUs; lines.clear();
            const HybridUsCheck c = hybridUsRowsCheck(in, 1, rowBase.data(), us.data(), w, h, t, "CHECK", report);
            assert(c.rows.daylight && c.rows.local.tiles == 0 && us == rowUs && hybridUsFlicker({c}) == 0.f);
            HybridTuning lamp = tuning(5.f, 10.f, 0.5f); lamp.usLocal = 0;
            us = rowUs;
            const HybridUsCheck d = hybridUsRowsCheck(in, 1, rowBase.data(), us.data(), w, h, lamp, "CHECK", report);
            assert(d.rows.apply && hybridUsFlicker({d}) == d.rows.maxDev);
            HybridInput plain = pairInput();
            plain.frames[0].raw = rowBase.data(); plain.frames[1].raw = rowUs.data();
            std::vector<std::vector<uint16_t>> own;
            const HybridInput out = hybridPlainUsRows(plain, t, own, report);
            assert(own.empty() && out.usFlicker == 0.f);
            std::printf("  held as daylight without a tile field: flicker 0 (lamp-lit: %.3f)\n", d.rows.maxDev);
        }
    }

    std::printf("one decision per burst (review): the median of its frames' incoherence\n");
    {
        // on the sunlit base: moving water (two wave patterns) and a lamp-like field (coherent: x0.7 at the first foam blob, a
        // gaussian of 110 px - local, so the row bands keep their level and only the tile field sees it)
        auto spot = [&](int x, int y) { const float dx = x - blobs[0].x, dy = y - blobs[0].y; return 1.f - 0.3f * std::exp(-(dx * dx + dy * dy) / (2.f * 110.f * 110.f)); };
        const auto seaUs2 = make(expected, [&](int x, int y) { return (water(x, y) + foamAt(blobs, x, y)) * waves(x + 23, y + 31); });
        const auto seaLamp = make(expected, [&](int x, int y) { return (water(x, y) + foamAt(blobs, x, y)) * spot(x, y); });
        const auto seaLamp2 = make(expected, [&](int x, int y) { return (water(x, y) + foamAt(blobs, x, y)) * spot(x, y); });
        HybridInput burst = pairInput();
        const HybridFrame ultrashort = burst.frames[1];
        burst.frames.push_back(ultrashort); burst.frames.push_back(ultrashort);
        auto frameAlone = [&](const std::vector<uint16_t>& f) {
            auto us = f; std::vector<std::string> quiet;
            return hybridUsRowsCheck(in, 1, seaBase.data(), us.data(), w, h, HybridTuning(), "ALONE", [&](const std::string& s) { quiet.push_back(s); }).rows.local;
        };
        const HybridUsLocal lampAlone = frameAlone(seaLamp), waterAlone = frameAlone(seaUs2);
        assert(lampAlone.on() && lampAlone.incoherence >= 0.f && lampAlone.incoherence < 0.5f);
        assert(waterAlone.held == 2 && waterAlone.incoherence >= 0.5f);
        std::printf("  frame by frame: lamp field %.3f (applied), moving water %.3f / %.3f (held)\n", lampAlone.incoherence,
                    waterAlone.incoherence, seaIncoherence);
        for (int lampFrames : {1, 2}) {
            const std::vector<const std::vector<uint16_t>*> src = lampFrames == 1
                ? std::vector<const std::vector<uint16_t>*>{&seaUs, &seaUs2, &seaLamp}
                : std::vector<const std::vector<uint16_t>*>{&seaUs, &seaLamp, &seaLamp2};
            const bool held = lampFrames == 1; // the median: moving water / the lamp field
            std::vector<std::vector<uint16_t>> us;
            for (const auto* s : src) us.push_back(*s);
            lines.clear();
            const std::vector<HybridUsCheck> cs = hybridUsRowsChecks(burst, {1, 2, 3}, {us[0].data(), us[1].data(), us[2].data()},
                                                                     seaBase.data(), w, h, HybridTuning(), "BURST", report);
            assert(cs.size() == 3);
            for (size_t i = 0; i < 3; ++i) {
                const HybridUsLocal& l = cs[i].rows.local;
                assert(l.over && l.held == (held ? 2 : 0) && l.on() == !held);
                assert(held ? us[i] == *src[i] : us[i] != *src[i]);
            }
            assert(held ? hybridUsFlicker(cs) == 0.f : hybridUsFlicker(cs) >= 0.08f);
            assert(held ? has(lines, "the burst's deviating tiles") : has(lines, "the burst's"));
            // the plain path: the same decision for every frame
            HybridInput plain = burst;
            plain.frames[0].raw = seaBase.data();
            for (size_t i = 0; i < 3; ++i) plain.frames[i + 1].raw = src[i]->data();
            std::vector<std::vector<uint16_t>> own;
            lines.clear();
            const HybridInput out = hybridPlainUsRows(plain, HybridTuning(), own, report);
            assert(held ? own.empty() && out.usFlicker == 0.f : own.size() == 3 && out.usFlicker >= 0.08f);
            for (size_t f = 1; f < out.frames.size(); ++f) {
                bool fromInput = false;
                for (const auto* s : src) fromInput = fromInput || out.frames[f].raw == s->data();
                assert(fromInput == held);
            }
            std::printf("  %s: every frame %s\n", held ? "2 x moving water + 1 x lamp" : "1 x moving water + 2 x lamp",
                        held ? "held" : "corrected");
        }
    }

    std::printf("plain Bayer burst with the sunlit frame (hybridPlainUsRows):\n");
    {
        HybridInput plain = pairInput();
        plain.frames[0].raw = seaBase.data(); plain.frames[1].raw = seaUs.data();
        for (const float ev : {nan, 11.2f}) {
            std::vector<std::vector<uint16_t>> own;
            lines.clear();
            const HybridInput out = hybridPlainUsRows(plain, tuning(ev, 10.f, 0.5f), own, report);
            assert(out.usRowsChecked && own.empty() && out.usFlicker == 0.f);
            assert(out.frames.size() == 1 || out.frames[1].raw == seaUs.data()); // as captured (or refused by the gain check), never recoloured
            assert(has(lines, std::isnan(ev) ? "as captured (incoherent" : "as captured (daylight"));
        }
        // the rules off: the frame is copied and corrected (before P366)
        std::vector<std::vector<uint16_t>> own;
        lines.clear();
        const HybridInput out = hybridPlainUsRows(plain, tuning(nan, 0.f, 0.f), own, report);
        assert(own.size() == 1 && out.usFlicker >= 0.08f);
    }
    std::printf("check_hybrid_us_daylight: OK\n");
    return 0;
}
