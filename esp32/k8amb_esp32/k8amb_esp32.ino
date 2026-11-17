// K8 ambient sync, plan C: ESP32 (WROOM-32D, classic BT + BLE) between the car relay and the lamps.
//
//  car NEMESIS relay 0.4.0 --RFCOMM "K8 Ambient ESP32" (SPP, read-only status)--> ESP32 --BLE--> Lotus / Zengge
//  phone app 0.5.0 --BLE "K8AMB-ESP32" config service--> ESP32 (settings hand-over only)
//
// The ESP32 runs the same color pipeline as the phone relay 0.4.8 (k8core.*). Lamp BLE writes are
// OFF by default ("SET output 1" turns them on): with output off every frame is printed on USB serial
// as "TX lotus|zengge <hex>" instead, so the board can be checked on a PC without touching a lamp.
// USB serial (115200) accepts the same text commands as the phone, plus simulated relay lines.
// License: AGPL-3.0 (see LICENSE).

#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BluetoothSerial.h>
#include <Preferences.h>

#include <map>
#include <string>

#include "k8core.h"

struct Out;  // used by the generated prototypes

static const char* FW_VERSION = "0.1.2";
static const char* DEVICE_NAME = "K8AMB-ESP32";
static const char* CFG_SERVICE = "6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b9e0";
static const char* CFG_RX = "6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b9e1";
static const char* CFG_TX = "6b38a0d2-4f7e-4c1b-9d5a-2e8f71c3b9e2";
static const char* CAR_SERVICE_NAME = "K8 Ambient ESP32";
static const uint32_t LINK_TIMEOUT_MS = 30000;  // the relay pings every 10 s
static const int K8_LINE_MAX = 128;

// ---------------------------------------------------------------- shared state
static k8::Settings gSettings;           // written only by the main loop
static SemaphoreHandle_t gLock;          // guards gSettings copies, gPost, status strings
static volatile uint32_t gReloadVer = 1, gRebuildVer = 1, gCarVer = 1;
static volatile uint32_t gPairNumber = 0;
static volatile bool gPairPending = false;
static volatile int gPairResult = 0;  // 1 bonded, 2 failed
static int gCarChannel = 0;              // 0 = by SDP name; serial "SET carch n" for a fixed channel

struct Post {
    k8::State state;
    bool warn;
    uint32_t ver;
};
static Post gPost = {k8::noState(), false, 0};

static k8::CarLogic gCar;                // main loop only
static char gCarStatus[64] = "starting";
static char gLampStatus[2][64] = {"-", "-"};
static unsigned long gLampWrites[2], gLampFails[2];
static k8::State gTarget = k8::noState();

struct LineMsg {
    char text[K8_LINE_MAX];
    uint8_t source;  // 0 car, 1 phone BLE, 2 serial simulation
};
static QueueHandle_t gLines;             // relay lines (car task, serial simulation)
static QueueHandle_t gCommands;          // phone BLE commands

static BluetoothSerial SerialBT;
static Preferences gPrefs;
static BLECharacteristic* gTx = nullptr;
static volatile bool gPhoneConnected = false;
static SemaphoreHandle_t gBleConnect;    // one lamp connection attempt at a time

static void setStatus(char* target, size_t size, const char* text) {
    xSemaphoreTake(gLock, portMAX_DELAY);
    strncpy(target, text, size - 1);
    target[size - 1] = 0;
    xSemaphoreGive(gLock);
}

static void copySettings(k8::Settings& out) {
    xSemaphoreTake(gLock, portMAX_DELAY);
    out = gSettings;
    xSemaphoreGive(gLock);
}

static void logLine(const char* text) {
    Serial.print("LOG ");
    Serial.println(text);
}

// ---------------------------------------------------------------- reply sinks
struct Out {
    virtual void line(const char* text) = 0;
    virtual ~Out() {}
};
struct SerialOut : Out {
    void line(const char* text) override { Serial.println(text); }
};
struct BleOut : Out {
    void line(const char* text) override {
        if (!gTx || !gPhoneConnected) return;
        gTx->setValue((uint8_t*)text, strlen(text));
        gTx->notify();
        delay(4);
    }
};

// ---------------------------------------------------------------- lamp transport
static const char* SLOT_NAME[2] = {"lotus", "zengge"};

class LampLink : public k8::Transport {
  public:
    LampLink(int slot, const char* address, bool real) : slot_(slot), real_(real), client_(nullptr), write_(nullptr), ready_(false), random_(false) {
        strncpy(address_, address, sizeof(address_) - 1);
        address_[sizeof(address_) - 1] = 0;
        setStatus(gLampStatus[slot_], sizeof(gLampStatus[slot_]), real_ ? "대기" : "시험 모드(쓰기 안 함)");
    }
    ~LampLink() override {
        disconnect();
        if (client_) delete client_;
    }
    bool connected() override { return !real_ || (ready_ && client_ && client_->isConnected()); }

    bool connect() override {
        if (!real_) return true;
        disconnect();
        if (!address_[0]) return false;
        xSemaphoreTake(gBleConnect, portMAX_DELAY);
        bool ok = open();
        xSemaphoreGive(gBleConnect);
        if (!ok) disconnect();
        return ok;
    }

    bool write(const uint8_t* data, size_t len) override {
        char hex[2 * 21 + 1];
        for (size_t i = 0; i < len && i < 21; i++) sprintf(hex + 2 * i, "%02x", data[i]);
        if (!real_) {
            Serial.printf("TX %s %s\n", SLOT_NAME[slot_], hex);
            return true;
        }
        if (!connected() || !write_) return false;
        // Write without response, as the phone did; the stack queues it.
        write_->writeValue((uint8_t*)data, len, false);
        return true;
    }

    void disconnect() override {
        ready_ = false;
        write_ = nullptr;
        if (client_ && client_->isConnected()) client_->disconnect();
    }

  private:
    bool open() {
        setStatus(gLampStatus[slot_], sizeof(gLampStatus[slot_]), "연결 중");
        if (!client_) client_ = BLEDevice::createClient();
        BLEAddress address{String(address_)};
        // Most lamps use a public address; try the other type once if that fails, and keep it.
        bool linked = client_->connect(address, random_ ? BLE_ADDR_TYPE_RANDOM : BLE_ADDR_TYPE_PUBLIC);
        if (!linked) {
            random_ = !random_;
            linked = client_->connect(address, random_ ? BLE_ADDR_TYPE_RANDOM : BLE_ADDR_TYPE_PUBLIC);
        }
        if (!linked) {
            setStatus(gLampStatus[slot_], sizeof(gLampStatus[slot_]), "연결 실패");
            return false;
        }
        // Lotus dropped service discovery right after connecting on the phone (2026-11-11): settle first.
        if (slot_ == 0) delay(500);
        BLERemoteService* service = client_->getService(BLEUUID((uint16_t)(slot_ == 0 ? 0xfff0 : 0xffff)));
        BLERemoteCharacteristic* write = service ? service->getCharacteristic(BLEUUID((uint16_t)(slot_ == 0 ? 0xfff3 : 0xff01))) : nullptr;
        if (!write) {
            setStatus(gLampStatus[slot_], sizeof(gLampStatus[slot_]), "조명 종류가 다름(서비스 없음)");
            return false;
        }
        if (slot_ == 1) {
            BLERemoteCharacteristic* notify = service->getCharacteristic(BLEUUID((uint16_t)0xff02));
            if (notify && notify->canNotify()) notify->registerForNotify(nullptr);
        }
        write_ = write;
        ready_ = true;
        char text[64];
        snprintf(text, sizeof(text), "준비됨 (MTU %u)", (unsigned)client_->getMTU());
        setStatus(gLampStatus[slot_], sizeof(gLampStatus[slot_]), text);
        return true;
    }

    int slot_;
    bool real_;
    char address_[18];
    BLEClient* client_;
    BLERemoteCharacteristic* write_;
    volatile bool ready_;
    bool random_;
};

// ---------------------------------------------------------------- lamp tasks
static void lampTask(void* arg) {
    const int slot = (int)(intptr_t)arg;
    static k8::Settings local[2];
    k8::Settings& st = local[slot];
    LampLink* link = nullptr;
    k8::LampCtl* ctl = nullptr;
    uint32_t rebuildSeen = 0, reloadSeen = 0, postSeen = 0;
    for (;;) {
        long long now = (long long)(esp_timer_get_time() / 1000);
        if (gRebuildVer != rebuildSeen) {
            rebuildSeen = gRebuildVer;
            reloadSeen = gReloadVer;
            copySettings(st);
            delete ctl;
            ctl = nullptr;
            delete link;
            link = nullptr;
            const char* address = slot == 0 ? st.lotus : st.zengge;
            // Dry run shows both lamps' frames; real output needs a saved address.
            if (!st.output || address[0]) {
                link = new LampLink(slot, address, st.output);
                ctl = new k8::LampCtl(link, slot == 1, st);
                postSeen = 0;  // show the current target again on the new link
            } else {
                setStatus(gLampStatus[slot], sizeof(gLampStatus[slot]), "주소 없음");
            }
        }
        if (ctl && gReloadVer != reloadSeen) {
            reloadSeen = gReloadVer;
            copySettings(st);
            ctl->reload(now, st);
        }
        if (ctl) {
            Post post;
            xSemaphoreTake(gLock, portMAX_DELAY);
            post = gPost;
            xSemaphoreGive(gLock);
            if (post.ver != postSeen && post.state.valid) {
                postSeen = post.ver;
                ctl->show(post.state, post.warn, now, st);
            }
            ctl->render(now);
            gLampWrites[slot] = ctl->engine().writes();
            gLampFails[slot] = ctl->engine().failures();
        }
        vTaskDelay(pdMS_TO_TICKS(25));
    }
}

// ---------------------------------------------------------------- car link task
static bool readLine(char* buf, int& len, uint32_t waitMs, bool checkLink = true) {
    uint32_t until = millis() + waitMs;
    while ((int32_t)(until - millis()) > 0) {
        while (SerialBT.available()) {
            int c = SerialBT.read();
            if (c == '\n') {
                buf[len] = 0;
                return true;
            }
            if (c != '\r' && len < K8_LINE_MAX - 1) buf[len++] = (char)c;
        }
        if (checkLink && !SerialBT.connected(0)) return false;
        vTaskDelay(pdMS_TO_TICKS(10));
    }
    return false;
}

static void postLine(const char* text, uint8_t source) {
    LineMsg msg;
    strncpy(msg.text, text, K8_LINE_MAX - 1);
    msg.text[K8_LINE_MAX - 1] = 0;
    msg.source = source;
    xQueueSend(gLines, &msg, pdMS_TO_TICKS(100));
}

/** Connects to one channel and checks the relay greeting. */
static bool tryChannel(const BTAddress& address, int channel) {
    char text[112];
    snprintf(text, sizeof(text), "채널 %d 연결 중", channel);
    setStatus(gCarStatus, sizeof(gCarStatus), text);
    // Insecure: the relay's ESP32 service needs no bonding (the head unit has no pairing flow for us).
    // BluetoothSerial keeps one client handle and drops any new link while an old one is recorded (a late open of a
    // timed-out attempt). Clear it before and after each attempt.
    if (SerialBT.hasClient()) {
        SerialBT.disconnect();
        vTaskDelay(pdMS_TO_TICKS(500));
    }
    if (!SerialBT.connect(address, channel, ESP_SPP_SEC_NONE, ESP_SPP_ROLE_MASTER)) {
        vTaskDelay(pdMS_TO_TICKS(1500));
        if (SerialBT.hasClient()) SerialBT.disconnect();
        logLine("car connect timed out");
        return false;
    }
    char line[K8_LINE_MAX];
    int len = 0;
    bool got = readLine(line, len, 4000, false);  // link flag not trusted, see carTask
    if (got && strcmp(line, "K8AMB 1") == 0) return true;
    line[len < 24 ? len : 24] = 0;
    snprintf(text, sizeof(text), "car channel %d: no greeting (%s, %d bytes: %s)", channel, got ? "line" : "timeout", len, line);
    logLine(text);
    SerialBT.disconnect();
    return false;
}

static void carTask(void*) {
    uint32_t backoff = 3000;
    for (;;) {
        k8::Settings st;
        copySettings(st);
        uint32_t carVer = gCarVer;
        if (!st.car[0]) {
            setStatus(gCarStatus, sizeof(gCarStatus), "차량 주소 없음");
            vTaskDelay(pdMS_TO_TICKS(2000));
            continue;
        }
        BTAddress address{String(st.car)};
        bool linked = false;
        int fixed = gCarChannel;
        if (fixed > 0) {
            linked = tryChannel(address, fixed);
        } else {
            setStatus(gCarStatus, sizeof(gCarStatus), "서비스 찾는 중");
            std::map<int, std::string> channels = SerialBT.getChannels(address);
            String found;
            // Our service first, then any other "K8 Ambient" service, then unnamed SPP services.
            for (int pass = 0; pass < 3 && !linked; pass++) {
                for (auto& entry : channels) {
                    const std::string& name = entry.second;
                    bool match = pass == 0 ? name.find(CAR_SERVICE_NAME) != std::string::npos
                               : pass == 1 ? name.find("K8 Ambient") != std::string::npos && name.find(CAR_SERVICE_NAME) == std::string::npos
                                           : name.empty();
                    if (pass == 0) found += String(entry.first) + "=" + String(name.c_str()) + " ";
                    if (match && tryChannel(address, entry.first)) {
                        linked = true;
                        break;
                    }
                }
            }
            if (!linked) {
                String text = "car SPP channels: " + (found.length() ? found : String("(none)"));
                logLine(text.c_str());
            }
        }
        if (!linked) {
            char text[64];
            snprintf(text, sizeof(text), "연결 실패, %lu초 후 재시도", (unsigned long)(backoff / 1000));
            setStatus(gCarStatus, sizeof(gCarStatus), text);
            vTaskDelay(pdMS_TO_TICKS(backoff));
            backoff = backoff * 2 > 30000 ? 30000 : backoff * 2;
            continue;
        }
        backoff = 3000;
        setStatus(gCarStatus, sizeof(gCarStatus), "연결됨");
        logLine("car connected");
        char line[K8_LINE_MAX];
        int len = 0;
        uint32_t lastLine = millis();
        // The link state flag is not trusted here: a late close event of an earlier attempt (SDP probe or a wrong
        // channel) clears it while this link is up (seen in the car 2026-11-18: dropped right after the first lines).
        // The relay pings every 10 s, so silence is the disconnect signal.
        while (gCarVer == carVer) {
            if (readLine(line, len, 200, false)) {
                lastLine = millis();
                postLine(line, 0);
                len = 0;
                continue;
            }
            if (millis() - lastLine > LINK_TIMEOUT_MS) {
                logLine("car silent, reconnect");
                break;
            }
        }
        SerialBT.disconnect();
        setStatus(gCarStatus, sizeof(gCarStatus), "연결 끊김");
        logLine("car disconnected");
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

// ---------------------------------------------------------------- settings text and commands
static void rgbText(const int* c, char* out, size_t size) { snprintf(out, size, "%d,%d,%d", c[0], c[1], c[2]); }

static bool parseInts(const char* text, int* out, int count, int low, int high) {
    char buf[64];
    strncpy(buf, text, sizeof(buf) - 1);
    buf[sizeof(buf) - 1] = 0;
    char* save = nullptr;
    char* part = strtok_r(buf, ",", &save);
    for (int i = 0; i < count; i++) {
        if (!part) return false;
        char* end = nullptr;
        long v = strtol(part, &end, 10);
        while (end && *end == ' ') end++;
        if (!end || *end || v < low || v > high) return false;
        out[i] = (int)v;
        part = strtok_r(nullptr, ",", &save);
    }
    return part == nullptr;
}

static bool validAddress(const char* text) {
    if (strlen(text) != 17) return false;
    for (int i = 0; i < 17; i++) {
        char c = text[i];
        if (i % 3 == 2 ? c != ':' : !isxdigit((unsigned char)c)) return false;
    }
    return true;
}

/** Settings as SET/THEME lines (also the NVS format). */
static String settingsText(const k8::Settings& s) {
    char buf[160], a[24], b[24];
    String out;
    auto add = [&](const char* line) { out += line; out += '\n'; };
    snprintf(buf, sizeof(buf), "SET car %s", s.car[0] ? s.car : "-"); add(buf);
    snprintf(buf, sizeof(buf), "SET lotus %s", s.lotus[0] ? s.lotus : "-"); add(buf);
    snprintf(buf, sizeof(buf), "SET zengge %s", s.zengge[0] ? s.zengge : "-"); add(buf);
    snprintf(buf, sizeof(buf), "SET order %s", k8::orderName(s.order)); add(buf);
    snprintf(buf, sizeof(buf), "SET power %d", s.lotusPower ? 1 : 0); add(buf);
    snprintf(buf, sizeof(buf), "SET fade %d", s.fadeMs); add(buf);
    snprintf(buf, sizeof(buf), "SET gamma %d", s.gamma10); add(buf);
    snprintf(buf, sizeof(buf), "SET red %d", s.redGain); add(buf);
    snprintf(buf, sizeof(buf), "SET green %d", s.greenGain); add(buf);
    snprintf(buf, sizeof(buf), "SET blue %d", s.blueGain); add(buf);
    snprintf(buf, sizeof(buf), "SET lotusMax %d", s.lotusMax); add(buf);
    snprintf(buf, sizeof(buf), "SET zenggeMax %d", s.zenggeMax); add(buf);
    snprintf(buf, sizeof(buf), "SET hue %d,%d,%d,%d,%d,%d", s.hue[0], s.hue[1], s.hue[2], s.hue[3], s.hue[4], s.hue[5]); add(buf);
    rgbText(s.eco, a, sizeof(a));
    rgbText(s.sport, b, sizeof(b));
    snprintf(buf, sizeof(buf), "SET eco %s", a); add(buf);
    snprintf(buf, sizeof(buf), "SET sport %s", b); add(buf);
    snprintf(buf, sizeof(buf), "SET output %d", s.output ? 1 : 0); add(buf);
    for (int i = 0; i < s.themeCount; i++) {
        snprintf(buf, sizeof(buf), "THEME %s %d,%d,%d", s.themes[i].key, s.themes[i].rgb[0], s.themes[i].rgb[1], s.themes[i].rgb[2]);
        add(buf);
    }
    return out;
}

enum Change { NONE = 0, RELOAD = 1, REBUILD = 2, CAR = 4 };

/** Applies one SET/THEME line to s. Returns false with a reason on bad input. */
static bool applySetting(k8::Settings& s, const char* line, int& change, const char*& why) {
    char key[16], value[96];
    why = "형식";
    if (strncmp(line, "THEMES CLEAR", 12) == 0) {
        s.themeCount = 0;
        change |= RELOAD;
        return true;
    }
    if (strncmp(line, "THEME ", 6) == 0) {
        if (sscanf(line + 6, "%15s %95s", key, value) != 2) return false;
        int rgb[3];
        if (strcmp(value, "-") == 0) {
            if (!s.setTheme(key, nullptr)) { why = "테마 키"; return false; }
        } else if (!parseInts(value, rgb, 3, 0, 255) || !s.setTheme(key, rgb)) {
            why = "테마";
            return false;
        }
        change |= RELOAD;
        return true;
    }
    if (strncmp(line, "SET ", 4) != 0 || sscanf(line + 4, "%15s %95s", key, value) != 2) return false;
    int v[6];
    auto num = [&](int low, int high) { return parseInts(value, v, 1, low, high); };
    why = "값 범위";
    if (!strcmp(key, "car") || !strcmp(key, "lotus") || !strcmp(key, "zengge")) {
        char* target = !strcmp(key, "car") ? s.car : !strcmp(key, "lotus") ? s.lotus : s.zengge;
        if (strcmp(value, "-") == 0) value[0] = 0;
        else if (!validAddress(value)) { why = "주소"; return false; }
        for (char* c = value; *c; c++) *c = toupper(*c);
        if (strcmp(target, value) != 0) change |= !strcmp(key, "car") ? CAR : REBUILD;
        strcpy(target, value);
        return true;
    }
    if (!strcmp(key, "order")) {
        int order = k8::parseOrder(value);
        if (order < 0) return false;
        s.order = order;
        change |= RELOAD;
        return true;
    }
    if (!strcmp(key, "power") || !strcmp(key, "output")) {
        if (!num(0, 1)) return false;
        bool& field = !strcmp(key, "power") ? s.lotusPower : s.output;
        if (field != (v[0] == 1)) change |= REBUILD;
        field = v[0] == 1;
        return true;
    }
    struct { const char* name; int* field; int low, high; } ints[] = {
        {"fade", &s.fadeMs, 0, 5000}, {"gamma", &s.gamma10, 10, 30}, {"red", &s.redGain, 0, 100},
        {"green", &s.greenGain, 0, 100}, {"blue", &s.blueGain, 0, 100}, {"lotusMax", &s.lotusMax, 1, 100},
        {"zenggeMax", &s.zenggeMax, 1, 100}};
    for (auto& item : ints) {
        if (strcmp(key, item.name)) continue;
        if (!num(item.low, item.high)) return false;
        *item.field = v[0];
        change |= RELOAD;
        return true;
    }
    if (!strcmp(key, "hue")) {
        if (!parseInts(value, v, 6, 20, 300)) return false;
        memcpy(s.hue, v, sizeof(s.hue));
        change |= RELOAD;
        return true;
    }
    if (!strcmp(key, "eco") || !strcmp(key, "sport")) {
        if (!parseInts(value, v, 3, 0, 255)) return false;
        memcpy(!strcmp(key, "eco") ? s.eco : s.sport, v, sizeof(s.eco));
        change |= RELOAD;
        return true;
    }
    why = "알 수 없는 항목";
    return false;
}

static void postTarget(const k8::State& state, bool warn) {
    xSemaphoreTake(gLock, portMAX_DELAY);
    gPost.state = state;
    gPost.warn = warn;
    gPost.ver++;
    gTarget = state;
    xSemaphoreGive(gLock);
}

static void commit(const k8::Settings& next, int change) {
    xSemaphoreTake(gLock, portMAX_DELAY);
    gSettings = next;
    xSemaphoreGive(gLock);
    if (change & CAR) {
        gCarVer++;
        gCar.reset();
    }
    if (change & REBUILD) gRebuildVer++;
    if (change & (RELOAD | REBUILD)) {
        gReloadVer++;
        k8::State state;
        bool warn;
        if (gCar.reapply(next, state, warn)) postTarget(state, warn);
    }
}

static void loadSettings() {
    k8::Settings s;
    s.defaults();
    gPrefs.begin("k8amb", true);
    String text = gPrefs.getString("cfg", "");
    gPrefs.end();
    int change = 0, bad = 0;
    const char* why;
    int start = 0;
    while (start < (int)text.length()) {
        int end = text.indexOf('\n', start);
        if (end < 0) end = text.length();
        String line = text.substring(start, end);
        start = end + 1;
        if (line.length() && !applySetting(s, line.c_str(), change, why)) bad++;
    }
    gSettings = s;
    char msg[64];
    snprintf(msg, sizeof(msg), "settings loaded (%d bytes, %d bad lines)", (int)text.length(), bad);
    logLine(msg);
}

static void status(Out& out) {
    char buf[160], target[48], sml[72], key[13];
    k8::Settings st;
    copySettings(st);
    xSemaphoreTake(gLock, portMAX_DELAY);
    String car = gCarStatus, lotus = gLampStatus[0], zengge = gLampStatus[1];
    k8::State t = gTarget;
    xSemaphoreGive(gLock);
    snprintf(buf, sizeof(buf), "ST ver %s", FW_VERSION); out.line(buf);
    snprintf(buf, sizeof(buf), "ST car %s", car.c_str()); out.line(buf);
    snprintf(buf, sizeof(buf), "ST lotus %s / 쓰기 %lu 실패 %lu", lotus.c_str(), gLampWrites[0], gLampFails[0]); out.line(buf);
    snprintf(buf, sizeof(buf), "ST zengge %s / 쓰기 %lu 실패 %lu", zengge.c_str(), gLampWrites[1], gLampFails[1]); out.line(buf);
    snprintf(buf, sizeof(buf), "ST output %d", st.output ? 1 : 0); out.line(buf);
    const k8::Sml& last = gCar.last();
    if (last.valid) {
        k8::smlHex(last, sml, sizeof(sml));
        snprintf(buf, sizeof(buf), "ST sml %s", sml); out.line(buf);
        if (!k8::isDirectColor(last)) {
            k8::themeKey(last, key);
            snprintf(buf, sizeof(buf), "ST theme %s", key);
        } else {
            snprintf(buf, sizeof(buf), "ST theme -");
        }
        out.line(buf);
        snprintf(buf, sizeof(buf), "ST mode %d linked %d alert %d", gCar.mode(), k8::driveLinked(last) ? 1 : 0, gCar.alerting() ? 1 : 0);
        out.line(buf);
    } else {
        out.line("ST sml -");
        out.line("ST theme -");
        snprintf(buf, sizeof(buf), "ST mode %d linked 0 alert %d", gCar.mode(), gCar.alerting() ? 1 : 0);
        out.line(buf);
    }
    if (t.valid) snprintf(target, sizeof(target), "%d,%d,%d,%d,%d", t.r, t.g, t.b, t.bri, t.on ? 1 : 0);
    else strcpy(target, "-");
    snprintf(buf, sizeof(buf), "ST target %s", target); out.line(buf);
    snprintf(buf, sizeof(buf), "ST heap %u phone %d", (unsigned)ESP.getFreeHeap(), gPhoneConnected ? 1 : 0); out.line(buf);
    out.line("ST end");
}

/** CALC <sml> <mode> <alert>: the car pipeline and both lamps' steady output for the given input. */
static void calc(const char* args, Out& out) {
    char hex[72];
    int mode = 0, alert = 0;
    k8::Sml sml;
    if (sscanf(args, "%71s %d %d", hex, &mode, &alert) != 3 || !k8::parseSmlHex(hex, sml)) {
        out.line("ERR CALC 형식");
        return;
    }
    k8::Settings st;
    copySettings(st);
    k8::State mapped = k8::mapSml(sml, st);
    k8::State state = alert ? mapped : k8::driveApply(mapped, sml, mode, st);
    if (!state.valid) {
        out.line("CALC none");
        return;
    }
    k8::State lotus = k8::hueApply(state, k8::tone(state, st, st.lotusMax), st.hue);
    k8::State zengge = k8::hueApply(state, k8::tone(state, st, st.zenggeMax), st.hue);
    int wire[3] = {0, 0, 0};
    if (zengge.on) {
        k8::toWire(st.order, (zengge.r * zengge.bri + 50) / 100, (zengge.g * zengge.bri + 50) / 100,
                   (zengge.b * zengge.bri + 50) / 100, wire);
    }
    char buf[160];
    snprintf(buf, sizeof(buf), "CALC %d,%d,%d,%d,%d L %d,%d,%d,%d,%d Z %d,%d,%d", state.r, state.g, state.b, state.bri,
             state.on ? 1 : 0, lotus.r, lotus.g, lotus.b, lotus.bri, lotus.on ? 1 : 0, wire[0], wire[1], wire[2]);
    out.line(buf);
}

static void command(const char* raw, Out& out, bool fromSerial) {
    char line[K8_LINE_MAX];
    while (*raw == ' ') raw++;
    strncpy(line, raw, K8_LINE_MAX - 1);
    line[K8_LINE_MAX - 1] = 0;
    for (int n = strlen(line); n > 0 && (line[n - 1] == '\r' || line[n - 1] == ' '); n--) line[n - 1] = 0;
    if (!line[0]) return;
    char reply[K8_LINE_MAX + 16];
    if (!strcmp(line, "HELLO")) {
        snprintf(reply, sizeof(reply), "K8ESP 1 %s", FW_VERSION);
        out.line(reply);
    } else if (!strncmp(line, "SET ", 4) || !strncmp(line, "THEME", 5)) {
        k8::Settings next;
        copySettings(next);
        int change = 0;
        const char* why;
        if (!strncmp(line, "SET carch ", 10)) {
            int ch = atoi(line + 10);
            if (ch < 0 || ch > 30) { out.line("ERR SET carch 값 범위"); return; }
            gCarChannel = ch;  // serial/diagnostic only, not saved
            gCarVer++;
            snprintf(reply, sizeof(reply), "OK %s", line);
            out.line(reply);
            return;
        }
        if (!applySetting(next, line, change, why)) {
            snprintf(reply, sizeof(reply), "ERR %s (%s)", line, why);
            out.line(reply);
            return;
        }
        commit(next, change);
        snprintf(reply, sizeof(reply), "OK %s", line);
        out.line(reply);
    } else if (!strcmp(line, "SAVE")) {
        k8::Settings st;
        copySettings(st);
        String text = settingsText(st);
        gPrefs.begin("k8amb", false);
        size_t n = gPrefs.putString("cfg", text);
        gPrefs.end();
        out.line(n == text.length() ? "OK SAVE" : "ERR SAVE");
    } else if (!strcmp(line, "GET")) {
        k8::Settings st;
        copySettings(st);
        String text = settingsText(st);
        int start = 0;
        while (start < (int)text.length()) {
            int end = text.indexOf('\n', start);
            String part = "CFG " + text.substring(start, end);
            out.line(part.c_str());
            start = end + 1;
        }
        out.line("CFG end");
    } else if (!strcmp(line, "STATUS")) {
        status(out);
    } else if (!strcmp(line, "DEFAULTS")) {
        k8::Settings next;
        next.defaults();
        commit(next, CAR | REBUILD | RELOAD);
        out.line("OK DEFAULTS");
    } else if (!strncmp(line, "CALC ", 5)) {
        calc(line + 5, out);
    } else if (fromSerial && (!strncmp(line, "SML ", 4) || !strncmp(line, "MODE ", 5) || !strncmp(line, "ALERT ", 6))) {
        postLine(line, 2);  // simulated relay line, handled like the car's
        out.line("OK SIM");
    } else if (fromSerial && !strcmp(line, "UNPAIR")) {
        SerialBT.deleteAllBondedDevices();  // rollback: forget the head unit bond
        out.line("OK UNPAIR");
    } else if (!strcmp(line, "REBOOT")) {
        out.line("OK REBOOT");
        delay(200);
        ESP.restart();
    } else {
        snprintf(reply, sizeof(reply), "ERR 알 수 없는 명령: %s", line);
        out.line(reply);
    }
}

// ---------------------------------------------------------------- phone BLE config service
class ServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer*) override { gPhoneConnected = true; }
    void onDisconnect(BLEServer*) override {
        gPhoneConnected = false;
        BLEDevice::startAdvertising();
    }
};

class RxCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* c) override {
        String value = c->getValue();
        // One write may carry several lines.
        int start = 0;
        while (start < (int)value.length()) {
            int end = value.indexOf('\n', start);
            if (end < 0) end = value.length();
            String part = value.substring(start, end);
            start = end + 1;
            if (!part.length()) continue;
            LineMsg msg;
            strncpy(msg.text, part.c_str(), K8_LINE_MAX - 1);
            msg.text[K8_LINE_MAX - 1] = 0;
            msg.source = 1;
            xQueueSend(gCommands, &msg, 0);
        }
    }
};

static void startBle() {
    BLEDevice::init(DEVICE_NAME);
    BLEDevice::setMTU(247);
    BLEServer* server = BLEDevice::createServer();
    server->setCallbacks(new ServerCallbacks());
    BLEService* service = server->createService(CFG_SERVICE);
    BLECharacteristic* rx = service->createCharacteristic(CFG_RX, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
    rx->setCallbacks(new RxCallbacks());
    gTx = service->createCharacteristic(CFG_TX, BLECharacteristic::PROPERTY_NOTIFY | BLECharacteristic::PROPERTY_READ);
    gTx->addDescriptor(new BLE2902());
    service->start();
    BLEAdvertising* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(CFG_SERVICE);
    advertising->setScanResponse(true);
    BLEDevice::startAdvertising();
}

// ---------------------------------------------------------------- setup / loop
void setup() {
    Serial.begin(115200);
    delay(200);
    gLock = xSemaphoreCreateMutex();
    gBleConnect = xSemaphoreCreateMutex();
    gLines = xQueueCreate(32, sizeof(LineMsg));
    gCommands = xQueueCreate(64, sizeof(LineMsg));
    char banner[64];
    snprintf(banner, sizeof(banner), "K8 ambient ESP32 %s", FW_VERSION);
    logLine(banner);
    loadSettings();
    startBle();
    // Classic SPP client (master) to the car relay; BLE stays enabled (disableBLE = false).
    // The head unit auto-rejects Just Works pairing ("Auto-reject JustWorks pairing unconditionally") and asks for
    // pairing on every link, so offer display + yes/no: numeric comparison, confirmed on the head unit screen once.
    // The ESP32 side confirms by itself (no screen); the number is printed on serial for comparison.
    SerialBT.enableSSP(true, true);
    SerialBT.onConfirmRequest([](uint32_t number) { gPairNumber = number; gPairPending = true; });
    SerialBT.onAuthComplete([](boolean ok) { gPairResult = ok ? 1 : 2; });
    if (!SerialBT.begin(DEVICE_NAME, true, false)) logLine("classic BT start failed");
    xTaskCreatePinnedToCore(carTask, "car", 6144, nullptr, 2, nullptr, 0);
    xTaskCreatePinnedToCore(lampTask, "lotus", 6144, (void*)0, 2, nullptr, 1);
    xTaskCreatePinnedToCore(lampTask, "zengge", 6144, (void*)1, 2, nullptr, 1);
    logLine(gSettings.output ? "lamp output ON" : "lamp output OFF (dry run: TX lines only)");
}

static char gSerialLine[K8_LINE_MAX];
static int gSerialLen = 0;

void loop() {
    static SerialOut serialOut;
    static BleOut bleOut;
    if (gPairPending) {
        gPairPending = false;
        char text[64];
        snprintf(text, sizeof(text), "pairing number %06lu (confirm on the head unit)", (unsigned long)gPairNumber);
        logLine(text);
        SerialBT.confirmReply(true);
    }
    if (gPairResult) {
        logLine(gPairResult == 1 ? "pairing done (bonded)" : "pairing failed");
        gPairResult = 0;
    }
    while (Serial.available()) {
        int c = Serial.read();
        if (c == '\n') {
            gSerialLine[gSerialLen] = 0;
            command(gSerialLine, serialOut, true);
            gSerialLen = 0;
        } else if (c != '\r' && gSerialLen < K8_LINE_MAX - 1) {
            gSerialLine[gSerialLen++] = (char)c;
        }
    }
    LineMsg msg;
    while (xQueueReceive(gCommands, &msg, 0) == pdTRUE) {
        Serial.printf("PHONE %s\n", msg.text);
        command(msg.text, bleOut, false);
    }
    while (xQueueReceive(gLines, &msg, 0) == pdTRUE) {
        k8::Settings st;
        copySettings(st);
        k8::State state;
        bool warn;
        k8::RelayLine parsed = k8::parseRelayLine(msg.text);
        if (parsed.kind != k8::RelayLine::PING) Serial.printf("%s %s\n", msg.source == 0 ? "CAR" : "SIM", msg.text);
        if (gCar.onLine(msg.text, st, state, warn)) {
            postTarget(state, warn);
            Serial.printf("TARGET %d,%d,%d,%d,%d%s mode %d\n", state.r, state.g, state.b, state.bri, state.on ? 1 : 0,
                          warn ? " alert" : "", gCar.mode());
        }
    }
    delay(5);
}
