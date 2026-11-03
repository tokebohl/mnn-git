# MNN_Next — Jarvis AI Brain (OnePlus 12R Edition)

Komplet, produktionsklar custom MNN-LLM Android-applikation bygget specifikt til **OnePlus 12R (Snapdragon 8 Gen 2, 16GB LPDDR5X RAM, Adreno 740, LineageOS)** med fuldautomatisk, offline LAN-kobling til **[HayaiTTS-Next](https://github.com/tokebohl/HayaiTTS-Next)** på **OnePlus 10T** og **Home Assistant** på **Raspberry Pi 4B**.

---

## Arkitektur & Funktioner

1. **Ægte MNN C++ JNI Inferens & Snapdragon 8 Gen 2 Optimering (`mnn_llm_jni.cpp` + `MnnHardwareProfile.kt`)**
   - Indlæser `config.json` via `MNN::Transformer::Llm::createLLM(configPath)` og sætter runtime-overrides (`backend_type`, `thread_num`, `precision: low`, `use_mmap`, `reuse_kv`).
   - Automatisk fallback fra GPU (`opencl` / `vulkan` på Adreno 740) til CPU (`5` hurtige Kryo Prime/Gold kerner), hvis GPU-driveren blokerer.
   - Atomisk afbrydelse (`nativeCancelGeneration`), KV-cache genbrug og nulstilling, samt UTF-8-sikret `std::streambuf` token-streaming.
   - Understøtter både lokal `MNN::Express::Module` embedding-model (`embedding.mnn`) og 384-D lokal semantisk vektor-indeksering.

2. **Autentificeret HayaiTTS-Next `/v1/tts/stream` Integration (`HayaiTtsBridge.kt`)**
   - Bruger det dokumenterede autentificerede WebSocket endpoint `/v1/tts/stream` med `Authorization: Bearer <API_KEY>` og `X-API-Key` headers (gemt i Android Keystore via `SecureCredentialStore.kt`).
   - Streamer sætninger og delsætninger i realtid med `{"type":"start"}`, `{"type":"text"}`, `{"type":"end"}` og `{"type":"cancel"}` protokollen uden at vente på hele LLM-svaret.
   - Modtager og afspiller binære **PCM16LE** lyd-frames direkte via lav-latens `AudioTrack.MODE_STREAM`.
   - 3-lags Zero-Config Discovery: mDNS (`_hayaitts._tcp.`), UDP Broadcast (`42110`) og parallel `/24` subnet-scanner samt manuel IP-konfiguration.

3. **Persistent Offline Hukommelse (`JarvisMemoryStore.kt`)**
   - SQLite + FTS5 (`bm25`) fuldtekst-søgning + 384-D Cosine Similarity vektor-søgning.
   - Automatisk udtræk af fakta fra både brugerens beskeder og modellens `[REMEMBER: ...]` tags.
   - Fuld UI-understøttelse til at inspicere, redigere, tilføje og slette minder.

4. **Home Assistant & Jarvis Orchestration (`JarvisBrainService.kt` + `HomeAssistantBridge.kt`)**
   - Kører som persistent Foreground Service på LineageOS og udfører `[HA_ACTION: domain.service | entity_id]` direkte mod din Raspberry Pi 4B.

---

## Fysisk Verifikation på Enheder (OnePlus 12R + OnePlus 10T)

*Bemærk: Da fysisk Adreno 740 OpenCL/Vulkan-acceleration, mDNS/UDP Wi-Fi discovery og MNN model-inferens kræver de fysiske telefoner, skal følgende verificeres efter installation af APK'en:*

1. Kopiér din MNN-modelmappe (`config.json`, `llm.mnn`, `llm.mnn.weight`, `tokenizer.txt`) til `/sdcard/MNNModels/<model-navn>/` på OnePlus 12R.
2. Åbn fanen **10T & HW**, vælg modellen, vælg `Adreno 740 OpenCL` (eller `CPU`), og tryk **Indlæs Model**.
3. Indtast din HayaiTTS API-nøgle (og evt. OnePlus 10T IP/Port) under **10T & HW**, og tryk **Gem & Forbind** eller **Test Tale**.
