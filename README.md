# 🧲 TorrentHub AIO v1.4.0

![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026--35)-3DDC84?logo=android&logoColor=white)
![Version](https://img.shields.io/badge/Release-v1.4.0-2563EB)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21%20%2B%20Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![UI Language](https://img.shields.io/badge/UI-Indonesia%20%7C%20English-0D9488)

**TorrentHub AIO (All-In-One)** adalah aplikasi ekosistem media Android modern berbasis **Jetpack Compose** yang menyatukan pencarian torrent multi-situs, integrasi cloud & streaming instan **PikPak**, pencarian serta editor subtitle multi-sumber, penerjemah subtitle berbasis **AI & Multi-API**, peramban internal berfitur **Ad-Blocker + Tampermonkey Userscript**, **RSS Monitor**, hingga fitur privasi tingkat lanjut (**DNS-over-HTTPS**, **App Lock PIN/Biometrik**, & **Mode Samaran**) dalam satu aplikasi ringan.

---

## 🚀 Sorotan Pembaruan Besar v1.4.0

### 🌐 1. Multi-Bahasa UI (ID/EN) & Terjemahan Subtitle Lengkap (17 Bahasa)
- **Antarmuka 2 Bahasa Instan:** Beralih antara **Bahasa Indonesia** dan **English** secara langsung tanpa perlu memuat ulang paksa aplikasi.
- **17 Bahasa Target Subtitle:** Mendukung pencarian, penerjemahan, dan pemolesan (*polish*) subtitle ke Indonesia, Inggris, Mandarin (Sederhana & Tradisional), Jepang, Korea, Melayu, Arab, Spanyol, Prancis, Jerman, Portugis, Rusia, Hindi, Thailand, Vietnam, dan Turki.

### 🎨 2. Tema Tampilan Dinamis (Light / Dark / AMOLED Black)
- **Light Mode:** Antarmuka terang, bersih, dan kontras tinggi untuk penggunaan luar ruangan.
- **Dark Mode:** Skema warna *slate* gelap klasik yang nyaman di mata.
- **AMOLED Black (`#000000`):** Hitam pekat murni untuk layar OLED/AMOLED yang hemat baterai di malam hari.

### ⚡ 3. Pencarian 16 Situs Torrent, Stream Magnet & Quick Stream PikPak
- **Agregasi 16 Situs Sekaligus:** Cari judul film, serial, anime, atau kode video secara paralel lengkap dengan deduplikasi cerdas, filter kualitas (`4K`, `Sub/中字`, `Uncensored`, `Multi-Sumber`), penyembunyian *dead torrent* (`0 SE`), dan **Cloudflare WebView Bypass**.
- **Verifikasi Pratinjau:** Cek isi file & tangkapan layar magnet melalui **WhatsLink** serta pencarian sampul gambar **3-Sumber** instan.
- **▶️ Stream Magnet Eksternal:** Buka magnet link langsung ke pemutar streaming torrent favorit di perangkat melalui *chooser* sistem.
- **⚡ Quick Stream via PikPak:** Satu ketukan untuk mengirim magnet ke cloud PikPak, memantau progres *caching* otomatis, memilih file video di dalam folder torrent, dan langsung memutarnya di pemutar eksternal (**VLC, MX Player, MPV**, dll.).
- **☁️ PikPak Cloud & Mini File Manager:** Login sesi web resmi PikPak atau Bearer Token manual, kelola folder *My Drive*, pantau *Cloud Download Tasks*, putar video langsung, dan unggah hasil subtitle `.srt` otomatis ke folder tujuan PikPak.

### 💬 4. Multi-Sumber Subtitle, Editor Delay/Encoding & Auto-Pairing SAF
- **Pencarian Multi-Server:** Cari subtitle secara serentak maupun spesifik dari **Xunlei**, **SubtitleCat**, **OpenSubtitles**, dan **SubDL** lengkap dengan deteksi ukuran file otomatis.
- **Editor Subtitle Terpadu (UTF-8 & Sync):**
  - Edit dialog baris demi baris maupun mode teks mentah (*raw text*) pada teks asli atau hasil terjemahan.
  - **Koreksi Encoding & Perbaikan Mojibake:** Konversi encoding lawas (GBK, Big5, Shift-JIS, EUC-KR, Windows-1252, UTF-16) menjadi **UTF-8** bersih.
  - **Geser Waktu (*Subtitle Delay / Time Shift*):** Sinkronkan timing subtitle maju/mundur dalam hitungan milidetik/detik secara presisi.
- **🎬 Auto-Pairing Nama Video (SAF):** Integrasi *Storage Access Framework* untuk mencocokkan dan menyimpan file `.srt` secara otomatis mengikuti nama file video di folder penyimpanan HP sehingga langsung terbaca oleh pemutar video.

### 🤖 5. Penerjemah Subtitle AI & Custom Multi-API
- **4 Penyedia Mesin Penerjemah:**
  1. **Google Translate (Gratis / Bawaan):** Langsung siap pakai tanpa API Key.
  2. **Google Gemini API (Hybrid + OpenRouter):** Terjemahan kontekstual memahami alur dialog, *Sliding Context* antar-batch, kamus istilah (*Glossary*), pembersih iklan/watermark SRT, output *Bilingual*, dan rotasi multi-key otomatis saat limit/sibuk (`503`).
  3. **DeepL API (`:fx` Free & Pro):** Pilihan model *Prefer Quality (Next-Gen LLM)* atau *Latency Optimized* dengan endpoint kustom.
  4. **OpenAI / Compatible API:** Mendukung GPT-4o, OpenRouter, DeepSeek, Groq, maupun endpoint kompatibel OpenAI kustom.
- **Mode Tugas Ganda:** *Translate* (terjemahkan bahasa asing) & *Polish* (perbaiki terjemahan mesin yang kaku), dilengkapi simpan progres otomatis (*auto-resume*) dan notifikasi progres latar belakang.

### 🧭 6. Browser Internal: Ad-Blocker, Userscript Engine & Download Manager
- **🛡️ Ad-Blocker & Anti-Popup Ketat:** Memblokir ribuan domain jaringan iklan, pelacak, *pop-under*, dan pengalihan lintas-domain tanpa izin lengkap dengan penghitung statistik blokir.
- **🧩 Tampermonkey / Userscript Engine:** Pasang script `.user.js` langsung dari GreasyFork, URL, penyimpanan HP, atau tempel kode; mendukung `GM_xmlhttpRequest` (bebas CORS via DoH), `GM_getValue`/`GM_setValue`, `GM_registerMenuCommand`, dan deteksi otomatis judul/kode video di halaman web.
- **📥 Download Manager Internal:** Mengelola unduhan file dari Browser, `.torrent`, dan subtitle dengan pelacakan kecepatan *real-time*, notifikasi Android interaktif, ekspor ke folder publik `Downloads/TorrentHub`, serta aksi *Buka / Batal / Ulangi / Kirim ke Gemini*.

### 🧲 7. Custom Tracker List Otomatis & RSS Monitor
- **Injeksi Tracker Otomatis & Magnet Builder:** Tambahkan puluhan *public trackers* aktif dan daftar tracker pribadi secara otomatis ke setiap magnet link sebelum disalin, di-stream, atau dikirim ke PikPak, ditambah alat perakit magnet dari *InfoHash* (40-hex / 32-Base32) dan pembaruan daftar tracker satu klik dari URL.
- **📡 RSS Monitor Torrent:** Berlangganan umpan RSS/Atom situs torrent favorit, tandai rilis baru secara otomatis menggunakan **Kata Kunci Pantauan (*Watch Keywords*)**, lalu jalankan *Quick Stream*, salin magnet, atau unduh subtitle langsung dari kartu RSS.

### 🔒 8. Privasi, Keamanan, Backup JSON & Manajemen Penyimpanan
- **🌐 DNS-over-HTTPS (DoH) Anti-Blokir:** Buka situs torrent, penyedia subtitle, dan tracker yang diblokir DNS ISP tanpa VPN menggunakan resolver terenkripsi **Cloudflare (`1.1.1.1`)**, **Google (`8.8.8.8`)**, atau **Quad9 (`9.9.9.9`)** lengkap dengan penguji latensi koneksi.
- **🔐 App Lock (PIN 4–6 Digit & Biometrik):** Lindungi akses aplikasi dengan PIN terenkripsi SHA-256 dan autentikasi **BiometricPrompt** (sidik jari / wajah) otomatis saat aplikasi dibuka kembali.
- **🕵️ Mode Samaran (*Disguise Mode*) & Auto-Clear:** Sembunyikan tangkapan layar aplikasi di *Recent Apps* (`FLAG_SECURE`), samarkan judul header menjadi *Media & Subtitle Utility*, dan aktifkan penghapusan otomatis riwayat pencarian serta sesi WebView saat keluar.
- **💾 Backup & Restore Pengaturan (JSON):** Ekspor dan pulihkan seluruh konfigurasi aplikasi (bahasa, tema, DoH, daftar tracker kustom, umpan & kata kunci RSS, bookmark, serta konfigurasi penerjemah) ke file `.json`, dengan sakelar opsional untuk menyertakan atau mengecualikan API Key sensitif.
- **🗄️ Manajemen Penyimpanan & Cache:** Pantau rincian pemakaian ruang untuk *Cache WebView & Jaringan*, *File Subtitle Sementara*, serta *Riwayat & Log Aplikasi*, lalu bersihkan dalam satu ketukan.

---

## 📲 Instalasi

1. Buka halaman [**Releases**](https://github.com/taguncangtgc-tech/TorrentHub-AIO/releases/latest) dan unduh file **`TorrentHub-AIO-v1.4.0.apk`** (juga tersedia di root repositori ini).
2. Buka file APK di perangkat Android (**Android 8.0 / API 26** ke atas) dan izinkan pemasangan dari sumber tepercaya jika diminta.
3. *(Catatan Pembaruan)*: Rilis resmi `v1.4.0` telah dioptimalkan menggunakan R8 dan ditandatangani dengan sertifikat rilis khusus. Jika perangkat Anda sebelumnya memasang versi pengembangan awal (`v1.0`–`v1.2.1`) yang ditandatangani dengan kunci debug, cadangkan pengaturan Anda terlebih dahulu atau copot versi lama sebelum memasang `v1.4.0`.

---

## 🛠️ Spesifikasi Teknis & Kompilasi

- **Bahasa & UI:** Kotlin `2.0.21`, Jetpack Compose (Material 3), Coroutines & Flow
- **Penyimpanan Preferensi:** AndroidX DataStore Preferences (dengan migrasi otomatis dari SharedPreferences)
- **Jaringan & Parsing:** OkHttp `4.12.0` + `okhttp-dnsoverhttps`, Jsoup `1.18.1`, Coil `2.7.0`, AndroidX WebKit `1.12.1`
- **Target SDK:** `minSdk = 26` (Android 8.0) · `targetSdk = 35` / `compileSdk = 35` (Android 15) · JDK `17`

### Membangun dari Kode Sumber

```powershell
# Kompilasi APK Debug
gradle assembleDebug

# Kompilasi APK Release (teroptimasi R8; menggunakan keystore.properties jika tersedia)
gradle assembleRelease
```

---

## 📝 Riwayat Versi Singkat

- **v1.4.0** — Pembaruan besar ekosistem: UI 2 Bahasa (ID/EN) & 17 bahasa target subtitle, tema Light/Dark/AMOLED, Stream Magnet & Quick Stream PikPak, Multi-Sumber Subtitle + Editor UTF-8/Delay + Auto-Pairing SAF, Multi-API Penerjemah (Google Translate Gratis, Gemini, DeepL, OpenAI), Ad-Blocker Browser, Download Manager Internal, Custom Tracker List & Magnet Builder, RSS Monitor, DNS-over-HTTPS (Cloudflare/Google/Quad9), App Lock PIN/Biometrik & Mode Samaran, Backup/Restore JSON, serta Manajemen Cache & optimasi rilis R8.
- **v1.3.0** — Perbaikan stabilitas, sinkronisasi sesi PikPak, dan navigasi *swipe* antar-tab.
- **v1.2.1 / v1.2.0** — Integrasi Browser internal dengan mesin Userscript Tampermonkey & deteksi kode halaman.
- **v1.1.0 / v1.0.0** — Rilis awal pencarian torrent multi-situs, integrasi PikPak Cloud, dan penerjemah subtitle Gemini.
