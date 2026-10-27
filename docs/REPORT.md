# Turn: technical report

Turn runs a restaurant floor from one camera. It sees which tables need someone, routes the task to the right server, clears the alert when a server actually walks over, and shows guests an honest wait time on their own phone. It is built in Java 25 with OpenCV 5 and publishes the guest wait board through Amazon S3.

![Architecture](architecture.svg)

## 1. Problem

In a small, busy restaurant the host and servers can't watch every table. A table that sits ten minutes without a greeting, or waits five minutes for the check, is a guest who doesn't come back. Guests at the door get a guess ("about 20 minutes") that is often wrong.

Products like Shire (YC Fall 2026) solve the staff side with CCTV and a POS integration. Turn targets the restaurant that has neither, and adds the guest side:

- one ordinary camera, no POS
- tables are tracked, not people: no face recognition, no per-employee scoring, faces blurred in every view
- staff are recognized by a **printed badge** (an ArUco marker), not by their face
- guests can flip a printed **HELP** or **CHECK** card that the camera reads
- guests see a **live wait estimate** computed from what the tables are actually doing

## 2. Users

| User | What they get |
|---|---|
| Server | Alerts for their tables (Not greeted, Check in, Help, Check, Needs bussing), which clear by themselves when their badge visits the table |
| Host | Waitlist, a **Seat** button that picks the smallest free table that fits, and a block on double-seating |
| Owner | Shift insights: covers, average time to first visit, response time per alert type, slowest table. Per table, never per person |
| Guest | A wait page on their phone, live, from anywhere (served by S3) |

## 3. Architecture

| Component | Role |
|---|---|
| `vision/VisionService` | Reads frames (webcam, CCTV stream, or a file), runs detection every 200 ms (up to five checks a second), turns detections into per-table observations |
| `vision/YoloxDetector`, `PersonDetector` | People detection with OpenCV 5 `dnn`: YOLOX-S (default for wide rooms) or NanoDet (fast, close-up cameras). Chosen by `PeopleDetector.forModel` from the model file |
| `vision/MarkerDetector` | OpenCV 5 `objdetect.ArucoDetector`, `DICT_4X4_50`. IDs 0 to 9 are staff badges, 20+n is HELP for table n, 30+n is CHECK |
| `vision/FaceBlurrer` | OpenCV 5 `FaceDetectorYN` (YuNet). Blurs each face. If no face is found inside a person box, blurs the top 30% of the box instead, so a missed face never shows |
| `floor/FloorEngine` | The state machine (Empty → Seated → Needs bussing → Ready → Empty), alerts and routing, waitlist and wait simulation |
| `floor/ShiftStats` | Shift insights |
| `web/*` | Spring MVC: `/floor` (staff), `/wait` (guest, local), JSON APIs, the blurred camera preview |
| `cloud/CloudSync` | AWS SDK v2. Every 10 s writes `public/wait.json` and `shifts/DATE.json` to S3; uploads the guest page `public/index.html` at start |
| `demo/DemoSource` | A scripted two-minute service that drives the same engine without a camera, labelled DEMO everywhere |

**Data flow.** Camera frame → people boxes + markers → each person's box centre is placed in a table zone → a person inside a grown box around a badge is staff, everyone else is a guest → `TableObservation(guests, staff badges, help, check)` per table → `FloorEngine.tick` → snapshot → web pages and CloudSync.

**Why a camera sees "a visit".** A badge inside a table's zone, or a person carrying a badge there, counts as a staff visit. The first badge to reach a newly seated table owns it. Unowned alerts go to the server with the lightest load.

**Wait estimate.** For a new party of size *k*, Turn simulates the host seating everyone ahead in order, each at the soonest-free table that fits, using each table's time seated and an average dining time learned from finished tables. A table that is free right now gives 0 minutes. A party no table fits gets "ask the host".

## 4. OpenCV 5 implementation

All vision runs in Java through OpenCV 5.0.0's official Java bindings (`opencv-500.jar` + `opencv_java500.dll`), with models from the OpenCV model zoo.

| OpenCV 5 module | Used for |
|---|---|
| `dnn` (`Dnn.readNet`, `blobFromImage`, `NMSBoxes`) | YOLOX-S and NanoDet ONNX models. Pre- and post-processing ported to Java from the zoo's Python demos |
| `objdetect` (`ArucoDetector`, `FaceDetectorYN`) | Staff badges, table cards, faces |
| `videoio` (`VideoCapture`, FFmpeg backend) | Camera and recorded clips, looping |
| `imgproc`, `imgcodecs` | Letterboxing, blur, drawing zones, JPEG preview |

OpenCV 5 differences we hit and handled (details in `lib/SPIKES.md` locally):

- NanoDet's six outputs come back in a different order than the zoo demo assumes, so outputs are matched by size, not by position.
- `Mat.size()` fails on 3-D blobs, so `total()` and `dims()` are used.
- `HOGDescriptor` is no longer in the Java bindings, so people detection uses `dnn` only.
- Java 25 needs `--enable-native-access=ALL-UNNAMED` for the native library.

Spikes before building:

1. MOG2 background subtraction forgets a still seated person within about 4 s, so it can't tell a seated table from an empty one. Rejected.
2. NanoDet at score 0.35 detected traffic cones as people; 0.5 removed them.
3. ArUco badges under about 40 px wide are missed when tilted 45°. Badges are printed large (see `markers/`).
4. NanoDet missed most seated diners in wide dining-room shots. This led to adding YOLOX-S.

## 5. AWS deployment

**Why AWS.** The Turn laptop sits behind the restaurant's router. Guests' phones are on mobile data or another Wi-Fi, so they can't reach it. S3 gives the restaurant a public, always-up guest page without opening a port or running a server.

**What runs where.**

- In the restaurant: the Spring Boot app, all vision, all frames.
- In AWS: one S3 bucket.
  - `public/index.html`: the guest page, uploaded by Turn on start.
  - `public/wait.json`: written every 10 s. It holds an estimate per party size, how many parties are waiting, and how many tables are free.
  - `shifts/YYYY-MM-DD.json`: that day's shift insights, kept private for the owner.

**Privacy line.** Only counts and estimates go to the cloud. No frames, no faces, no guest names (the local waitlist has names; the cloud board does not).

**Failure behaviour.** If AWS is unreachable, the local Floor keeps working, CloudSync logs once and retries each tick, and the guest page shows "Not updated for a while" once the board is over 60 s old.

Setup steps are in [AWS.md](AWS.md).

## 6. Evaluation

### 6.1 Detection on real restaurant footage

The tool is `turn.turn.eval.Evaluate`. It runs both detectors on the same sampled frames (every 10th frame) and writes a CSV plus annotated frames for checking by hand.

- Machine: AMD Ryzen 7 3700X, CPU only, OpenCV 5.0.0 default backend.
- Raw per-frame results: [`docs/eval/`](eval/).

| Clip (free licence) | View | Frames | NanoDet people/frame | YOLOX people/frame | NanoDet ms p50 / p95 | YOLOX ms p50 / p95 |
|---|---|---|---|---|---|---|
| Pexels 16478422, busy sushi restaurant, 1920×1080 | wide, eye level | 34 | 1.4 | 8.1 | 33 / 40 | 130 / 161 |
| Mixkit 4385, restaurant from above, 1280×720 | high angle | 36 | 0.3 | 4.4 | 31 / 42 | 132 / 153 |
| Mixkit 44025, couple eating, 1280×720 | close-up | 35 | 0.9 | 2.0 | 30 / 40 | 134 / 158 |

**Hand check.** One reviewer checked annotated frames. In the busy background, people who are mostly hidden behind the counter are hard to count, so those numbers are approximate.

| Frame | People clearly visible | YOLOX correct | YOLOX false | NanoDet correct |
|---|---|---|---|---|
| Pexels 16478422, frame 0 | about 12 (5 near the camera) | 5 | 0 | 0 |
| Pexels 16478422, frame 180 | about 14 (6 chefs, 8 diners) | 8 | 1 (same diner boxed twice) | 2 |
| Mixkit 4385, frame 120 | 5 | 4 | 0 | 1 |
| Mixkit 44025, frame 120 | 2 | 2 | 0 | 2 |

**What this means.**

- On wide dining-room views, NanoDet finds too few people for table tracking; YOLOX-S finds every diner near the camera.
- At about 130 ms per frame, YOLOX keeps up with Turn's 200 ms check interval on a laptop CPU (table states change over seconds, so even 2 checks a second would be enough).
- NanoDet stays the choice for close-up cameras, where it is about four times faster.

### 6.2 Floor logic

There are 9 unit tests (`FloorEngineTest`), driven by a fake clock, so they are exact and repeatable. Each one tests one of these rules:

- Guests seen for 2 s are seated.
- An unvisited table raises Not greeted. A badge visit clears it and claims the table.
- Shift insights record the greet time (11 s in the test) and answered alerts.
- A CHECK card raises one alert, and a visit clears it.
- When guests leave, the table goes to Needs bussing. A badge visit makes it Ready.
- Double-seating is blocked.
- Seat picks the smallest free table that fits.
- A small party ahead doesn't block a bigger table that is free.
- The wait is 0 while a table is free and grows when the room is full.

`CloudSyncTest` checks the public board: an estimate for each party size, the counts, and no guest names.

### 6.3 End to end on real footage

With the `dining` profile (Pexels 16478422, YOLOX, four table zones), these were checked in the browser:

- Counter 1, Booth 2 and Booth 3 go Seated from the footage.
- About 12 s later, Not greeted alerts appear on their own and are routed to the lightest-loaded server.
- All faces are blurred in the preview.

### 6.4 Failure cases (seen, not hypothetical)

| Failure | Where seen | Effect | Mitigation |
|---|---|---|---|
| Staff without badges count as guests | Pexels 16478422: chefs behind the counter | A prep zone inside a table zone would look occupied | Draw zones around tables only; staff wear badges |
| The same person boxed twice | Pexels frame 180 | Guest count +1. Table state is unaffected (seated is guests ≥ 1) | NMS at 0.5; a stricter IoU is possible |
| People hidden behind a counter or another diner are missed | Pexels, back rows | A table that is only partly visible can read as empty | Mount the camera high, one camera per section |
| Tilted or small badges are missed | Spike 3, under about 40 px at 45° | A visit isn't counted, so the alert stays up (the safe direction) | Large printed badges worn on the chest |
| A still person is lost by background subtraction | Spike 1 | Not used: detection is per-frame dnn | — |
| NanoDet misses seated diners in high or wide views | Mixkit 4385: 0.3 people per frame | Tables read empty | Use YOLOX-S for these views |
| Close-up crops are bigger than a zone | Mixkit 44025 | The centre point can land outside a zone | Zones are set per camera |

## 7. Limitations

- Zones are drawn by hand per camera, in `application.yml`.
- Only one camera per instance.
- Busy rooms need a CPU that can run YOLOX at a few checks a second.
- The guest page in S3 is read-only: joining the waitlist still happens on the restaurant's local `/wait` or at the host stand.
- The evaluation is small: three free-licence clips and a hand check by one reviewer. It is not a labelled benchmark.
- We have not tested with a live restaurant's CCTV.

## 8. Responsible use

- **No face recognition.** Faces are only found so they can be blurred. Staff are known by a badge they choose to wear, and they can take it off.
- **No frames are stored** or sent anywhere. Only counts and estimates go to the cloud.
- **No per-person scoring.** Insights are per table and per alert type, by design, so the tool helps a team and doesn't surveil one worker.
- **Guests are told.** A real deployment should post a sign at the door saying a camera counts table occupancy, nothing is recorded, and faces are blurred.
- **Footage used** is free-licence stock (Pexels, Mixkit) and OpenCV's own sample video. No real CCTV of real customers was used.

## 9. Build, run, test

See the [README](../README.md). In short:

- Java 25, Maven wrapper, OpenCV 5.0.0 Windows package in `lib/`, zoo models in `lib/models/`.
- `mvnw test` runs the tests.
- `mvnw spring-boot:run -Dspring-boot.run.profiles=dining` runs the real footage.
- Pinned versions:

  | Dependency | Version |
  |---|---|
  | Spring Boot | 4.1.2 |
  | OpenCV | 5.0.0 |
  | AWS SDK for Java v2 | 2.57.4 |
  | Java | 25 |

## 10. Credits

- **OpenCV 5.0.0** and the **OpenCV model zoo**: YOLOX-S, NanoDet, YuNet.
- **Footage**:
  - Pexels video 16478422 (Pexels licence)
  - Mixkit clips 4385 and 44025 (Mixkit licence)
  - OpenCV's `vtest.avi`
