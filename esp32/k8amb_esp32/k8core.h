// K8 ambient sync core for the ESP32 (plan C). Pure C++ with no Arduino or Bluetooth calls, so the
// same code can be checked on a PC. It ports the Java core used by the phone relay 0.4.8:
// K8LotusMapping, DriveModeLight, ColorTone, HueBrightness, Fader, SyncEngine, LotusE1Frames,
// ZenggeFrames, ColorOrder and the per-lamp logic of RelayService. Keep both in step.
// License: AGPL-3.0 (see LICENSE).
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace k8 {

/** Normalized lamp input: RGB 0..255, brightness 0..100, power. valid=false is Java's null. */
struct State {
    int r, g, b, bri;
    bool on, valid;
};
State makeState(int r, int g, int b, int bri, bool on);
State noState();
bool sameState(const State& a, const State& b);

/** Raw SML status (9..32 bytes): three big-endian 16-bit channels, brightness, dim, drive link. */
struct Sml {
    uint8_t bytes[32];
    int len;
    int redRaw, greenRaw, blueRaw, briRaw, dimRaw, modeRaw;
    bool valid;
};
/** Parses the hex body of an "SML <hex>" line. */
bool parseSmlHex(const char* hex, Sml& out);
void smlHex(const Sml& sml, char* out, size_t size);

enum { MODE_ECO = 0x02, MODE_SPORT = 0x03, MODE_SMART = 0x06 };

/** One line from the car relay (RelayProtocol). */
struct RelayLine {
    enum Kind { NONE, HELLO, PING, SML, ALERT, MODE, BAD } kind;
    Sml sml;
    bool alert;
    int mode;  // 0 when the line names no real mode (0x00, 0x0f)
};
RelayLine parseRelayLine(const char* line);

enum ColorOrder { RGB = 0, RBG, GRB, GBR, BRG, BGR };
int parseOrder(const char* text);  // -1 when unknown
const char* orderName(int order);
void toWire(int order, int r, int g, int b, int out[3]);

const int MAX_THEMES = 24;
struct Theme {
    char key[13];
    uint8_t rgb[3];
};

/** Everything the phone app used to keep in its own settings, now sent to the ESP32. */
struct Settings {
    char car[18], lotus[18], zengge[18];  // "" = not set
    int order;                             // Zengge wire order
    bool lotusPower;                       // Lotus power frames option
    int fadeMs, gamma10, redGain, greenGain, blueGain, lotusMax, zenggeMax;
    int hue[6];                            // red, warm, green, sky, blue, purple (20..300)
    int eco[3], sport[3];
    Theme themes[MAX_THEMES];
    int themeCount;
    bool output;                           // lamp BLE writes allowed (off = dry run on serial)
    void defaults();
    const int* theme(const char* key) const;
    bool setTheme(const char* key, const int* rgb);  // rgb null removes
};

/** K8LotusMapping.map: null (valid=false) when the brightness raw value is unknown. */
State mapSml(const Sml& sml, const Settings& settings);
bool isDirectColor(const Sml& sml);
void themeKey(const Sml& sml, char out[13]);
bool driveLinked(const Sml& sml);
/** DriveModeLight.apply. */
State driveApply(const State& mapped, const Sml& sml, int mode, const Settings& settings);
/** ColorTone.apply with the given lamp maximum. */
State tone(const State& s, const Settings& settings, int maxBrightness);
double hueRatioFor(int r, int g, int b, const int ratios[6]);
/** HueBrightness.apply. */
State hueApply(const State& source, const State& toned, const int ratios[6]);

class Fader {
  public:
    Fader() : hasTo(false), start(0), duration(0) {}
    void target(const State& next, long long now, long long durationMs);
    State current(long long now) const;
    bool active(long long now) const;

  private:
    State from, to;
    bool hasTo;
    long long start, duration;
};

/** Lamp frames (HCI-captured color/brightness, Lotus power from the app bytecode). */
size_t lotusColor(int r, int g, int b, uint8_t* out);
size_t lotusBrightness(int percent, uint8_t* out);
size_t lotusPower(bool on, uint8_t* out);
size_t zenggeStatic(int sequence, int r, int g, int b, uint8_t* out);

class Transport {
  public:
    virtual ~Transport() {}
    virtual bool connected() = 0;
    virtual bool connect() = 0;
    virtual bool write(const uint8_t* data, size_t len) = 0;
    virtual void disconnect() = 0;
};

/** SyncEngine port. kind LOTUS (observed frames, optional power) or ZENGGE (scaled color). */
class Engine {
  public:
    enum Kind { LOTUS, ZENGGE };
    Engine(Transport* transport, Kind kind, bool lotusPower, int order);
    void setOrder(int order) { order_ = order; }
    void onState(const State& s) { desired = s; }
    void setEnabled(bool enabled);
    void tick(long long now, long long startupFadeMs);
    unsigned long writes() const { return successfulWrites; }
    unsigned long failures() const { return failures_; }

  private:
    Transport* transport;
    Kind kind;
    bool lotusPower;
    int order_, sequence;
    State desired;
    bool enabled, hadConnection, lastDataWasColor;
    int sentOn;  // -1 unknown, 0 off, 1 on
    int sentRed, sentGreen, sentBlue, sentBrightness;
    long long lastTick, lastAttempt, retryAt, retryDelay, startupAt;
    bool startupComplete;
    unsigned long successfulWrites, failures_;
    State rampStartup(const State& target, int brightness, long long now, long long duration);
    void resetStartup();
    void forgetSent();
    void failed(long long now);
};

/** The per-lamp logic of RelayService.Lamp: tone, per-color ratio, fades, alert pulses. */
class LampCtl {
  public:
    LampCtl(Transport* transport, bool zengge, const Settings& settings);
    void show(const State& state, bool warning, long long now, const Settings& settings);
    void reload(long long now, const Settings& settings);  // settings changed: re-apply at once
    void render(long long now);
    Engine& engine() { return engine_; }
    bool zengge() const { return zengge_; }
    State toned(const State& s, const Settings& settings) const;

  private:
    bool zengge_;
    Engine engine_;
    Fader fader;
    State lastMapped;
    bool cameraWarning, recoveryFade;
    long long fadeMs;
};

/** The car stream logic of RelayService.carLoop/handleStatus. */
class CarLogic {
  public:
    CarLogic() : warning(false), driveMode(0) { lastNormal.valid = false; }
    /** Feeds one relay line; returns true and sets out/warn when the lamps get a new target. */
    bool onLine(const char* line, const Settings& settings, State& out, bool& warn);
    /** Settings changed: the current target again (false while an alert runs or nothing seen). */
    bool reapply(const Settings& settings, State& out, bool& warn);
    void reset() { warning = false; lastNormal.valid = false; }
    int mode() const { return driveMode; }
    bool alerting() const { return warning; }
    const Sml& last() const { return lastNormal; }

  private:
    bool target(const Sml& sml, bool alert, const Settings& settings, State& out, bool& warn);
    Sml lastNormal;
    bool warning;
    int driveMode;
};

}  // namespace k8
