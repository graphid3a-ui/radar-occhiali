package it.paolo.radarocchiali;

/** Un dispositivo BLE visto dallo scanner. */
public final class Dispositivo {
    public final String mac;
    public String motivo;      // null = non è un occhiale
    public String nome;
    public double rssi;        // media mobile
    public int rssiGrezzo;
    public long visto;         // SystemClock.elapsedRealtime
    public int pacchetti;
    public final float angolo; // solo per disegnarlo sul radar (la direzione NON è misurata)

    public Dispositivo(String mac, int rssi, long ora) {
        this.mac = mac;
        this.rssi = rssi;
        this.rssiGrezzo = rssi;
        this.visto = ora;
        this.angolo = (float) ((mac.hashCode() & 0x7fffffff) % 360);
    }

    public void aggiorna(int r, long ora) {
        // media mobile esponenziale: smorza le oscillazioni tipiche del Bluetooth
        rssi = 0.3 * r + 0.7 * rssi;
        rssiGrezzo = r;
        visto = ora;
        pacchetti++;
    }

    public boolean occhiale() { return motivo != null; }
}
