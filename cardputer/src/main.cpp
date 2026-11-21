// Radar Occhiali - firmware per M5Stack Cardputer (v1.1 e ADV)
// Rileva occhiali smart (Ray-Ban/Oakley Meta, Snap Spectacles e altri) dai loro
// annunci Bluetooth Low Energy.
//
// PRIVACY BY DESIGN
//  - Nessun dato viene scritto su flash, SD o NVS: la tabella dei dispositivi vive
//    solo in RAM e ogni voce scade dopo DEVICE_TTL_MS senza segnale.
//  - Wi-Fi mai attivato, nessuna trasmissione verso l'esterno.
//  - Nessuna connessione agli occhiali: solo ascolto degli annunci (scan).
//  - A schermo compaiono solo le ultime 3 cifre dell'indirizzo, per distinguere
//    due dispositivi vicini.
//
// TASTI
//  ; / .      su / giu nella lista
//  ENTER      aggancia il dispositivo selezionato (modalita' caccia)
//  BACKSPACE  sgancia / torna alla lista
//  f          filtro: solo occhiali <-> tutti i BLE
//  s          suono on/off
//  h          aiuto

#include <M5Cardputer.h>
#include <NimBLEDevice.h>
#include <WiFi.h>
#include <math.h>

// ---------------------------------------------------------------- parametri
static const uint32_t DEVICE_TTL_MS   = 30000;  // voce dimenticata dopo 30 s
static const int      MAX_DEVICES     = 48;
static const float    RSSI_ALPHA      = 0.30f;  // filtro esponenziale
static const float    TX_POWER_1M     = -59.0f; // RSSI tipico a 1 m
static const float    PATH_LOSS_N     = 2.2f;   // ambiente interno
static const int      HISTORY_LEN     = 120;    // campioni nel grafico caccia

// ---------------------------------------------------------------- colori
#define C_BG     0x0841
#define C_GRID   0x1B44
#define C_TEXT   0xCE79
#define C_DIM    0x7BEF
#define C_GREEN  0x5EF0
#define C_RED    0xF9E7
#define C_ORANGE 0xFD20
#define C_SEL    0x2965

enum Kind : uint8_t { K_OTHER = 0, K_SUSPECT = 1, K_GLASSES = 2 };

struct Dev {
    bool     used;
    uint8_t  addr[6];
    char     label[22];
    Kind     kind;
    float    rssi;        // filtrato
    int8_t   lastRaw;
    uint32_t lastSeen;
    uint32_t firstSeen;
    bool     alerted;
};

static Dev        devs[MAX_DEVICES];
static SemaphoreHandle_t devMutex;

// stato interfaccia
static bool    onlyGlasses = true;
static bool    soundOn     = true;
static bool    showHelp    = false;
static int     selIdx      = 0;      // indice nella lista visibile
static int     lockedSlot  = -1;     // slot agganciato
static uint8_t lockedAddr[6];
static int8_t  history[HISTORY_LEN];
static int     histCount   = 0;
static int     peakRssi    = -127;
static uint32_t lastBeep   = 0;
static uint32_t alertUntil = 0;

static M5Canvas canvas(&M5Cardputer.Display);

// ---------------------------------------------------------------- classificazione
static bool payloadHas(const std::vector<uint8_t>& p, const char* s) {
    size_t n = strlen(s);
    if (p.size() < n) return false;
    for (size_t i = 0; i + n <= p.size(); i++)
        if (memcmp(&p[i], s, n) == 0) return true;
    return false;
}

static bool nameHas(const std::string& name, const char* s) {
    if (name.empty()) return false;
    std::string a = name, b = s;
    for (auto& c : a) c = tolower(c);
    for (auto& c : b) c = tolower(c);
    return a.find(b) != std::string::npos;
}

static Kind classify(const NimBLEAdvertisedDevice* d, char* label, size_t labelLen) {
    std::string name = d->haveName() ? d->getName() : std::string();
    uint16_t cid = 0xFFFF;
    if (d->getManufacturerDataCount() > 0) {
        std::string md = d->getManufacturerData();
        if (md.size() >= 2) cid = (uint8_t)md[0] | ((uint8_t)md[1] << 8);
    }
    const std::vector<uint8_t>& p = d->getPayload();

    // nomi noti di occhiali smart
    static const char* glassNames[] = {"ray-ban", "rayban", "oakley meta", "spectacles",
                                       "even g", "xreal", "nreal", "rokid", "rayneo",
                                       "vuzix", "solos", "echo frames", "meta glasses"};
    for (auto g : glassNames)
        if (nameHas(name, g)) {
            snprintf(label, labelLen, "%s", name.c_str());
            return K_GLASSES;
        }

    switch (cid) {
        case 0x0D53:  // Luxottica (Ray-Ban / Oakley Meta)
            snprintf(label, labelLen, "Luxottica/Meta");
            return K_GLASSES;
        case 0x03C2:  // Snap Inc.
            snprintf(label, labelLen, "Snap Spectacles");
            return K_GLASSES;
        case 0x01AB:  // Meta Platforms
        case 0x058E:  // Meta Platforms Technologies
            if (payloadHas(p, "META_RB_GLASS") || payloadHas(p, "RB_GLASS")) {
                snprintf(label, labelLen, "Ray-Ban Meta");
                return K_GLASSES;
            }
            snprintf(label, labelLen, "Meta (verifica)");
            return K_SUSPECT;  // puo' essere un visore Quest
        default:
            break;
    }
    if (!name.empty()) snprintf(label, labelLen, "%s", name.c_str());
    else if (cid != 0xFFFF) snprintf(label, labelLen, "BLE id 0x%04X", cid);
    else snprintf(label, labelLen, "dispositivo BLE");
    return K_OTHER;
}

// ---------------------------------------------------------------- scansione
class ScanCB : public NimBLEScanCallbacks {
    void onResult(const NimBLEAdvertisedDevice* d) override {
        const uint8_t* a = d->getAddress().getBase()->val;
        int8_t rssi = d->getRSSI();
        uint32_t now = millis();

        char label[22];
        Kind k = classify(d, label, sizeof(label));

        xSemaphoreTake(devMutex, portMAX_DELAY);
        int slot = -1, freeSlot = -1, oldest = -1;
        uint32_t oldestT = UINT32_MAX;
        for (int i = 0; i < MAX_DEVICES; i++) {
            if (devs[i].used && memcmp(devs[i].addr, a, 6) == 0) { slot = i; break; }
            if (!devs[i].used && freeSlot < 0) freeSlot = i;
            if (devs[i].used && devs[i].kind == K_OTHER && devs[i].lastSeen < oldestT &&
                i != lockedSlot) { oldestT = devs[i].lastSeen; oldest = i; }
        }
        if (slot < 0) {
            slot = freeSlot >= 0 ? freeSlot : oldest;
            if (slot >= 0) {
                memset(&devs[slot], 0, sizeof(Dev));
                devs[slot].used = true;
                memcpy(devs[slot].addr, a, 6);
                devs[slot].rssi = rssi;
                devs[slot].firstSeen = now;
            }
        }
        if (slot >= 0) {
            Dev& v = devs[slot];
            // una classificazione "forte" non viene mai declassata
            if (k >= v.kind) { v.kind = k; strncpy(v.label, label, sizeof(v.label) - 1); }
            v.rssi = RSSI_ALPHA * rssi + (1.0f - RSSI_ALPHA) * v.rssi;
            v.lastRaw = rssi;
            v.lastSeen = now;
        }
        xSemaphoreGive(devMutex);
    }
    void onScanEnd(const NimBLEScanResults&, int) override {
        NimBLEDevice::getScan()->start(0, false, true);
    }
};
static ScanCB scanCB;

// ---------------------------------------------------------------- utilita'
static float distM(float rssi) {
    return powf(10.0f, (TX_POWER_1M - rssi) / (10.0f * PATH_LOSS_N));
}
static int proximityPct(float rssi) {  // -95 dBm -> 0 %, -40 dBm -> 100 %
    int p = (int)((rssi + 95.0f) * 100.0f / 55.0f);
    return p < 0 ? 0 : (p > 100 ? 100 : p);
}
static uint16_t kindColor(Kind k) {
    return k == K_GLASSES ? C_RED : (k == K_SUSPECT ? C_ORANGE : C_DIM);
}
static void fmtDist(float d, char* out, size_t n) {
    if (d < 10) snprintf(out, n, "~%.1fm", d);
    else snprintf(out, n, "~%.0fm", d);
}

// copia ordinata (per RSSI) dei dispositivi visibili
struct View { int slot; Dev d; };
static int buildView(View* out, int maxN, int* glassesCount) {
    int n = 0, g = 0;
    uint32_t now = millis();
    xSemaphoreTake(devMutex, portMAX_DELAY);
    for (int i = 0; i < MAX_DEVICES; i++) {
        if (!devs[i].used) continue;
        if (now - devs[i].lastSeen > DEVICE_TTL_MS && i != lockedSlot) {
            memset(&devs[i], 0, sizeof(Dev));  // dimentica
            continue;
        }
        if (devs[i].kind == K_GLASSES) g++;
        if (onlyGlasses && devs[i].kind == K_OTHER) continue;
        if (n < maxN) { out[n].slot = i; out[n].d = devs[i]; n++; }
    }
    xSemaphoreGive(devMutex);
    // ordina: occhiali prima, poi per segnale
    for (int i = 1; i < n; i++) {
        View t = out[i]; int j = i - 1;
        while (j >= 0 && (out[j].d.kind < t.d.kind ||
               (out[j].d.kind == t.d.kind && out[j].d.rssi < t.d.rssi))) {
            out[j + 1] = out[j]; j--;
        }
        out[j + 1] = t;
    }
    if (glassesCount) *glassesCount = g;
    return n;
}

// ---------------------------------------------------------------- disegno
static void drawHeader(int glasses) {
    canvas.fillRect(0, 0, 240, 14, glasses ? 0x6000 : C_GRID);
    canvas.setTextColor(glasses ? TFT_WHITE : C_GREEN);
    canvas.setTextSize(1);
    canvas.setCursor(3, 3);
    if (glasses) canvas.printf("RADAR OCCHIALI  %d IN VISTA", glasses);
    else canvas.print("RADAR OCCHIALI  nessuno");
    int bat = M5Cardputer.Power.getBatteryLevel();
    canvas.setCursor(196, 3);
    canvas.setTextColor(C_TEXT);
    if (bat >= 0) canvas.printf("%3d%%", bat);
    canvas.setCursor(222, 3);
    canvas.setTextColor(soundOn ? C_GREEN : C_DIM);
    canvas.print(soundOn ? "S" : "-");
}

static void drawList() {
    static View view[MAX_DEVICES];
    int g = 0;
    int n = buildView(view, MAX_DEVICES, &g);
    canvas.fillSprite(C_BG);
    drawHeader(g);

    if (selIdx >= n) selIdx = n > 0 ? n - 1 : 0;
    const int rowH = 13, top = 17, rows = 8;
    int first = selIdx >= rows ? selIdx - rows + 1 : 0;

    if (n == 0) {
        canvas.setTextColor(C_DIM);
        canvas.setCursor(8, 50);
        canvas.print(onlyGlasses ? "Nessun occhiale smart rilevato." : "Nessun dispositivo BLE.");
        canvas.setCursor(8, 66);
        canvas.print("Scansione in corso...");
    }
    for (int r = 0; r < rows && first + r < n; r++) {
        const Dev& d = view[first + r].d;
        int y = top + r * rowH;
        if (first + r == selIdx) canvas.fillRect(0, y - 1, 240, rowH, C_SEL);
        canvas.fillRect(2, y + 1, 8, 8, kindColor(d.kind));
        canvas.setTextColor(d.kind == K_OTHER ? C_TEXT : kindColor(d.kind));
        canvas.setCursor(13, y + 2);
        char lab[16]; snprintf(lab, sizeof(lab), "%.15s", d.label);
        canvas.print(lab);
        char ds[10]; fmtDist(distM(d.rssi), ds, sizeof(ds));
        canvas.setTextColor(C_TEXT);
        canvas.setCursor(108, y + 2);
        canvas.printf("%4d %-6s", (int)d.rssi, ds);
        canvas.setTextColor(C_DIM);
        canvas.setCursor(186, y + 2);
        canvas.printf("%02X:%02X:%02X", d.addr[2], d.addr[1], d.addr[0]);  // ultime 3 cifre
    }
    canvas.fillRect(0, 122, 240, 13, C_GRID);
    canvas.setTextColor(C_DIM);
    canvas.setCursor(3, 125);
    canvas.printf("ENTER aggancia  f:%s  s:suono  h:aiuto", onlyGlasses ? "occhiali" : "tutti");

    if (millis() < alertUntil && (millis() / 250) % 2 == 0)
        canvas.drawRect(0, 0, 240, 135, C_RED), canvas.drawRect(1, 1, 238, 133, C_RED);
}

static void drawHunt() {
    Dev d; bool ok = false;
    xSemaphoreTake(devMutex, portMAX_DELAY);
    if (lockedSlot >= 0 && devs[lockedSlot].used && memcmp(devs[lockedSlot].addr, lockedAddr, 6) == 0) {
        d = devs[lockedSlot]; ok = true;
    }
    xSemaphoreGive(devMutex);

    canvas.fillSprite(C_BG);
    if (!ok) {
        canvas.setTextColor(C_ORANGE);
        canvas.setCursor(8, 50);
        canvas.print("Segnale perso. BACKSPACE per tornare.");
        return;
    }
    bool stale = millis() - d.lastSeen > 3000;
    uint16_t col = stale ? C_DIM : (d.kind == K_GLASSES ? C_RED : (d.kind == K_SUSPECT ? C_ORANGE : C_GREEN));

    canvas.fillRect(0, 0, 240, 14, C_GRID);
    canvas.setTextColor(C_GREEN);
    canvas.setCursor(3, 3);
    canvas.printf("CACCIA  %.20s", d.label);

    int pct = proximityPct(d.rssi);
    char ds[10]; fmtDist(distM(d.rssi), ds, sizeof(ds));
    canvas.setTextColor(col);
    canvas.setTextSize(3);
    canvas.setCursor(6, 20);
    canvas.printf("%3d%%", pct);
    canvas.setTextSize(2);
    canvas.setCursor(118, 22);
    canvas.print(ds);
    canvas.setTextSize(1);
    canvas.setTextColor(C_TEXT);
    canvas.setCursor(118, 40);
    canvas.printf("%d dBm  picco %d", (int)d.rssi, peakRssi);

    // barra di prossimita'
    canvas.drawRect(6, 50, 228, 8, C_GRID);
    canvas.fillRect(7, 51, 226 * pct / 100, 6, col);

    // grafico storico RSSI (-100..-30 dBm)
    const int gx = 6, gy = 62, gw = 228, gh = 48;
    canvas.drawRect(gx, gy, gw, gh, C_GRID);
    auto yOf = [&](int r) { r = r < -100 ? -100 : (r > -30 ? -30 : r); return gy + gh - 1 - (r + 100) * (gh - 2) / 70; };
    int py = yOf(peakRssi);
    for (int x = gx; x < gx + gw; x += 4) canvas.drawPixel(x, py, C_ORANGE);
    for (int i = 1; i < histCount; i++) {
        int x0 = gx + 1 + (i - 1) * (gw - 2) / (HISTORY_LEN - 1);
        int x1 = gx + 1 + i * (gw - 2) / (HISTORY_LEN - 1);
        canvas.drawLine(x0, yOf(history[i - 1]), x1, yOf(history[i]), col);
    }
    canvas.setTextColor(C_DIM);
    canvas.setCursor(3, 114);
    canvas.print("Gira lentamente su te stesso: dove il");
    canvas.setCursor(3, 124);
    canvas.print("segnale tocca il picco, li' ci sono.  BKSP");
}

static void drawHelp() {
    canvas.fillSprite(C_BG);
    canvas.setTextColor(C_GREEN);
    canvas.setCursor(4, 4);
    canvas.print("RADAR OCCHIALI - aiuto");
    canvas.setTextColor(C_TEXT);
    const char* lines[] = {
        "; .     su / giu",
        "ENTER   aggancia (modalita' caccia)",
        "BKSP    sgancia / indietro",
        "f       solo occhiali / tutti i BLE",
        "s       suono on/off",
        "Rosso = occhiali  Arancio = verifica",
        "Nulla viene salvato. Wi-Fi spento.",
        "Distanza stimata, conferma a vista.",
    };
    for (int i = 0; i < 8; i++) { canvas.setCursor(4, 20 + i * 13); canvas.print(lines[i]); }
}

// ---------------------------------------------------------------- audio
static void soundTick() {
    uint32_t now = millis();
    if (!soundOn) return;
    if (lockedSlot >= 0) {
        float r = -127;
        xSemaphoreTake(devMutex, portMAX_DELAY);
        if (devs[lockedSlot].used && now - devs[lockedSlot].lastSeen < 3000) r = devs[lockedSlot].rssi;
        xSemaphoreGive(devMutex);
        if (r < -110) return;
        int pct = proximityPct(r);
        uint32_t period = 1200 - pct * 11;  // geiger: piu' vicino, piu' veloce
        if (now - lastBeep > period) {
            M5Cardputer.Speaker.tone(600 + pct * 12, 40);
            lastBeep = now;
        }
    }
}

static void checkNewGlasses() {
    bool fire = false;
    xSemaphoreTake(devMutex, portMAX_DELAY);
    for (int i = 0; i < MAX_DEVICES; i++) {
        if (devs[i].used && devs[i].kind == K_GLASSES && !devs[i].alerted) {
            devs[i].alerted = true; fire = true;
        }
    }
    xSemaphoreGive(devMutex);
    if (fire) {
        alertUntil = millis() + 3000;
        if (soundOn && lockedSlot < 0) {
            M5Cardputer.Speaker.tone(1800, 120);
            delay(150);
            M5Cardputer.Speaker.tone(1800, 120);
        }
    }
}

// ---------------------------------------------------------------- input
static void handleKeys() {
    if (!(M5Cardputer.Keyboard.isChange() && M5Cardputer.Keyboard.isPressed())) return;
    auto& st = M5Cardputer.Keyboard.keysState();

    if (showHelp) { showHelp = false; return; }

    if (st.del || st.backspace || st.esc) {
        lockedSlot = -1;
        return;
    }
    if (st.enter && lockedSlot < 0) {
        static View view[MAX_DEVICES];
        int n = buildView(view, MAX_DEVICES, nullptr);
        if (selIdx < n) {
            lockedSlot = view[selIdx].slot;
            memcpy(lockedAddr, view[selIdx].d.addr, 6);
            histCount = 0;
            peakRssi = -127;
        }
        return;
    }
    for (char c : st.word) {
        switch (c) {
            case ';': if (selIdx > 0) selIdx--; break;
            case '.': selIdx++; break;
            case 'f': case 'F': onlyGlasses = !onlyGlasses; selIdx = 0; break;
            case 's': case 'S': soundOn = !soundOn; break;
            case 'h': case 'H': showHelp = true; break;
            case 'r': case 'R': peakRssi = -127; histCount = 0; break;  // azzera picco
        }
    }
}

// ---------------------------------------------------------------- setup / loop
void setup() {
    auto cfg = M5.config();
    M5Cardputer.begin(cfg, true);
    M5Cardputer.Display.setRotation(1);
    M5Cardputer.Speaker.setVolume(160);

    WiFi.mode(WIFI_OFF);  // nessuna trasmissione verso l'esterno

    canvas.setColorDepth(16);
    canvas.createSprite(240, 135);
    canvas.setTextFont(1);

    devMutex = xSemaphoreCreateMutex();
    memset(devs, 0, sizeof(devs));

    NimBLEDevice::init("");
    NimBLEScan* scan = NimBLEDevice::getScan();
    scan->setScanCallbacks(&scanCB, true);  // vogliamo ogni annuncio (RSSI aggiornato)
    scan->setActiveScan(true);              // chiede la risposta di scansione per il nome
    scan->setInterval(100);
    scan->setWindow(99);
    scan->setDuplicateFilter(false);
    scan->setMaxResults(0);                 // nessun risultato conservato dalla libreria
    scan->start(0, false, true);
}

void loop() {
    M5Cardputer.update();
    handleKeys();
    checkNewGlasses();

    static uint32_t lastSample = 0;
    if (lockedSlot >= 0 && millis() - lastSample > 250) {
        lastSample = millis();
        int r = -127;
        xSemaphoreTake(devMutex, portMAX_DELAY);
        if (devs[lockedSlot].used && millis() - devs[lockedSlot].lastSeen < 3000) r = (int)devs[lockedSlot].rssi;
        xSemaphoreGive(devMutex);
        if (r > -127) {
            if (histCount < HISTORY_LEN) history[histCount++] = r;
            else { memmove(history, history + 1, HISTORY_LEN - 1); history[HISTORY_LEN - 1] = r; }
            if (r > peakRssi) peakRssi = r;
        }
    }
    soundTick();

    if (showHelp) drawHelp();
    else if (lockedSlot >= 0) drawHunt();
    else drawList();
    canvas.pushSprite(0, 0);
    delay(30);
}
