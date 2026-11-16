package it.paolo.radarocchiali;

/** Riconoscimento smartglasses dai dati di advertising BLE. Nessuna dipendenza Android: testabile su PC. */
public final class Rilevatore {
    private Rilevatore() {}

    /** Company ID Bluetooth SIG dei produttori di smartglasses con fotocamera. */
    public static final int[] COMPANY_ID = { 0x01AB, 0x058E, 0x0D53, 0x03C2 };
    public static final String[] NOME_ID = { "Meta", "Meta Tech", "Luxottica", "Snap" };

    /** Frammenti di UUID di servizio (16 bit) associati a Meta. */
    public static final String[] UUID_NOTI = { "0000fd5f-" };
    public static final String[] NOME_UUID = { "Meta FD5F" };

    /** Parole cercate nel nome trasmesso (minuscolo). */
    public static final String[] PAROLE = { "ray-ban", "rayban", "rb meta", "oakley", "meta glass",
            "spectacles", "xreal", "nreal", "rokid", "rayneo", "even g1", "even g2", "vuzix", "inmo",
            "solos", "echo frames", "halliday", "glasses", "occhiali" };

    /** @return il motivo del riconoscimento, oppure null se non sembra uno smartglass. */
    public static String riconosci(int[] companyIds, String[] uuids, String nome) {
        if (companyIds != null)
            for (int cid : companyIds)
                for (int i = 0; i < COMPANY_ID.length; i++)
                    if (cid == COMPANY_ID[i]) return "ID " + NOME_ID[i];
        if (uuids != null)
            for (String u : uuids) {
                if (u == null) continue;
                String l = u.toLowerCase();
                for (int i = 0; i < UUID_NOTI.length; i++)
                    if (l.startsWith(UUID_NOTI[i])) return "UUID " + NOME_UUID[i];
            }
        if (nome != null) {
            String l = nome.toLowerCase();
            for (String p : PAROLE) if (l.contains(p)) return "nome \"" + nome + "\"";
        }
        return null;
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
