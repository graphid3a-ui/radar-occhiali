# Radar Occhiali

App Android che trova gli smartglasses con fotocamera nelle vicinanze (Ray-Ban/Oakley Meta, Snap Spectacles e altri) tramite scansione Bluetooth LE passiva, con un radar di vicinanza e bip/vibrazione a contatore Geiger.

- Riconoscimento: Company ID 0x01AB, 0x058E (Meta), 0x0D53 (Luxottica), 0x03C2 (Snap), UUID 0xFD5F, nome trasmesso, e la sigla `META_RB_GLASS` nel pacchetto (Ray-Ban Meta confermati, non un visore Quest). Elenco completo in FIRME.md.
- Distanza stimata dal segnale (RSSI con media mobile).
- **Trova direzione** (v1.2): con il telefono al petto giri lentamente su te stesso; l'app unisce bussola e segnale per settori di 15° e disegna una freccia verso il punto dove il segnale è più forte (il corpo scherma 5-15 dB). La freccia diventa rossa quando il giro copre almeno l'80% e il contrasto supera 5 dB.
- Tocca un dispositivo per agganciarlo. "Tutti BLE" mostra anche gli altri dispositivi.

## Privacy
- Nessun permesso INTERNET: l'app non può inviare dati.
- Gli indirizzi stanno solo in memoria e vengono dimenticati dopo 60 secondi; niente file, database, log o preferenze salvate.
- Android 12+: permesso "Dispositivi nelle vicinanze" con `neverForLocation`, il GPS non serve.
- Nessuna connessione agli occhiali: solo ascolto degli annunci Bluetooth.

## APK
Ogni push compila l'APK con GitHub Actions: scheda **Actions** → ultima esecuzione → **Artifacts** → `radar-occhiali-apk`.
