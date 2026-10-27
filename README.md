# Turn

**Turn runs a restaurant floor from one camera you already own.** It sees which tables need someone, sends the task to the right server, keeps the workload fair, and shows guests at the door an honest wait time. It does this without face recognition and without saving a single frame.

Built in Java 25 with Spring Boot and **OpenCV 5**. The guest wait board is published through **Amazon S3**, and a **CloudFormation** stack runs a hosted demo on **EC2**.

- **Live guest wait page (S3):** https://turn-wait-sid.s3.amazonaws.com/public/index.html

- **Technical report** (architecture, OpenCV 5, AWS, evaluation, failure cases, responsible use): [docs/REPORT.md](docs/REPORT.md)
- **Architecture diagram:** [docs/architecture.svg](docs/architecture.svg)
- **AWS setup and hosted demo:** [docs/AWS.md](docs/AWS.md), [infra/turn-demo.yaml](infra/turn-demo.yaml)

## Why

Restaurant-floor products like Shire (YC Fall 2026) use CCTV to track tables for staff. Turn covers four gaps:

| Gap | What Turn does |
|---|---|
| Guests see nothing | **A live wait page for guests**, computed from the tables the camera actually sees |
| Needs CCTV and a POS | Runs on **one laptop or phone camera** |
| Scores employees from video | **Staff wear a printed badge.** Tables are tracked, not faces. No per-person efficiency scores, and people are blurred in the view |
| Guests have to catch someone's eye | **A table card** (HELP / CHECK) the guest flips, which the camera reads |

## What it does

**Host (everything in Shire's host view, from one camera):**
- **Floor map:** tables laid out where they sit, coloured Open, Seated, Needs attention, Dirty or Blocked, with the server's initials. Click a free table to block or unblock it.
- **Server rotation bar:** each server's tables, and **Next party** on whoever should get the next table (lightest load).
- **Host stand:**
  - Counts of open, dirty and seated tables.
  - A **waitlist** with walk-in notes ("birthday", "high chair"), a suggested table and server for each party, a quoted wait, and **Seat**.
  - **Reservations** with **Arrived**.
- **Shift flow (`/flow`):**
  - covers, parties, tables turned, average turn time and peak
  - a timeline
  - **Do this next** recommendations
  - live floor signals
  - idle seats and waiting covers, valued at your average check
  - server load as an operational view, never a ranking

**Floor and servers:**

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
- **Shift insights** (Turn's take on an AI operations panel):
  - covers served, average time to first visit, alerts raised and answered
  - response time per alert type, and how fast tables are reset
  - plain-language tips like "Slowest first visit: Table 2 waited 34 s"
  - per table and per alert type, never per person
- **Health:** `/api/health` reports the source, detection time, frames streamed and the last AWS sync.
- **Privacy view:** a smooth camera preview (detection on its own thread, MJPEG stream) with every face blurred (OpenCV zoo YuNet; if a face isn't found, the top of the person's box is blurred) and staff labelled only by badge number.

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
   - people, wide rooms: https://huggingface.co/opencv/object_detection_yolox/resolve/main/object_detection_yolox_2022nov.onnx
   - people, close-up cameras: https://huggingface.co/opencv/object_detection_nanodet/resolve/main/object_detection_nanodet_2022nov.onnx
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

  - **`mvnw spring-boot:run -Dspring-boot.run.profiles=dining`** runs the main demo: a busy real dining room (Pexels video 16478422, free licence) with YOLOX-S and four table zones. Download it from https://www.pexels.com/video/16478422/ to `lib/samples/pexels-16478422.mp4`. Tables go Seated from the footage, Not greeted alerts follow, and every face is blurred.

### AWS (optional)

Pass `--turn.aws.bucket=<bucket>` and Turn publishes the anonymous wait board and the guest page to S3 every 10 s. With no bucket set, cloud sync is off and everything runs locally.

For the hosted demo, run `mvnw package -DskipTests`, upload `target/turn.jar` to the bucket's `releases/` folder, and create a stack from [infra/turn-demo.yaml](infra/turn-demo.yaml). It runs the demo shift on EC2 behind port 80, with no SSH. Steps are in [docs/AWS.md](docs/AWS.md).

### Test and evaluate

- `mvnw test` runs 16 tests: floor logic (seating, greeting, cards, bussing, double-seat blocking, table choice, wait estimates, blocking, reservations, suggestions, Shift Flow) and the cloud board.
- `java --enable-native-access=ALL-UNNAMED -cp "target/classes;<maven classpath>" turn.turn.eval.Evaluate <clip> <outDir> 10 40` compares NanoDet and YOLOX on a clip and writes a CSV plus annotated frames. Results are in [docs/eval/](docs/eval/).

### Markers (ArUco, `DICT_4X4_50`) in `markers/`

- **Staff badges:** IDs 1 to 3, mapped to server names in `application.yml`.
- **Table cards:** HELP = 20 + table number, CHECK = 30 + table number.

## How it works

```
camera/clip → OpenCV 5:   YOLOX-S or NanoDet (dnn) finds people  +  ArucoDetector finds badges and cards
            → per table:  guests in zone, staff badges in zone, cards up?
            → FloorEngine (state machine, alerts, rotation, waitlist, reservations, estimates, shift flow)
            → REST /api/floor, /api/flow, /api/wait  → Floor, Shift flow and Wait pages
            → CloudSync → S3 (guest wait board, shift history)
demo script ───────────────────────────────┘ (same engine, no camera)
```

- `floor/FloorEngine.java`: the state machine, alert routing, load, and the waitlist simulation. It only ever sees counts and badge IDs, never images.
- `vision/`: `YoloxDetector` and `PersonDetector` (OpenCV zoo YOLOX-S and NanoDet, ported to Java and OpenCV 5), `MarkerDetector` (ArUco), `FaceBlurrer` (YuNet), and `VisionService` (capture, detection thread, MJPEG privacy preview).
- `cloud/CloudSync.java`: publishes the wait board to S3. `infra/turn-demo.yaml`: the hosted demo stack.
- `demo/DemoSource.java`: the scripted service.
- [`frontend/`](frontend/): the landing page, Floor, Shift flow, Wait and S3 guest pages. Maven packages them as the web root.

## Limits we measured (OpenCV 5, on a laptop)

- **Background subtraction (MOG2) forgets a person who sits still within about 4 s,** so Turn uses a person detector for "seated" instead.
- **NanoDet at the zoo's default 0.35 threshold called traffic cones "people".** Turn uses 0.5, which dropped them in testing.
- **Badges smaller than about 40 px in the image were missed when turned 45°.** Keep the camera close (about 2 m for a phone-screen badge) or print badges larger.
- **Zones are drawn in config, not in the UI.** One camera per Turn instance.
- **Staff without badges inside a table zone count as guests.** Zones go around tables only.
- **Dining time is one global average,** not per party size.

## Credits

- [OpenCV](https://opencv.org) 5.0.0 (Apache-2.0)
- YOLOX-S, NanoDet and YuNet from the [OpenCV model zoo](https://huggingface.co/opencv) (Apache-2.0 / MIT)
- Footage: Pexels video 16478422, Mixkit clips 4385 and 44025 (free licences)
- Front end design: Basu
- `vtest.avi` sample from the OpenCV package

MIT licensed, see [LICENSE](LICENSE).
