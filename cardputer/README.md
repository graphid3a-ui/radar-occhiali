# Radar Occhiali per Cardputer

Firmware per M5Stack Cardputer (v1.1 e ADV) da avviare con M5Launcher.

- `bin/RadarOcchiali_Cardputer.bin`: rilevatore occhiali smart (sorgente in `src/main.cpp`).
  Nessun dato salvato, Wi-Fi spento, solo ascolto degli annunci BLE.
  Tasti: `;` `.` su/giù, INVIO aggancia (modalità caccia con picco), BACKSPACE indietro,
  `f` solo occhiali/tutti, `s` suono, `r` azzera picco, `h` aiuto.
- `bin/GhostBLE_Cardputer.bin`: [GhostBLE](https://github.com/SmonSE/GhostBLE) di SmonSE (licenza MIT),
  compilato senza modifiche dal sorgente originale.

Installazione: copiare i `.bin` sulla microSD e sceglierli dal Launcher (voce SD).
