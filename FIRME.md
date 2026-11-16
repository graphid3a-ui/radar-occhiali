# Firme Bluetooth riconosciute (v1.1, 16/11/2026)

## Certe (fanno suonare il radar)
| Firma | Tipo | Marchio | Fonte |
|---|---|---|---|
| 0x01AB | Company ID | Meta Platforms | Bluetooth SIG, Nearby Glasses |
| 0x058E | Company ID | Meta Platforms Technologies | Bluetooth SIG, Nearby Glasses |
| 0x0D53 | Company ID | Luxottica (Ray-Ban / Oakley Meta) | Bluetooth SIG, Nearby Glasses |
| 0x03C2 | Company ID | Snapchat (Spectacles) | Bluetooth SIG, Nearby Glasses |
| 0x10F9 | Company ID | Even Realities (G1/G2: display + microfono, senza fotocamera) | Bluetooth SIG |
| 0x060C | Company ID | Vuzix | Bluetooth SIG |
| 0xFD5F | UUID servizio | Meta Platforms Technologies | Bluetooth SIG |
| 0xFE45 | UUID servizio | Snapchat | Bluetooth SIG |
| 7905FFF0-B5CE-4E99-A40F-4B1E122D00D0 | UUID servizio | HeyCyan (Nilox e molti occhiali AI cinesi) | Nearby Glasses |
| 0xF0EF + service data FEA8 = 413149010000 | combinazione | Da Echo | Nearby Glasses |
| nomi: ray-ban, heycyan, spectacles, even g1/g2, vuzix, bose frames, echo frames, rokid, rayneo, xreal, inmo, halliday, solos, smart/ai glasses | nome | vari | nomi commerciali |

## Probabili (arancione, suonano solo se agganciati)
| Firma | Perché solo probabile |
|---|---|
| 0x05D6 Zhuhai Jieli | chip di occhiali economici (Rogbird, Rollme) ma anche di moltissime cuffie e casse |
| 0xFEB7 / 0xFEB8 Meta | usati anche dai visori Quest |
| nome con "glass" | troppo generico |

## Non aggiunte di proposito
Amazon, Bose, TCL (RayNeo), Xiaomi, Huawei, Google, Samsung, Oppo: il Company ID è lo stesso di telefoni, cuffie e altoparlanti, quindi in aula darebbe solo falsi allarmi. Questi marchi si riconoscono solo dal nome, se lo trasmettono. XREAL e Rokid di solito si collegano via cavo USB-C e spesso non trasmettono in Bluetooth.
