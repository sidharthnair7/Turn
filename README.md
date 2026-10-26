# Turn

**Turn runs a restaurant floor from one camera you already own.** It sees which tables need someone, sends the task to the right server, keeps the workload fair, and shows guests at the door an honest wait time. It does this without face recognition and without saving a single frame.

Built in Java 25 with Spring Boot and **OpenCV 5**, for Devpost's *Build With AI: Basics*, using the Devpost Learn skill pack. The planning docs are in [`devpost/`](devpost/): [scope](devpost/scope.md), [PRD](devpost/prd.md), [spec](devpost/spec.md).

## Why

Restaurant-floor products like Shire (YC Fall 2026) use CCTV to track tables for staff. Turn covers four gaps:

| Gap | What Turn does |
|---|---|
| Guests see nothing | **A live wait page for guests**, computed from the tables the camera actually sees |
| Needs CCTV and a POS | Runs on **one laptop or phone camera** |
| Scores employees from video | **Staff wear a printed badge.** Tables are tracked, not faces. No per-person efficiency scores, and people are blurred in the view |
| Guests have to catch someone's eye | **A table card** (HELP / CHECK) the guest flips, which the camera reads |

## What it does

- **Table states:** Empty → Seated → (guests leave) Needs bussing → (a staff badge visits) Ready → Empty
- **Alerts, routed to the table's server:**
  - **Not greeted:** seated, and no badge has visited yet
  - **Check in:** too long since the last visit
  - **Help requested** / **Waiting on check:** the guest flipped a card
  - **Needs bussing**

  Alerts go amber when raised and red when overdue. **The next staff badge visit clears them automatically.**
- **Server load:**
  - The first badge to reach a newly seated table owns it.
  - Unowned tables suggest the lightest-loaded server.
- **Waitlist:**
  - Guests join from `/wait` and see their place and estimate live.
  - The host presses **Seat**, and Turn picks the smallest free table that fits and blocks double-seating.
- **Wait estimate:** simulates the host seating everyone ahead of you at the soonest table that fits. It uses each table's time seated and an average dining time learned from finished tables.
- **Privacy view:** a camera preview with every face blurred (OpenCV zoo YuNet; if a face isn't found, the top of the person's box is blurred) and staff labelled only by badge number.

## Run it

Needs Java 25 and Windows. The OpenCV 5 Java bindings come from OpenCV's official Windows package.

1. **Get OpenCV 5.0.0.**
   1. Download `opencv-5.0.0-windows.exe` from https://github.com/opencv/opencv/releases/tag/5.0.0 and extract it.
   2. Copy these into a `lib/` folder in this repo:
      - `build/java/opencv-500.jar`
      - `build/java/x64/opencv_java500.dll`
      - `build/bin/opencv_videoio_ffmpeg500_64.dll` (to read video files)
   3. Install the jar for Maven:
      ```
      mvnw install:install-file -Dfile=lib/opencv-500.jar -DgroupId=org.opencv -DartifactId=opencv -Dversion=5.0.0 -Dpackaging=jar
      ```
2. **Get the models** from the OpenCV model zoo (Apache-2.0 / MIT) into `lib/models/`:
   - people: https://huggingface.co/opencv/object_detection_nanodet/resolve/main/object_detection_nanodet_2022nov.onnx
   - faces (for the blur): https://huggingface.co/opencv/face_detection_yunet/resolve/main/face_detection_yunet_2023mar.onnx
3. **Run:**
   ```
   mvnw spring-boot:run
   ```
   Open http://localhost:8080 for the landing page, http://localhost:8080/floor for the **Floor** and http://localhost:8080/wait for the **guest page**.

### Modes (`turn.source` in `src/main/resources/application.yml`)

- **`demo` (default):** a scripted two-minute dinner service drives the same engine, with no camera. Labelled DEMO. **Restart demo** replays it.
  - Demo mode doesn't need OpenCV at all, so steps 1 and 2 are only for camera mode.
- **`camera:0`:** your webcam.
  - Set each table's zone (`x, y, w, h` in camera pixels) in `application.yml`.
  - Print the badges and table cards from [`markers/`](markers/).
- **`file:path/to/clip.mp4`:** a recorded clip.
  - `mvnw spring-boot:run -Dspring-boot.run.profiles=clip` runs the real pipeline on OpenCV's own sample video (`lib/samples/vtest.avi`, copied from the OpenCV package).
  - `mvnw spring-boot:run -Dspring-boot.run.profiles=restaurant` runs it on free-licence restaurant footage: Mixkit clip 44025, "Man and woman eating and talking". Download https://assets.mixkit.co/videos/44025/44025-720.mp4 to `lib/samples/mixkit-44025-couple-eating.mp4`. The diner is detected and blurred, the table goes Seated, then Not greeted.

### Markers (ArUco, `DICT_4X4_50`) in `markers/`

- **Staff badges:** IDs 1 to 3, mapped to server names in `application.yml`.
- **Table cards:** HELP = 20 + table number, CHECK = 30 + table number.

## How it works

```
camera/clip → OpenCV 5:   NanoDet (dnn) finds people  +  ArucoDetector finds badges and cards
            → per table:  guests in zone, staff badges in zone, cards up?
            → FloorEngine (state machine, alerts, load, waitlist, estimates)
            → REST /api/floor, /api/wait  → Floor and Wait pages (plain HTML/JS, poll every 1–2 s)
demo script ───────────────────────────────┘ (same engine, no camera)
```

- `floor/FloorEngine.java`: the state machine, alert routing, load, and the waitlist simulation. It only ever sees counts and badge IDs, never images.
- `vision/`: `PersonDetector` (OpenCV zoo NanoDet, ported to Java and OpenCV 5), `MarkerDetector` (ArUco), and `VisionService` (capture loop and privacy preview).
- `demo/DemoSource.java`: the scripted service.
- [`frontend/`](frontend/): the Floor and Wait pages in plain HTML, CSS and JS. Maven packages them as the web root, so `/` and `/wait` serve them.
- Tests: `mvnw test`. They cover seating, greeting, the check card, bussing, double-seat blocking, table choice and wait estimates.

## Limits we measured (OpenCV 5, on a laptop)

- **Background subtraction (MOG2) forgets a person who sits still within about 4 s,** so Turn uses a person detector for "seated" instead.
- **NanoDet at the zoo's default 0.35 threshold called traffic cones "people".** Turn uses 0.5, which dropped them in testing.
- **Badges smaller than about 40 px in the image were missed when turned 45°.** Keep the camera close (about 2 m for a phone-screen badge) or print badges larger.
- **Zones are drawn in config, not in the UI.** One camera only.
- **Dining time is one global average,** not per party size.

## Credits

- [OpenCV](https://opencv.org) 5.0.0 (Apache-2.0)
- NanoDet model from the [OpenCV model zoo](https://huggingface.co/opencv/object_detection_nanodet) (Apache-2.0)
- `vtest.avi` sample from the OpenCV package

MIT licensed, see [LICENSE](LICENSE).
