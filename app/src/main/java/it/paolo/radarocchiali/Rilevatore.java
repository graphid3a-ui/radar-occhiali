package it.paolo.radarocchiali;

import java.util.Arrays;

/**
 * Riconoscimento smartglasses dai dati di advertising BLE. Nessuna dipendenza Android: testabile su PC.
 *
 * Due livelli:
 *  - CERTO: firma usata solo (o quasi solo) da occhiali smart. Fa suonare il radar.
 *  - PROBABILE: firma condivisa con altri prodotti dello stesso marchio (cuffie, visori, casse).
 *    Compare in lista con "?" in arancione e suona solo se lo agganci a mano.
 *
 * Fonti: Bluetooth SIG assigned numbers (company_identifiers.yaml, member_uuids.yaml)
 * e il codice di Nearby Glasses (github.com/yjeanrenaud/yj_nearbyglasses, AGPL: solo i dati, non il codice).
 */
public final class Rilevatore {
    private Rilevatore() {}

    public static final String PREFISSO_PROBABILE = "? ";

    // ---- Company ID (Bluetooth SIG) ----
    static final int[] CID_CERTI = { 0x01AB, 0x058E, 0x0D53, 0x03C2, 0x10F9, 0x060C };
    static final String[] CID_CERTI_NOME = { "Meta", "Meta Tech", "Luxottica (Ray-Ban/Oakley)",
            "Snap Spectacles", "Even Realities", "Vuzix" };
    /** Zhuhai Jieli: chip usato da occhiali cinesi economici ma anche da tante cuffie e casse. */
    static final int[] CID_PROBABILI = { 0x05D6 };
    static final String[] CID_PROBABILI_NOME = { "Jieli (occhiali cinesi o cuffie)" };

    // ---- UUID di servizio ----
    static final String[] UUID_CERTI = {
            "0000fd5f-0000-1000-8000-00805f9b34fb",   // Meta Platforms Technologies
            "0000fe45-0000-1000-8000-00805f9b34fb",   // Snapchat (Spectacles)
            "7905fff0-b5ce-4e99-a40f-4b1e122d00d0" }; // HeyCyan: Nilox e molti occhiali AI cinesi
    static final String[] UUID_CERTI_NOME = { "Meta FD5F", "Snap FE45", "HeyCyan" };
    static final String[] UUID_PROBABILI = {
            "0000feb7-0000-1000-8000-00805f9b34fb",   // Meta Platforms Inc: anche visori Quest
            "0000feb8-0000-1000-8000-00805f9b34fb" };
    static final String[] UUID_PROBABILI_NOME = { "Meta FEB7 (occhiali o visore)", "Meta FEB8 (occhiali o visore)" };

    // ---- Da Echo: company 0xF0EF + service data FEA8 con payload fisso ----
    static final int DA_ECHO_CID = 0xF0EF;
    static final byte[] DA_ECHO_DATI = { 0x41, 0x31, 0x49, 0x01, 0x00, 0x00 };

    // ---- Nomi trasmessi (minuscolo) ----
    static final String[] NOMI_CERTI = { "ray-ban", "rayban", "ray ban", "rb meta", "oakley meta", "meta glass",
            "heycyan", "spectacles", "even g1", "even g2", "vuzix", "bose frames", "echo frames",
            "rokid", "rayneo", "xreal", "nreal", "inmo air", "inmo go", "halliday", "solos",
            "smart glasses", "ai glasses", "occhiali" };
    static final String[] NOMI_PROBABILI = { "glasses", "glass" };

    /** @return motivo (con PREFISSO_PROBABILE se solo probabile), oppure null se non sembra uno smartglass. */
    public static String riconosci(int[] companyIds, String[] uuids, String nome, byte[] datiFea8) {
        String probabile = null;
        if (companyIds != null)
            for (int cid : companyIds) {
                for (int i = 0; i < CID_CERTI.length; i++)
                    if (cid == CID_CERTI[i]) return "ID " + CID_CERTI_NOME[i];
                if (cid == DA_ECHO_CID && datiFea8 != null && Arrays.equals(datiFea8, DA_ECHO_DATI))
                    return "Da Echo";
                for (int i = 0; i < CID_PROBABILI.length; i++)
                    if (cid == CID_PROBABILI[i] && probabile == null) probabile = "ID " + CID_PROBABILI_NOME[i];
            }
        if (uuids != null)
            for (String u : uuids) {
                if (u == null) continue;
                String l = u.toLowerCase();
                for (int i = 0; i < UUID_CERTI.length; i++)
                    if (l.equals(UUID_CERTI[i])) return "UUID " + UUID_CERTI_NOME[i];
                for (int i = 0; i < UUID_PROBABILI.length; i++)
                    if (l.equals(UUID_PROBABILI[i]) && probabile == null) probabile = "UUID " + UUID_PROBABILI_NOME[i];
            }
        if (nome != null) {
            String l = nome.toLowerCase();
            for (String p : NOMI_CERTI) if (l.contains(p)) return "nome \"" + nome + "\"";
            for (String p : NOMI_PROBABILI) if (l.contains(p) && probabile == null) probabile = "nome \"" + nome + "\"";
        }
        return probabile == null ? null : PREFISSO_PROBABILE + probabile;
    }

    public static boolean certo(String motivo) {
        return motivo != null && !motivo.startsWith(PREFISSO_PROBABILE);
    }

    /** Distanza stimata in metri dal segnale (modello log-distanza, ambiente chiuso). */
    public static double metri(double rssi) {
        return Math.pow(10.0, (-59.0 - rssi) / (10.0 * 2.5));
    }

    /** Vicinanza 0..1: 0 = -95 dBm o meno, 1 = -45 dBm o più. */
    public static double vicinanza(double rssi) {
        double x = (rssi + 95.0) / 50.0;
        return x < 0 ? 0 : (x > 1 ? 1 : x);
    }

    /** Intervallo tra due bip in ms: lento da lontano, quasi continuo da vicinissimo. */
    public static int periodoBip(double rssi) {
        double x = vicinanza(rssi);
        return (int) Math.round(1500 - x * x * 1420);
    }
}
