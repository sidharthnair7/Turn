# Turn

**Turn runs a restaurant floor from one camera.** It sees which tables need someone, sends the task to the right server, clears it when the server's badge shows up at the table, and shows guests an estimated wait time on their own phone. There's no face recognition, and no frame is ever saved.

Built in Java 25 with Spring Boot and **OpenCV 5**. Video never leaves the restaurant. **Amazon S3** serves the guest wait page, and a **CloudFormation** stack runs a hosted demo on **EC2**.

Team: Sid (vision, floor engine, AWS, evaluation) and Basu (front end design).

## For judges: start here

| | |
|---|---|
| **Hosted demo (EC2)** | https://d287za2gqkxahc.cloudfront.net/floor Press **Restart demo** for a scripted two-minute, eight-table shift. Open **Shift flow** in the top bar |
| **Guest wait page (S3)** | https://turn-wait-sid.s3.amazonaws.com/public/index.html. It's fed by Turn running the real dining clip on our machine; when that machine is off, the page says it's not updated |
| **Technical report** | [docs/REPORT.md](docs/REPORT.md): architecture, OpenCV 5, AWS, evaluation, failure cases, gaps answered, responsible use |
| **Architecture** | [docs/architecture.svg](docs/architecture.svg) |
| **Proof that the badge path works on real footage** | [REPORT §7.3](docs/REPORT.md) and [docs/img/badge-read.jpg](docs/img/badge-read.jpg) |
| **Evaluation data** | [docs/eval/](docs/eval/) |
| **AWS setup** | [docs/AWS.md](docs/AWS.md), [infra/turn-demo.yaml](infra/turn-demo.yaml) |

## Why

Shire ([shireintelligence.com](https://shireintelligence.com), YC Fall 2026) turns a restaurant's CCTV into a live floor map. Turn does that job too, and covers four gaps:

| Gap | What Turn does |
|---|---|
| Guests see nothing | **An estimated wait page for guests**, computed from the tables the camera sees |
| Built around CCTV and a POS | Runs on **one camera** (webcam, phone, or an existing CCTV stream over RTSP) with no POS |
| Labels staff from video | **Staff wear a printed badge.** Tables are tracked, not faces. No per-person speed scores, and faces are blurred |
| Guests have to catch someone's eye | **A table card** (HELP / CHECK) the guest flips, which the camera reads |

## What it does

**For the host (Shire's host view, from one camera):**
- **Floor map:** tables laid out where they sit, coloured Open, Seated, Needs attention, Dirty or Blocked, with the server's initials. Click a free table to block or unblock it.
- **Server rotation bar:** each server's tables, and **Next party** on whoever should get the next table (lightest load).
- **Host stand:**
  - counts of open, dirty and seated tables
  - a **waitlist** with walk-in notes ("birthday", "high chair"), a suggested table and server for each party, a quoted wait, and **Seat**
  - **reservations** with **Arrived**
- **Shift flow (`/flow`):**
  - covers, parties, tables turned, average turn time and the peak
  - a timeline and **Do this next** recommendations
  - live floor signals
  - idle seats and waiting covers, valued at your average check
  - server load as an operational view, never a ranking

**For servers:**
- **Table states:** Empty → Seated → (guests leave) Needs bussing → (a staff badge visits) Ready → Empty.
- **Alerts, routed to the table's server or the lightest-loaded one:**
  - Not greeted
  - Check in
  - Help requested and Waiting on check (the guest flipped a card)
  - Needs bussing

  They go amber, then red when overdue. **The next staff badge seen at the table clears them.**

**For guests:**
- An estimated wait for their party size on their phone, from anywhere (S3).
- On the restaurant Wi-Fi (`/wait`) they can also join the list, see their place, and get "Your table is ready" when the host seats them.

**For everyone:**
- **Privacy view:** a smooth camera preview (detection on its own thread, MJPEG). Every face YuNet finds is blurred, plus the head of every person the detector finds. The preview is fully blurred until the first detection finishes.
- **Staff PIN (optional):** with `--turn.staff-pin=1234`, the floor, Shift flow, camera and host actions need the PIN. Guests on the Wi-Fi only get the wait page.
- **Health:** `/api/health` reports status, the source, detection time, frames streamed and the last AWS sync.

## Where the camera reads badges

- `vision/MarkerDetector.java` runs OpenCV 5's `ArucoDetector` (`DICT_4X4_50`) on every detection frame, next to YOLOX.
- `VisionService.observe` gives each badge to the nearest person box around it, and to the table zone it's in.
- `FloorEngine` treats a badge at a seated table as a visit: alerts clear and that server owns the table.
- Badge IDs 0 to 9 are staff, mapped to names in `application.yml`. Table cards: HELP is 20 + table number, CHECK is 30 + table number. Print-ready PNGs are in [`markers/`](markers/).
- Keep the marker at least about 56 px wide in the camera image (measured in REPORT §7.2). That's roughly a 10 to 12 cm printed marker for a camera about 3 m away.

## Run it

You need **Java 25**. Commands below are for **PowerShell**:
- In cmd, use `mvnw` instead of `.\mvnw`, and drop the quotes around `-D...` arguments.
- On macOS or Linux, use `./mvnw`.

1. **Get OpenCV 5.0.0.** You need this for every mode, because Maven compiles against its Java bindings.
   1. Download `opencv-5.0.0-windows.exe` from https://github.com/opencv/opencv/releases/tag/5.0.0 and extract it. It's a self-extracting archive; 7-Zip also opens it on any OS.
   2. Copy these into a `lib/` folder in this repo:
      - `build/java/opencv-500.jar`
      - `build/java/x64/opencv_java500.dll`
      - `build/bin/opencv_videoio_ffmpeg500_64.dll` (to read video files)
   3. Install the jar for Maven:
      ```
      .\mvnw install:install-file "-Dfile=lib/opencv-500.jar" "-DgroupId=org.opencv" "-DartifactId=opencv" "-Dversion=5.0.0" "-Dpackaging=jar"
      ```
   - **macOS or Linux:** the jar from step 2 is enough for demo mode and the tests. Real vision needs OpenCV 5 built with Java for your OS (`libopencv_java500.so` or `.dylib` in `lib/`), or use the hosted demo.
2. **Get the models** from the OpenCV model zoo into `lib/models/` (only needed for real vision):
   - people, wide rooms: https://huggingface.co/opencv/object_detection_yolox/resolve/main/object_detection_yolox_2022nov.onnx
   - people, close cameras: https://huggingface.co/opencv/object_detection_nanodet/resolve/main/object_detection_nanodet_2022nov.onnx
   - faces, for the blur: https://huggingface.co/opencv/face_detection_yunet/resolve/main/face_detection_yunet_2023mar.onnx
3. **Run the demo shift** (no camera, no models):
   ```
   .\mvnw spring-boot:run
   ```
   Then open these:
   - http://localhost:8080/floor for the Floor
   - http://localhost:8080/flow for Shift flow
   - http://localhost:8080/wait for the guest page

### Real vision (`turn.source`, or a profile)

| Profile | Runs on | Command |
|---|---|---|
| `dining` (main) | A busy real dining room, Pexels video 16478422 (free licence), YOLOX-S, four table zones. Download the **1920×1080** file from https://www.pexels.com/video/16478422/ to `lib/samples/pexels-16478422.mp4` | `.\mvnw spring-boot:run "-Dspring-boot.run.profiles=dining" "-Dspring-boot.run.arguments=--server.port=8097"` |
| `dining,badgetest` | The same footage with a badge and a HELP card drawn in at Booth 2. Build the clip first (see "Test and evaluate") | `.\mvnw spring-boot:run "-Dspring-boot.run.profiles=dining,badgetest"` |
| `webcam` | Your webcam, one table covering the picture. Sit in view, then hold `markers/badge_2.png` up on a phone | `.\mvnw spring-boot:run "-Dspring-boot.run.profiles=webcam"` |
| `restaurant` | Mixkit clip 44025 (couple eating). Download https://assets.mixkit.co/videos/44025/44025-720.mp4 to `lib/samples/mixkit-44025-couple-eating.mp4` | `.\mvnw spring-boot:run "-Dspring-boot.run.profiles=restaurant"` |
| `clip` | OpenCV's `vtest.avi`, from `sources/samples/data/` in the OpenCV package, copied to `lib/samples/` | `.\mvnw spring-boot:run "-Dspring-boot.run.profiles=clip"` |

- **Other sources:**
  - Your own camera: `--turn.source=camera:0`.
  - A CCTV recorder: `--turn.source=rtsp://user:pass@host/stream`.
  - A clip: `--turn.source=file:path.mp4`.
- **Set up a camera:**
  1. Take one frame from http://localhost:8080/camera.jpg.
  2. Read each table's rectangle in pixels (any image editor shows coordinates).
  3. Put it in `turn.tables` in `application.yml`.

  It's about 10 minutes per camera.

### AWS (optional)

- **Publish to S3:** pass `--turn.aws.bucket=<bucket>`. Turn then publishes the anonymous wait board and the guest page to S3 every 10 s. With no bucket, everything runs locally.
- **Hosted demo:**
  1. Run `.\mvnw package -DskipTests`.
  2. Upload `target/turn.jar` to the bucket's `releases/` folder.
  3. Create a stack from [infra/turn-demo.yaml](infra/turn-demo.yaml).

  Steps are in [docs/AWS.md](docs/AWS.md).

### Test and evaluate

`.\mvnw test` runs 24 tests:
- the floor engine (15)
- the vision-to-table glue: zones, badges, cards (4)
- the staff PIN (3)
- the cloud board (1)
- app startup (1)

The evaluation tools need the classpath first:
```
.\mvnw dependency:build-classpath "-Dmdep.outputFile=target/cp.txt"
```
Then run any of them like this (on macOS and Linux, use `:` instead of `;`):
```
java --enable-native-access=ALL-UNNAMED -cp "target/classes;$(Get-Content target/cp.txt)" turn.turn.eval.OccupancyEval lib/samples/pexels-16478422.mp4 lib/eval/occupancy "Counter 1=1;Booth 2=1;Booth 3=1;Booth 4=1;Empty seats=0;Empty aisle=0"
```

| Tool | What it measures |
|---|---|
| `Evaluate <clip> <outDir> 10 40` | People per frame and time per frame, NanoDet vs YOLOX |
| `OccupancyEval <clip> <outDir> <labels>` | Per-table occupancy against hand labels |
| `EngineBench <clip> 30` | OpenCV 5's classic vs new dnn engine |
| `BadgeEval <clip> docs/eval/badges.csv 40` | Badge reading by size, angle, light and blur |
| `BadgeClip <clip> lib/samples/badge-test.avi 54` | Builds the end-to-end badge test clip |

Results are in [docs/eval/](docs/eval/) and REPORT §7.

## How it works

```
camera / clip / RTSP → OpenCV 5:  YOLOX-S or NanoDet (dnn) finds people  +  ArucoDetector finds badges and cards
                     → per table:  guests in zone, staff badges in zone, cards up?    (VisionService.observe)
                     → FloorEngine: states, alerts, rotation, waitlist, reservations, estimates, shift flow
                     → /floor, /flow, /wait pages  and  CloudSync → S3 (guest wait board, shift history)
demo script ─────────────────────────────┘ (same engine, no camera)
```

## Limits we measured (OpenCV 5, Ryzen 7 3700X desktop CPU, no GPU)

- **A diner hidden behind a counter is missed.** YOLOX found the Booth 4 diner in 6% of frames. Mount the camera high.
- **Badges need about 56 px** in the image to survive a 60° turn or walking blur. Flat badges read at 24 px.
- **MOG2 background subtraction forgets a seated person within about 4 s,** so Turn uses a person detector.
- **NanoDet at 0.35 called traffic cones people,** so Turn runs NanoDet at 0.5 and YOLOX-S at 0.35.
- **Staff without badges inside a table zone count as guests.** Zones go around tables only.
- **Zones are drawn in config,** and there's one camera per Turn instance.
- **Laptops will be slower than our desktop.** NanoDet is about 4 times faster than YOLOX-S.

## Credits

- [OpenCV](https://opencv.org) 5.0.0 (Apache-2.0)
- YOLOX-S, NanoDet and YuNet from the [OpenCV model zoo](https://huggingface.co/opencv) (Apache-2.0 / MIT)
- Footage: Pexels video 16478422, Mixkit clips 4385 and 44025 (free licences), and `vtest.avi` from the OpenCV package
- Front end design: Basu

MIT licensed, see [LICENSE](LICENSE).
