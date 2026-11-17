// See k8core.h. Rounding follows Java: Math.round(x) == floor(x + 0.5).
#include "k8core.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

namespace k8 {

static long long jround(double x) { return (long long)floor(x + 0.5); }
static int clampi(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

State makeState(int r, int g, int b, int bri, bool on) {
    State s;
    s.r = r; s.g = g; s.b = b; s.bri = bri; s.on = on; s.valid = true;
    return s;
}

State noState() {
    State s = makeState(0, 0, 0, 0, false);
    s.valid = false;
    return s;
}

bool sameState(const State& a, const State& b) {
    if (!a.valid || !b.valid) return a.valid == b.valid;
    return a.r == b.r && a.g == b.g && a.b == b.b && a.bri == b.bri && a.on == b.on;
}

static int hexDigit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

bool parseSmlHex(const char* hex, Sml& out) {
    out.valid = false;
    size_t n = strlen(hex);
    if (n % 2 != 0 || n < 18 || n > 64) return false;
    for (size_t i = 0; i < n / 2; i++) {
        int hi = hexDigit(hex[2 * i]), lo = hexDigit(hex[2 * i + 1]);
        if (hi < 0 || lo < 0) return false;
        out.bytes[i] = (uint8_t)((hi << 4) | lo);
    }
    out.len = (int)(n / 2);
    out.redRaw = (out.bytes[0] << 8) | out.bytes[1];
    out.greenRaw = (out.bytes[2] << 8) | out.bytes[3];
    out.blueRaw = (out.bytes[4] << 8) | out.bytes[5];
    out.briRaw = out.bytes[6];
    out.dimRaw = out.bytes[7];
    out.modeRaw = out.bytes[8];
    out.valid = true;
    return true;
}

void smlHex(const Sml& sml, char* out, size_t size) {
    size_t at = 0;
    if (size == 0) return;
    out[0] = 0;
    for (int i = 0; i < sml.len && at + 3 <= size; i++) at += snprintf(out + at, size - at, "%02x", sml.bytes[i]);
}

RelayLine parseRelayLine(const char* raw) {
    RelayLine r;
    r.kind = RelayLine::NONE;
    r.sml.valid = false;
    r.alert = false;
    r.mode = 0;
    char text[96];
    while (*raw == ' ' || *raw == '\t') raw++;
    size_t n = strlen(raw);
    while (n > 0 && (raw[n - 1] == ' ' || raw[n - 1] == '\r' || raw[n - 1] == '\n' || raw[n - 1] == '\t')) n--;
    if (n == 0) return r;
    if (n >= sizeof(text)) { r.kind = RelayLine::BAD; return r; }
    memcpy(text, raw, n);
    text[n] = 0;
    if (strcmp(text, "K8AMB 1") == 0) { r.kind = RelayLine::HELLO; return r; }
    if (strcmp(text, "PING") == 0) { r.kind = RelayLine::PING; return r; }
    if (strcmp(text, "ALERT 1") == 0 || strcmp(text, "ALERT 0") == 0) {
        r.kind = RelayLine::ALERT;
        r.alert = text[6] == '1';
        return r;
    }
    if (strncmp(text, "MODE ", 5) == 0 && n == 7) {
        int hi = hexDigit(text[5]), lo = hexDigit(text[6]);
        if (hi < 0 || lo < 0) { r.kind = RelayLine::BAD; return r; }
        int mode = (hi << 4) | lo;
        // 0x00 follows every change and 0x0f is reported at ignition: neither names a mode.
        if (mode == 0 || mode == 0x0f) { r.kind = RelayLine::BAD; return r; }
        r.kind = RelayLine::MODE;
        r.mode = mode;
        return r;
    }
    if (strncmp(text, "SML ", 4) == 0 && parseSmlHex(text + 4, r.sml)) {
        r.kind = RelayLine::SML;
        return r;
    }
    r.kind = RelayLine::BAD;
    return r;
}

static const char* ORDER_NAMES[] = {"RGB", "RBG", "GRB", "GBR", "BRG", "BGR"};
static const int ORDER_SLOTS[6][3] = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

int parseOrder(const char* text) {
    for (int i = 0; i < 6; i++) {
        const char* a = ORDER_NAMES[i];
        const char* b = text;
        bool same = strlen(b) == 3;
        for (int j = 0; same && j < 3; j++) same = (b[j] & ~0x20) == a[j];
        if (same) return i;
    }
    return -1;
}

const char* orderName(int order) { return order >= 0 && order < 6 ? ORDER_NAMES[order] : "RGB"; }

void toWire(int order, int r, int g, int b, int out[3]) {
    const int want[3] = {r, g, b};
    const int* slot = ORDER_SLOTS[order >= 0 && order < 6 ? order : 0];
    for (int i = 0; i < 3; i++) out[i] = want[slot[i]];
}

void Settings::defaults() {
    car[0] = lotus[0] = zengge[0] = 0;
    order = GRB;
    lotusPower = false;
    fadeMs = 300;
    gamma10 = 18;
    redGain = greenGain = 100;
    blueGain = 85;
    lotusMax = 20;
    zenggeMax = 100;
    const int hues[6] = {65, 150, 100, 100, 65, 65};
    memcpy(hue, hues, sizeof(hue));
    eco[0] = 50; eco[1] = 154; eco[2] = 158;
    sport[0] = 255; sport[1] = 0; sport[2] = 0;
    themeCount = 0;
    output = false;
}

const int* Settings::theme(const char* key) const {
    static int rgb[3];
    for (int i = 0; i < themeCount; i++) {
        if (strcmp(themes[i].key, key) == 0) {
            for (int c = 0; c < 3; c++) rgb[c] = themes[i].rgb[c];
            return rgb;
        }
    }
    return 0;
}

bool Settings::setTheme(const char* key, const int* rgb) {
    if (strlen(key) != 12) return false;
    for (int i = 0; i < 12; i++) {
        char c = key[i];
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    }
    for (int i = 0; i < themeCount; i++) {
        if (strcmp(themes[i].key, key) != 0) continue;
        if (!rgb) {
            themes[i] = themes[--themeCount];
            return true;
        }
        for (int c = 0; c < 3; c++) themes[i].rgb[c] = (uint8_t)rgb[c];
        return true;
    }
    if (!rgb) return true;
    if (themeCount >= MAX_THEMES) return false;
    memcpy(themes[themeCount].key, key, 13);
    for (int c = 0; c < 3; c++) themes[themeCount].rgb[c] = (uint8_t)rgb[c];
    themeCount++;
    return true;
}

bool isDirectColor(const Sml& s) { return s.redRaw <= 255 && s.greenRaw <= 255 && s.blueRaw <= 255; }

void themeKey(const Sml& s, char out[13]) { snprintf(out, 13, "%04x%04x%04x", s.redRaw, s.greenRaw, s.blueRaw); }

State mapSml(const Sml& s, const Settings& settings) {
    int raw = s.briRaw;
    if (raw < 1 || raw > 11) return noState();
    int color[3];
    if (isDirectColor(s)) {
        color[0] = s.redRaw; color[1] = s.greenRaw; color[2] = s.blueRaw;
    } else {
        char key[13];
        themeKey(s, key);
        const int* assigned = settings.theme(key);
        if (assigned) {
            for (int c = 0; c < 3; c++) color[c] = assigned[c];
        } else {
            color[0] = s.redRaw & 255; color[1] = s.greenRaw & 255; color[2] = s.blueRaw & 255;
        }
    }
    bool on = raw != 1;
    return makeState(color[0], color[1], color[2], on ? (raw - 1) * 10 : 0, on);
}

bool driveLinked(const Sml& s) { return s.valid && s.modeRaw == 0x02; }

State driveApply(const State& mapped, const Sml& s, int mode, const Settings& settings) {
    if (!mapped.valid || !driveLinked(s)) return mapped;
    const int* color = mode == MODE_ECO ? settings.eco : mode == MODE_SPORT ? settings.sport : 0;
    if (!color) return mapped;
    return makeState(color[0], color[1], color[2], mapped.bri, mapped.on);
}

static int toneChannel(int value, int gain, int gamma10) {
    double corrected = 255.0 * pow(value / 255.0, gamma10 / 10.0) * gain / 100.0;
    long long v = jround(corrected);
    return (int)(v < 0 ? 0 : v > 255 ? 255 : v);
}

State tone(const State& s, const Settings& st, int maxBrightness) {
    if (!s.valid) return s;
    int gamma = clampi(st.gamma10, 10, 30);
    return makeState(toneChannel(s.r, clampi(st.redGain, 0, 100), gamma),
                     toneChannel(s.g, clampi(st.greenGain, 0, 100), gamma),
                     toneChannel(s.b, clampi(st.blueGain, 0, 100), gamma),
                     (s.bri * clampi(maxBrightness, 1, 100) + 50) / 100, s.on);
}

static const double ANCHOR_HUES[7] = {0, 30, 60, 120, 185, 235, 280};
static const int ANCHOR_RATIO[7] = {0, 1, 1, 2, 3, 4, 5};

static double hueRatio(double hue, const int ratios[6]) {
    const int n = 7;
    for (int i = 0; i < n; i++) {
        double from = ANCHOR_HUES[i], to = i + 1 < n ? ANCHOR_HUES[i + 1] : ANCHOR_HUES[0] + 360;
        double h = hue < from ? hue + 360 : hue;
        if (h >= from && h <= to) {
            double t = (h - from) / (to - from);
            int a = ratios[ANCHOR_RATIO[i]], b = ratios[ANCHOR_RATIO[(i + 1) % n]];
            return a + (b - a) * t;
        }
    }
    return ratios[ANCHOR_RATIO[0]];
}

double hueRatioFor(int red, int green, int blue, const int ratios[6]) {
    int max = red > green ? red : green;
    if (blue > max) max = blue;
    int min = red < green ? red : green;
    if (blue < min) min = blue;
    if (max == 0) return 100;
    double saturation = (max - min) / (double)max;
    if (saturation == 0) return 100;
    double hue, delta = max - min;
    if (max == red) hue = 60 * fmod((green - blue) / delta, 6);
    else if (max == green) hue = 60 * ((blue - red) / delta + 2);
    else hue = 60 * ((red - green) / delta + 4);
    if (hue < 0) hue += 360;
    double weight = saturation * 1.5 < 1 ? saturation * 1.5 : 1;
    return weight * hueRatio(hue, ratios) + (1 - weight) * 100;
}

State hueApply(const State& source, const State& toned, const int ratios[6]) {
    if (!source.valid || !toned.valid) return toned;
    int clamped[6];
    for (int i = 0; i < 6; i++) clamped[i] = clampi(ratios[i], 20, 300);
    double ratio = hueRatioFor(source.r, source.g, source.b, clamped);
    long long b = jround(toned.bri * ratio / 100.0);
    return makeState(toned.r, toned.g, toned.b, (int)(b < 100 ? b : 100), toned.on);
}

static int mix(int a, int b, double t) { return (int)jround(a + (b - a) * t); }

void Fader::target(const State& next, long long now, long long durationMs) {
    State shown = hasTo ? current(now) : noState();
    if (!shown.valid || !shown.on || !next.on || durationMs == 0) from = next;
    else from = shown;
    to = next;
    hasTo = true;
    start = now;
    duration = durationMs;
}

State Fader::current(long long now) const {
    if (!hasTo) return noState();
    if (duration == 0 || now - start >= duration) return to;
    if (now <= start) return from;
    double t = (double)(now - start) / duration;
    return makeState(mix(from.r, to.r, t), mix(from.g, to.g, t), mix(from.b, to.b, t), mix(from.bri, to.bri, t), to.on);
}

bool Fader::active(long long now) const { return hasTo && duration > 0 && now - start < duration; }

size_t lotusColor(int r, int g, int b, uint8_t* o) {
    const uint8_t f[9] = {0x7e, 7, 5, 3, (uint8_t)r, (uint8_t)g, (uint8_t)b, 0x10, 0xef};
    memcpy(o, f, 9);
    return 9;
}

size_t lotusBrightness(int percent, uint8_t* o) {
    const uint8_t f[9] = {0x7e, 4, 1, (uint8_t)percent, 1, 0xff, 2, 1, 0xef};
    memcpy(o, f, 9);
    return 9;
}

size_t lotusPower(bool on, uint8_t* o) {
    const uint8_t f[9] = {0x7e, 7, 4, (uint8_t)(on ? 0xff : 0), 1, (uint8_t)(on ? 1 : 0), 2, 1, 0xef};
    memcpy(o, f, 9);
    return 9;
}

size_t zenggeStatic(int sequence, int r, int g, int b, uint8_t* o) {
    uint8_t p[13] = {0x41, 1, (uint8_t)r, (uint8_t)g, (uint8_t)b, 0, 0, 0, 1, 0, 0, 0xf0, 0};
    int sum = 0;
    for (int i = 0; i < 12; i++) sum += p[i];
    p[12] = (uint8_t)sum;
    const uint8_t head[8] = {0, (uint8_t)sequence, 0x80, 0, 0, 13, 14, 0x0b};
    memcpy(o, head, 8);
    memcpy(o + 8, p, 13);
    return 21;
}

Engine::Engine(Transport* t, Kind k, bool power, int order)
    : transport(t), kind(k), lotusPower(power), order_(order), sequence(0), desired(noState()),
      enabled(false), hadConnection(false), lastDataWasColor(false), sentOn(-1),
      sentRed(-1), sentGreen(-1), sentBlue(-1), sentBrightness(-1),
      lastTick(-1), lastAttempt(-1), retryAt(0), retryDelay(100), startupAt(-1), startupComplete(false),
      successfulWrites(0), failures_(0) {}

void Engine::setEnabled(bool on) {
    enabled = on;
    if (!on) {
        forgetSent();
        hadConnection = false;
        retryAt = 0;
        retryDelay = 100;
        if (transport->connected()) transport->disconnect();
    }
}

static int scale(int channel, int percent) { return (channel * percent + 50) / 100; }

void Engine::tick(long long now, long long startupFadeMs) {
    if (now < 0 || now < lastTick) return;  // the Java engine throws; the firmware clock is monotonic
    lastTick = now;
    if (!enabled || !desired.valid || now < retryAt) return;
    bool observedLotus = kind == LOTUS;
    if (observedLotus && !lotusPower && !desired.on) {
        forgetSent();
        return;
    }
    if (!transport->connected()) {
        hadConnection = false;
        forgetSent();
        if (!transport->connect() || !transport->connected()) {
            failed(now);
            return;
        }
    }
    if (!hadConnection) {
        forgetSent();
        hadConnection = true;
    }
    if (lastAttempt >= 0 && now - lastAttempt < 50) return;
    State target = desired;
    const int maximumBrightness = 100;  // ColorTone already scaled to the lamp maximum
    int targetBrightness = (target.bri * maximumBrightness + 50) / 100;
    if (!target.on) resetStartup();
    else if (startupFadeMs == 0) startupComplete = true;
    else if (!startupComplete) target = rampStartup(target, targetBrightness, now, startupFadeMs);
    uint8_t packet[21];
    size_t len;
    if (kind == ZENGGE) {
        int red = target.on ? scale(target.r, targetBrightness) : 0;
        int green = target.on ? scale(target.g, targetBrightness) : 0;
        int blue = target.on ? scale(target.b, targetBrightness) : 0;
        if (red == sentRed && green == sentGreen && blue == sentBlue) return;
        lastAttempt = now;
        int wire[3];
        toWire(order_, red, green, blue, wire);
        sequence = (sequence + 1) & 0xff;
        len = zenggeStatic(sequence, wire[0], wire[1], wire[2], packet);
        if (transport->write(packet, len)) {
            successfulWrites++;
            retryAt = 0;
            retryDelay = 100;
            sentRed = red; sentGreen = green; sentBlue = blue;
        } else {
            failed(now);
        }
        return;
    }
    bool colorPending = sentRed != target.r || sentGreen != target.g || sentBlue != target.b;
    bool prepareLotus = lotusPower && target.on && !startupComplete && startupAt < 0
            && (colorPending || sentBrightness != targetBrightness);
    int field;
    if (!prepareLotus && lotusPower && (sentOn < 0 || (sentOn == 1) != target.on)) {
        len = lotusPower ? k8::lotusPower(target.on, packet) : 0;
        field = 0;
    } else if (!target.on) {
        return;
    } else if (sentBrightness != targetBrightness && (lastDataWasColor || !colorPending)) {
        len = lotusBrightness(targetBrightness, packet);
        field = 2;
    } else if (colorPending) {
        len = lotusColor(target.r, target.g, target.b, packet);
        field = 1;
    } else {
        return;
    }
    lastAttempt = now;
    if (!transport->write(packet, len)) {
        failed(now);
        return;
    }
    successfulWrites++;
    retryAt = 0;
    retryDelay = 100;
    if (field == 0) {
        sentOn = target.on ? 1 : 0;
        if (!target.on) {
            sentRed = sentGreen = sentBlue = sentBrightness = -1;
            lastDataWasColor = false;
        }
    } else if (field == 1) {
        lastDataWasColor = true;
        sentRed = target.r; sentGreen = target.g; sentBlue = target.b;
    } else {
        lastDataWasColor = false;
        sentBrightness = targetBrightness;
    }
}

State Engine::rampStartup(const State& target, int brightness, long long now, long long duration) {
    if (startupAt < 0 && sentRed == 0 && sentGreen == 0 && sentBlue == 0
            && (kind != LOTUS || sentBrightness == brightness) && (!lotusPower || sentOn == 1)) {
        startupAt = now;
    }
    long long elapsed = startupAt < 0 ? 0 : now - startupAt;
    if (elapsed >= duration) {
        startupComplete = true;
        return target;
    }
    double level = (double)elapsed / duration;
    return makeState((int)jround(target.r * level), (int)jround(target.g * level), (int)jround(target.b * level),
                     target.bri, true);
}

void Engine::resetStartup() {
    startupAt = -1;
    startupComplete = false;
}

void Engine::forgetSent() {
    resetStartup();
    sentOn = -1;
    lastDataWasColor = false;
    sentRed = sentGreen = sentBlue = sentBrightness = -1;
}

void Engine::failed(long long now) {
    failures_++;
    hadConnection = false;
    forgetSent();
    retryAt = now + retryDelay;
    retryDelay = retryDelay * 2 < 5000 ? retryDelay * 2 : 5000;
    transport->disconnect();
}

static const long long STARTUP_FADE_MS = 600;

LampCtl::LampCtl(Transport* transport, bool zengge, const Settings& s)
    : zengge_(zengge),
      engine_(transport, zengge ? Engine::ZENGGE : Engine::LOTUS, s.lotusPower, s.order),
      lastMapped(noState()), cameraWarning(false), recoveryFade(false), fadeMs(clampi(s.fadeMs, 0, 5000)) {
    engine_.setEnabled(true);
}

State LampCtl::toned(const State& s, const Settings& st) const {
    return hueApply(s, tone(s, st, zengge_ ? st.zenggeMax : st.lotusMax), st.hue);
}

void LampCtl::show(const State& state, bool warning, long long now, const Settings& st) {
    if (sameState(state, lastMapped) && warning == cameraWarning) return;
    recoveryFade = !warning && (cameraWarning || (recoveryFade && fader.active(now)));
    lastMapped = state;
    cameraWarning = warning;
    // The factory alert blinks once a second; a fade would blur and delay it.
    long long duration = warning || (zengge_ && !recoveryFade) ? 0 : fadeMs;
    fader.target(toned(state, st), now, duration);
    if (zengge_) render(now);
}

void LampCtl::reload(long long now, const Settings& st) {
    fadeMs = clampi(st.fadeMs, 0, 5000);
    engine_.setOrder(st.order);
    if (lastMapped.valid) fader.target(toned(lastMapped, st), now, 0);
}

void LampCtl::render(long long now) {
    State shown = fader.current(now);
    if (!shown.valid) return;
    engine_.onState(shown);
    engine_.tick(now, fadeMs == 0 || cameraWarning ? 0 : STARTUP_FADE_MS);
}

bool CarLogic::target(const Sml& sml, bool alert, const Settings& st, State& out, bool& warn) {
    State mapped = mapSml(sml, st);
    // A camera alert (red blink) always wins over the drive mode color, as on the car.
    State s = alert ? mapped : driveApply(mapped, sml, driveMode, st);
    if (!s.valid) return false;
    out = s;
    warn = alert;
    return true;
}

bool CarLogic::onLine(const char* line, const Settings& st, State& out, bool& warn) {
    RelayLine r = parseRelayLine(line);
    switch (r.kind) {
        case RelayLine::MODE:
            driveMode = r.mode;
            return !warning && lastNormal.valid && target(lastNormal, false, st, out, warn);
        case RelayLine::ALERT: {
            bool ended = warning && !r.alert;
            warning = r.alert;
            // Restore promptly even if the relay deduplicates the next normal SML.
            return ended && lastNormal.valid && target(lastNormal, false, st, out, warn);
        }
        case RelayLine::SML:
            if (!warning) lastNormal = r.sml;
            return target(r.sml, warning, st, out, warn);
        default:
            return false;
    }
}

bool CarLogic::reapply(const Settings& st, State& out, bool& warn) {
    return !warning && lastNormal.valid && target(lastNormal, false, st, out, warn);
}

}  // namespace k8
