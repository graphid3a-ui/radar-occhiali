package it.paolo.radarocchiali;

/**
 * Stima della direzione con il "giro di scansione".
 *
 * Il Bluetooth non dice da dove arriva il segnale, ma il corpo umano lo attenua di 5-15 dB.
 * Tenendo il telefono al petto e girando su se stessi, il segnale è più forte quando si è
 * rivolti verso gli occhiali. Si raccoglie l'RSSI per settori di bussola (24 x 15°) e si
 * prende il settore migliore, con una leggera media sui vicini.
 *
 * Nessuna dipendenza Android: testabile su PC.
 */
public final class Direzione {
    public static final int SETTORI = 24;
    private static final float LARGHEZZA = 360f / SETTORI;

    private final double[] somma = new double[SETTORI];
    private final int[] conta = new int[SETTORI];

    public void azzera() {
        for (int i = 0; i < SETTORI; i++) { somma[i] = 0; conta[i] = 0; }
    }

    private static int settore(float azimut) {
        float a = ((azimut % 360f) + 360f) % 360f;
        return Math.min(SETTORI - 1, (int) (a / LARGHEZZA));
    }

    /** Aggiunge un campione: azimut del telefono in gradi (0 = nord) e RSSI grezzo in dBm. */
    public void aggiungi(float azimut, int rssi) {
        int s = settore(azimut);
        somma[s] += rssi;
        conta[s]++;
    }

    /** Settori che hanno almeno un campione. */
    public boolean coperto(int s) { return conta[s] > 0; }

    /** Quota del giro completata, 0..1. */
    public float copertura() {
        int c = 0;
        for (int n : conta) if (n > 0) c++;
        return c / (float) SETTORI;
    }

    private double media(int s) {
        s = ((s % SETTORI) + SETTORI) % SETTORI;
        return conta[s] > 0 ? somma[s] / conta[s] : Double.NaN;
    }

    /** Media pesata 1-2-1 con i settori vicini (salta quelli vuoti). */
    private double liscia(int s) {
        double t = 0, w = 0;
        int[] off = { -1, 0, 1 };
        double[] pes = { 1, 2, 1 };
        for (int k = 0; k < 3; k++) {
            double m = media(s + off[k]);
            if (!Double.isNaN(m)) { t += m * pes[k]; w += pes[k]; }
        }
        return w > 0 ? t / w : Double.NaN;
    }

    /** Azimut stimato verso il dispositivo (gradi, 0 = nord), oppure NaN se non ci sono dati. */
    public float migliore() {
        int best = -1;
        double bv = -1e9;
        for (int s = 0; s < SETTORI; s++) {
            if (conta[s] == 0) continue;
            double v = liscia(s);
            if (v > bv) { bv = v; best = s; }
        }
        return best < 0 ? Float.NaN : best * LARGHEZZA + LARGHEZZA / 2f;
    }

    /** Differenza in dB tra il settore migliore e il peggiore: più è alta, più la freccia è affidabile. */
    public double contrasto() {
        double mx = -1e9, mn = 1e9;
        for (int s = 0; s < SETTORI; s++) {
            if (conta[s] == 0) continue;
            double v = liscia(s);
            mx = Math.max(mx, v);
            mn = Math.min(mn, v);
        }
        return mx < mn ? 0 : mx - mn;
    }

    /** La stima è utilizzabile: giro quasi completo e differenza sufficiente. */
    public boolean pronta() {
        return copertura() >= 0.8f && contrasto() >= 5.0;
    }
}
