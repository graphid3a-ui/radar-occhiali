# Radar Occhiali

App Android che trova gli smartglasses con fotocamera nelle vicinanze (Ray-Ban/Oakley Meta, Snap Spectacles e altri) tramite scansione Bluetooth LE passiva, con un radar di vicinanza e bip/vibrazione a contatore Geiger.

- Riconoscimento: Company ID 0x01AB, 0x058E (Meta), 0x0D53 (Luxottica), 0x03C2 (Snap), UUID 0xFD5F, nome trasmesso.
- Distanza stimata dal segnale (RSSI con media mobile). La direzione non si misura: girati su te stesso, il segnale cala quando gli occhiali sono alle tue spalle.
- Tocca un dispositivo per agganciarlo. "Tutti BLE" mostra anche gli altri dispositivi.

## APK
Ogni push compila l'APK con GitHub Actions: scheda **Actions** → ultima esecuzione → **Artifacts** → `radar-occhiali-apk`.
