# TWS Latency Lab 🎧
### *2-Phase Calibrated Bluetooth Audio Latency Measurement*

> **Stop reading the brochure. Start measuring reality.**

---

## What Is This?

**TWS Latency Lab** is a precision Android tool that measures the true end-to-end latency of any Bluetooth TWS or wired earphone using an acoustic loopback pipeline and PCM cross-correlation math.

It does not guess. It does not estimate from specs. It physically plays a sound, records it through the microphone, and calculates the exact delay down to the millisecond.

---

## The Market Lie 🗿

Every TWS brand prints a latency number on the box. Here is what they do not tell you:

That number is measured in a **laboratory vacuum** — an isolated chip test inside a Faraday cage with zero interference, no operating system overhead, and no real phone in the loop.

The second you connect to an actual Android phone, reality kicks in:

| Layer | Latency Added |
|---|---|
| Android Audio Mixer | ~20–40 ms |
| Bluetooth A2DP Safety Buffer | ~150–240 ms |
| Codec Encoding + Decoding (SBC/AAC) | ~20–60 ms |
| Air gap (sound traveling to mic) | ~1 ms |

**The result?**

- A TWS marketed as **"10ms Ultra Low Latency"** → **~150–200ms in reality**
- A TWS marketed as **"50ms Gaming Mode"** → **~120–180ms in reality**
- A standard music/movie TWS with no gaming claims → **~190–200ms+ in reality**

Video apps like YouTube fool your eyes by **delaying the video** to match the slow audio. Games cannot predict the future. When you press fire, you feel every single millisecond of that pipeline lag.

**If you want true zero-latency audio for gaming — buy a wire. Physics always wins. 🗿**

---

## How It Works

TWS Latency Lab uses a **2-phase calibrated measurement pipeline:**

### Phase 1 — Device Baseline
The app plays a raw PCM tone through the phone's built-in speaker and records it back through the microphone. This captures the phone's own internal audio pipeline overhead so it can be subtracted from the final result.

### Phase 2 — TWS Bluetooth Test
The same raw PCM tone is played through your connected TWS. The microphone records the sound coming out of the earbuds. The app calculates the total round-trip time.

### Net Result
```
True Earbud Latency = TWS Absolute Measurement - Device Baseline - A2DP Buffer Estimate
```

The app also shows a **Laboratory Reconstruction** — what your earbud's chip *could* theoretically do if the Android OS and Bluetooth stack were removed from the equation. This is the number closest to what brands print on their boxes.

### The Math — PCM Cross-Correlation
The app does not detect the sound using a simple volume threshold. It uses **cross-correlation** — sliding the original recorded waveform across the captured mic buffer and finding the exact sample index where mathematical similarity peaks. This gives sub-millisecond precision regardless of background noise.

```
R(n) = Σ f(k) · g(n + k)
```

The peak of R(n) = exact arrival time of sound at the microphone.

---

## Screenshots

| Phase 1 — How It Works | Phase 2 — Baseline Captured | Result Screen |
|---|---|---|
| ![How it works](screenshots/phase1.jpg) | ![Baseline](screenshots/phase2.jpg) | ![Result](screenshots/result.jpg) |

---

## Tech Stack

- **Language:** Kotlin
- **Audio Pipeline:** Android AudioTrack (Raw PCM) + AudioRecord
- **Algorithm:** PCM Cross-Correlation DSP
- **Architecture:** MVVM (ViewModel + LiveData)
- **Min SDK:** Android 6.0 (API 23)

---

## Download

> APK coming soon in [Releases](../../releases)

---

## Building From Source

```bash
git clone https://github.com/Horizon-25/Twslatencylab
cd Twslatencylab
./gradlew assembleDebug
```

---

## License

Licensed under the [Apache 2.0 License](LICENSE)

---

*Built by [Horizon-25](https://github.com/Horizon-25)*
