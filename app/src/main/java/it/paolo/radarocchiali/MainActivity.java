package it.paolo.radarocchiali;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final long VISIBILE_MS = 8000, AGGANCIO_MS = 20000, DIMENTICA_MS = 60000;

    private final Map<String, Dispositivo> mappa = new HashMap<>();
    private final List<Dispositivo> righe = new ArrayList<>();
    private final Handler h = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private boolean scansiona;
    private String agganciato;
    private boolean suono = true, vibra = true, tutti = false;
    private long prossimoBip;

    private RadarView radar;
    private TextView titolo, grande, piccolo, avviso;
    private Button bSuono, bVibra, bTutti, bSgancia, bDirezione;

    // modalità direzione (giro di scansione con la bussola)
    private final Direzione direzione = new Direzione();
    private boolean modoDirezione;
    private String macDirezione;
    private float azimut = Float.NaN;
    private SensorManager sensori;
    private final float[] matrice = new float[9];
    private final SensorEventListener bussola = new SensorEventListener() {
        public void onSensorChanged(SensorEvent e) {
            SensorManager.getRotationMatrixFromVector(matrice, e.values);
            // asse Y del telefono (in piano) o retro dello schermo (telefono in piedi):
            // si usa quello più orizzontale. Mondo: X = est, Y = nord, Z = alto.
            float yx = matrice[1], yy = matrice[4], yz = matrice[7];
            float zx = -matrice[2], zy = -matrice[5], zz = -matrice[8];
            double a = Math.abs(yz) < Math.abs(zz) ? Math.atan2(yx, yy) : Math.atan2(zx, zy);
            azimut = (float) ((Math.toDegrees(a) + 360.0) % 360.0);
        }
        public void onAccuracyChanged(Sensor s, int acc) { }
    };
    private Adattatore adattatore;
    private ToneGenerator tono;
    private Vibrator vibratore;
    private float dp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dp = getResources().getDisplayMetrics().density;
        costruisciInterfaccia();
        try { tono = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); } catch (RuntimeException e) { tono = null; }
        vibratore = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        sensori = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    }

    // ------------------------------------------------------------------ interfaccia
    private void costruisciInterfaccia() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.rgb(8, 14, 12));
        int pad = (int) (12 * dp);
        col.setPadding(pad, pad, pad, pad);

        titolo = testo(15, Color.rgb(120, 220, 170), true);
        titolo.setText("RADAR OCCHIALI · avvio…");
        col.addView(titolo);

        radar = new RadarView(this);
        LinearLayout.LayoutParams lpR = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpR.topMargin = (int) (6 * dp);
        col.addView(radar, lpR);

        grande = testo(30, Color.WHITE, true);
        grande.setGravity(Gravity.CENTER);
        col.addView(grande);
        piccolo = testo(14, Color.rgb(190, 200, 195), false);
        piccolo.setGravity(Gravity.CENTER);
        col.addView(piccolo);

        LinearLayout tasti = new LinearLayout(this);
        tasti.setOrientation(LinearLayout.HORIZONTAL);
        bSuono = tasto("Suono");
        bVibra = tasto("Vibra");
        bTutti = tasto("Tutti BLE");
        bSgancia = tasto("Sgancia");
        tasti.addView(bSuono, pesato());
        tasti.addView(bVibra, pesato());
        tasti.addView(bTutti, pesato());
        tasti.addView(bSgancia, pesato());
        col.addView(tasti);
        bDirezione = tasto("Trova direzione");
        col.addView(bDirezione, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bDirezione.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { commutaDirezione(); } });
        bSuono.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { suono = !suono; aggiornaTasti(); } });
        bVibra.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { vibra = !vibra; aggiornaTasti(); } });
        bTutti.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { tutti = !tutti; aggiornaTasti(); } });
        bSgancia.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { agganciato = null; fermaDirezione(); aggiornaTasti(); } });

        avviso = testo(12, Color.rgb(140, 150, 145), false);
        avviso.setText("Tocca un dispositivo per agganciarlo. Per la direzione premi \"Trova direzione\", "
                + "tieni il telefono al petto e gira lentamente su te stesso: la freccia indica dove il segnale è più forte. "
                + "Arancione = da verificare: marchio che fa anche cuffie o visori, suona solo se lo agganci.");
        col.addView(avviso);

        ListView lista = new ListView(this);
        adattatore = new Adattatore();
        lista.setAdapter(adattatore);
        lista.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                if (pos < righe.size()) { agganciato = righe.get(pos).mac; aggiornaTasti(); }
            }
        });
        col.addView(lista, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        final int padBase = pad;
        col.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets s = in.getInsets(android.view.WindowInsets.Type.systemBars());
                    top = s.top; bottom = s.bottom;
                } else {
                    top = in.getSystemWindowInsetTop(); bottom = in.getSystemWindowInsetBottom();
                }
                v.setPadding(padBase, padBase + top, padBase, padBase + bottom);
                return in;
            }
        });
        setContentView(col);
        aggiornaTasti();
    }

    private TextView testo(int sp, int colore, boolean grassetto) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(colore);
        if (grassetto) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button tasto(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(13);
        return b;
    }

    private LinearLayout.LayoutParams pesato() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void aggiornaTasti() {
        bSuono.setText(suono ? "Suono ON" : "Suono OFF");
        bVibra.setText(vibra ? "Vibra ON" : "Vibra OFF");
        bTutti.setText(tutti ? "Tutti BLE" : "Solo occhiali");
        bSgancia.setEnabled(agganciato != null);
        bDirezione.setText(modoDirezione ? "Ferma direzione" : "Trova direzione");
    }

    // ------------------------------------------------------------------ direzione
    private String bersaglioCorrente;   // mac del dispositivo mostrato in grande

    private void commutaDirezione() {
        if (modoDirezione) { fermaDirezione(); aggiornaTasti(); return; }
        String mac = agganciato != null ? agganciato : bersaglioCorrente;
        if (mac == null) { piccolo.setText("Prima tocca un dispositivo nella lista"); return; }
        if (sensori == null || sensori.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null) {
            piccolo.setText("Questo telefono non ha la bussola");
            return;
        }
        agganciato = mac;               // la direzione si cerca sempre su un dispositivo agganciato
        macDirezione = mac;
        synchronized (direzione) { direzione.azzera(); }
        modoDirezione = true;
        aggiornaTasti();
    }

    private void fermaDirezione() {
        modoDirezione = false;
        macDirezione = null;
        synchronized (direzione) { direzione.azzera(); }
    }

    // ------------------------------------------------------------------ ciclo di vita
    @Override protected void onResume() {
        super.onResume();
        avvia();
        h.post(ciclo);
        if (sensori != null) {
            Sensor rv = sensori.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rv != null) sensori.registerListener(bussola, rv, SensorManager.SENSOR_DELAY_UI);
        }
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(ciclo);
        ferma();
        if (sensori != null) sensori.unregisterListener(bussola);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (tono != null) tono.release();
    }

    private void avvia() {
        // Android 12+: permesso "Dispositivi nelle vicinanze" (neverForLocation, niente GPS)
        // Android 11 e precedenti: permesso Posizione, richiesto dal sistema per il Bluetooth
        String perm = Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION;
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            titolo.setText(Build.VERSION.SDK_INT >= 31 ? "Serve il permesso \"Dispositivi nelle vicinanze\""
                    : "Serve il permesso Posizione (Android lo chiede per il Bluetooth)");
            requestPermissions(new String[] { perm }, 1);
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) { titolo.setText("Bluetooth non disponibile"); return; }
        if (!ad.isEnabled()) {
            titolo.setText("Accendi il Bluetooth");
            // da Android 12 la richiesta diretta vuole un permesso in più: apriamo le impostazioni
            startActivity(new Intent(Build.VERSION.SDK_INT >= 31 ? Settings.ACTION_BLUETOOTH_SETTINGS : BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 28 && Build.VERSION.SDK_INT < 31 && lm != null && !lm.isLocationEnabled()) {
            titolo.setText("Accendi la Posizione (GPS), senza non arrivano dati Bluetooth");
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        if (scansiona) return;
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { titolo.setText("Scanner BLE non pronto, riprova"); return; }
        ScanSettings s = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();
        try {
            scanner.startScan(null, s, ascolto);
            scansiona = true;
        } catch (SecurityException e) {
            titolo.setText("Permesso negato: " + e.getMessage());
        }
    }

    private void ferma() {
        if (scansiona && scanner != null) {
            try { scanner.stopScan(ascolto); } catch (RuntimeException ignored) { }
        }
        scansiona = false;
    }

    @Override public void onRequestPermissionsResult(int req, String[] perm, int[] res) {
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) avvia();
        else titolo.setText(Build.VERSION.SDK_INT >= 31 ? "Senza \"Dispositivi nelle vicinanze\" l'app non vede il Bluetooth"
                : "Senza permesso Posizione Android non mostra i dispositivi Bluetooth");
    }

    // ------------------------------------------------------------------ scansione
    private final ScanCallback ascolto = new ScanCallback() {
        @Override public void onScanResult(int tipo, ScanResult r) { ricevi(r); }
        @Override public void onBatchScanResults(List<ScanResult> l) { for (ScanResult r : l) ricevi(r); }
        @Override public void onScanFailed(int codice) {
            scansiona = false;
            titolo.setText("Scansione fallita (codice " + codice + "): chiudi e riapri l'app");
        }
    };

    private static final ParcelUuid FEA8 = ParcelUuid.fromString("0000fea8-0000-1000-8000-00805f9b34fb");

    private void ricevi(ScanResult r) {
        if (r == null || r.getDevice() == null) return;
        String mac = r.getDevice().getAddress();
        long ora = SystemClock.elapsedRealtime();
        ScanRecord rec = r.getScanRecord();
        int[] ids = null;
        String[] uuids = null;
        String nome = null;
        byte[] fea8 = null;
        byte[] grezzo = null;
        if (rec != null) {
            grezzo = rec.getBytes();
            SparseArray<byte[]> md = rec.getManufacturerSpecificData();
            if (md != null) {
                ids = new int[md.size()];
                for (int i = 0; i < md.size(); i++) ids[i] = md.keyAt(i);
            }
            List<String> u = new ArrayList<>();
            if (rec.getServiceUuids() != null) for (ParcelUuid p : rec.getServiceUuids()) u.add(p.toString());
            if (rec.getServiceData() != null) for (ParcelUuid p : rec.getServiceData().keySet()) u.add(p.toString());
            uuids = u.toArray(new String[0]);
            nome = rec.getDeviceName();
            fea8 = rec.getServiceData(FEA8);
        }
        String motivo = Rilevatore.riconosci(ids, uuids, nome, fea8, grezzo);
        if (modoDirezione && mac.equals(macDirezione) && !Float.isNaN(azimut))
            synchronized (direzione) { direzione.aggiungi(azimut, r.getRssi()); }
        synchronized (mappa) {
            Dispositivo d = mappa.get(mac);
            if (d == null) { d = new Dispositivo(mac, r.getRssi(), ora); mappa.put(mac, d); }
            else d.aggiorna(r.getRssi(), ora);
            d.segna(motivo);
            if (nome != null) d.nome = nome;
        }
    }

    // ------------------------------------------------------------------ ciclo grafico + bip
    private final Runnable ciclo = new Runnable() {
        public void run() {
            aggiorna();
            h.postDelayed(this, 40);
        }
    };

    private long ultimaLista;

    private void aggiorna() {
        long ora = SystemClock.elapsedRealtime();
        List<Dispositivo> visibili = new ArrayList<>();
        Dispositivo bers = null, agg = null;
        int occhiali = 0, probabili = 0;
        synchronized (mappa) {
            List<String> via = new ArrayList<>();
            for (Dispositivo d : mappa.values()) {
                long eta = ora - d.visto;
                if (eta > DIMENTICA_MS && !d.mac.equals(agganciato)) { via.add(d.mac); continue; }
                if (d.mac.equals(agganciato) && eta < AGGANCIO_MS) agg = d;
                if (eta > VISIBILE_MS) continue;
                if (d.occhiale()) occhiali++;
                if (d.probabile()) probabili++;
                if (d.occhiale() || d.probabile() || tutti) visibili.add(d);
            }
            for (String m : via) mappa.remove(m);
        }
        if (agganciato != null && agg == null) { agganciato = null; fermaDirezione(); aggiornaTasti(); }  // sparito o MAC cambiato
        Collections.sort(visibili, new Comparator<Dispositivo>() {
            public int compare(Dispositivo a, Dispositivo b) {
                if (a.occhiale() != b.occhiale()) return a.occhiale() ? -1 : 1;
                if (a.probabile() != b.probabile()) return a.probabile() ? -1 : 1;
                return Double.compare(b.rssi, a.rssi);
            }
        });
        if (agg != null) bers = agg;
        else for (Dispositivo d : visibili) if (d.occhiale()) { bers = d; break; }

        titolo.setText(occhiali == 0 ? "RADAR OCCHIALI · nessun occhiale in vista"
                : "RADAR OCCHIALI · " + occhiali + (occhiali == 1 ? " occhiale" : " occhiali") + " in vista"
                + (agganciato != null ? " · AGGANCIATO" : ""));
        if (probabili > 0) titolo.append(" · " + probabili + " da verificare");
        bersaglioCorrente = bers != null ? bers.mac : null;
        boolean pronta;
        float copertura;
        double contrasto;
        synchronized (direzione) {
            pronta = direzione.pronta();
            copertura = direzione.copertura();
            contrasto = direzione.contrasto();
            radar.direzione(modoDirezione, modoDirezione ? direzione : null, azimut, pronta);
        }
        radar.imposta(visibili, bers, agganciato != null);

        if (bers == null) {
            grande.setText("—");
            grande.setTextColor(Color.rgb(120, 130, 125));
            piccolo.setText(scansiona ? "Cammino tra i banchi e ascolto…" : "Scansione ferma");
        } else {
            double vic = Rilevatore.vicinanza(bers.rssi);
            grande.setText(String.format(Locale.ITALY, "%d%%  ·  ~%s", Math.round(vic * 100), metri(bers.rssi)));
            grande.setTextColor(RadarView.colore(vic));
            if (modoDirezione && !pronta)
                piccolo.setText(String.format(Locale.ITALY, "Telefono al petto, gira lentamente: %d%% del giro · contrasto %.0f dB",
                        Math.round(copertura * 100), contrasto));
            else if (modoDirezione)
                piccolo.setText(String.format(Locale.ITALY, "Segui la freccia rossa · contrasto %.0f dB · %d dBm", contrasto, bers.rssiGrezzo));
            else
                piccolo.setText(String.format(Locale.ITALY, "%s · %d dBm · %s", bers.motivo != null ? bers.motivo.replace("? ", "probabile: ") : "BLE",
                    bers.rssiGrezzo, coda(bers.mac)));
            if (ora >= prossimoBip && (bers.occhiale() || agg != null)) {
                int periodo = Rilevatore.periodoBip(bers.rssi);
                bip(vic > 0.95 ? 120 : 35);
                prossimoBip = ora + periodo;
            }
        }

        if (ora - ultimaLista > 400) {
            ultimaLista = ora;
            righe.clear();
            righe.addAll(visibili);
            adattatore.notifyDataSetChanged();
        }
    }

    private void bip(int ms) {
        if (suono && tono != null) tono.startTone(ToneGenerator.TONE_PROP_BEEP, ms);
        if (vibra && vibratore != null && vibratore.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26) vibratore.vibrate(VibrationEffect.createOneShot(Math.min(ms, 60), VibrationEffect.DEFAULT_AMPLITUDE));
            else vibratore.vibrate(Math.min(ms, 60));
        }
    }

    static String metri(double rssi) {
        double m = Rilevatore.metri(rssi);
        if (m < 1) return String.format(Locale.ITALY, "%.1f m", m);
        if (m < 10) return String.format(Locale.ITALY, "%.0f m", m);
        return ">10 m";
    }

    static String coda(String mac) {
        return mac == null || mac.length() < 8 ? String.valueOf(mac) : "…" + mac.substring(mac.length() - 8);
    }

    // ------------------------------------------------------------------ lista
    private class Adattatore extends BaseAdapter {
        public int getCount() { return righe.size(); }
        public Object getItem(int i) { return righe.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View v, ViewGroup g) {
            TextView t = v instanceof TextView ? (TextView) v : testo(14, Color.WHITE, false);
            int pad = (int) (8 * dp);
            t.setPadding(pad, pad, pad, pad);
            Dispositivo d = righe.get(i);
            boolean agg = d.mac.equals(agganciato);
            int barre = (int) Math.round(Rilevatore.vicinanza(d.rssi) * 12);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 12; k++) sb.append(k < barre ? '█' : '·');
            t.setText((agg ? "▶ " : "") + (d.occhiale() ? "👓 " + d.motivo : d.probabile() ? "❓ probabile: " + d.motivo.substring(2) : (d.nome != null ? d.nome : "dispositivo BLE"))
                    + "\n" + sb + "  " + Math.round(d.rssi) + " dBm  ~" + metri(d.rssi) + "  " + coda(d.mac));
            t.setTextColor(d.occhiale() ? RadarView.colore(Rilevatore.vicinanza(d.rssi))
                    : d.probabile() ? Color.rgb(255, 170, 60) : Color.rgb(150, 155, 150));
            t.setBackgroundColor(agg ? Color.rgb(30, 50, 40) : Color.TRANSPARENT);
            return t;
        }
    }
}
