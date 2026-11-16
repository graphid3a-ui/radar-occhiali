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
    private Button bSuono, bVibra, bTutti, bSgancia;
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
        bSuono.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { suono = !suono; aggiornaTasti(); } });
        bVibra.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { vibra = !vibra; aggiornaTasti(); } });
        bTutti.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { tutti = !tutti; aggiornaTasti(); } });
        bSgancia.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { agganciato = null; aggiornaTasti(); } });

        avviso = testo(12, Color.rgb(140, 150, 145), false);
        avviso.setText("Tocca un dispositivo per agganciarlo. La distanza è stimata, la direzione no: "
                + "girati lentamente, quando il segnale cala gli occhiali sono alle tue spalle.");
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
    }

    // ------------------------------------------------------------------ ciclo di vita
    @Override protected void onResume() {
        super.onResume();
        avvia();
        h.post(ciclo);
    }

    @Override protected void onPause() {
        super.onPause();
        h.removeCallbacks(ciclo);
        ferma();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (tono != null) tono.release();
    }

    private void avvia() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            titolo.setText("Serve il permesso Posizione (Android lo chiede per il Bluetooth)");
            requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 1);
            return;
        }
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null) { titolo.setText("Bluetooth non disponibile"); return; }
        if (!ad.isEnabled()) {
            titolo.setText("Accendi il Bluetooth");
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 28 && lm != null && !lm.isLocationEnabled()) {
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
        else titolo.setText("Senza permesso Posizione Android non mostra i dispositivi Bluetooth");
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

    private void ricevi(ScanResult r) {
        if (r == null || r.getDevice() == null) return;
        String mac = r.getDevice().getAddress();
        long ora = SystemClock.elapsedRealtime();
        ScanRecord rec = r.getScanRecord();
        int[] ids = null;
        String[] uuids = null;
        String nome = null;
        if (rec != null) {
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
        }
        String motivo = Rilevatore.riconosci(ids, uuids, nome);
        synchronized (mappa) {
            Dispositivo d = mappa.get(mac);
            if (d == null) { d = new Dispositivo(mac, r.getRssi(), ora); mappa.put(mac, d); }
            else d.aggiorna(r.getRssi(), ora);
            if (motivo != null) d.motivo = motivo;   // una volta riconosciuto resta riconosciuto
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
        int occhiali = 0;
        synchronized (mappa) {
            List<String> via = new ArrayList<>();
            for (Dispositivo d : mappa.values()) {
                long eta = ora - d.visto;
                if (eta > DIMENTICA_MS && !d.mac.equals(agganciato)) { via.add(d.mac); continue; }
                if (d.mac.equals(agganciato) && eta < AGGANCIO_MS) agg = d;
                if (eta > VISIBILE_MS) continue;
                if (d.occhiale()) occhiali++;
                if (d.occhiale() || tutti) visibili.add(d);
            }
            for (String m : via) mappa.remove(m);
        }
        if (agganciato != null && agg == null) agganciato = null;  // sparito o MAC cambiato
        Collections.sort(visibili, new Comparator<Dispositivo>() {
            public int compare(Dispositivo a, Dispositivo b) {
                if (a.occhiale() != b.occhiale()) return a.occhiale() ? -1 : 1;
                return Double.compare(b.rssi, a.rssi);
            }
        });
        if (agg != null) bers = agg;
        else for (Dispositivo d : visibili) if (d.occhiale()) { bers = d; break; }

        titolo.setText(occhiali == 0 ? "RADAR OCCHIALI · nessun occhiale in vista"
                : "RADAR OCCHIALI · " + occhiali + (occhiali == 1 ? " occhiale" : " occhiali") + " in vista"
                + (agganciato != null ? " · AGGANCIATO" : ""));
        radar.imposta(visibili, bers, agganciato != null);

        if (bers == null) {
            grande.setText("—");
            grande.setTextColor(Color.rgb(120, 130, 125));
            piccolo.setText(scansiona ? "Cammino tra i banchi e ascolto…" : "Scansione ferma");
        } else {
            double vic = Rilevatore.vicinanza(bers.rssi);
            grande.setText(String.format(Locale.ITALY, "%d%%  ·  ~%s", Math.round(vic * 100), metri(bers.rssi)));
            grande.setTextColor(RadarView.colore(vic));
            piccolo.setText(String.format(Locale.ITALY, "%s · %d dBm · %s", bers.motivo != null ? bers.motivo : "BLE",
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
            t.setText((agg ? "▶ " : "") + (d.occhiale() ? "👓 " + d.motivo : (d.nome != null ? d.nome : "dispositivo BLE"))
                    + "\n" + sb + "  " + Math.round(d.rssi) + " dBm  ~" + metri(d.rssi) + "  " + coda(d.mac));
            t.setTextColor(d.occhiale() ? RadarView.colore(Rilevatore.vicinanza(d.rssi)) : Color.rgb(150, 155, 150));
            t.setBackgroundColor(agg ? Color.rgb(30, 50, 40) : Color.TRANSPARENT);
            return t;
        }
    }
}
