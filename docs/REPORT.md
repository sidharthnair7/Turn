# Turn: technical report

Turn runs a restaurant floor from one camera. It keeps every table live, routes each task to the right server, and clears the alert when a server actually walks over. It gives the host a floor map, a server rotation and a waitlist, gives the owner a Shift Flow view, and shows guests an honest wait time on their own phone. Turn is built in Java 25 with OpenCV 5. Amazon S3 serves the guest wait board, and a CloudFormation stack runs a hosted demo on EC2.

![Architecture](architecture.svg)

**Try it**
- Hosted demo (EC2): see the `DemoUrl` output of the stack in [AWS.md](AWS.md). Press **Restart demo** for a scripted two-minute shift.
- Guest wait page (S3), live from the restaurant laptop: https://turn-wait-sid.s3.amazonaws.com/public/index.html
- Run it yourself: [README](../README.md).

## 1. Problem

In a small, busy restaurant the host and servers can't watch every table. A table that sits ten minutes without a greeting, or waits five minutes for the check, is a guest who doesn't come back. Guests at the door get a guess ("about 20 minutes") that is often wrong.

Shire (YC Fall 2026) solves the staff side: it turns a restaurant's CCTV into a live floor map for the host, routes tables to servers and reports on the shift. Turn does the same job and goes after three gaps:

| Gap in Shire's approach | What Turn does instead |
|---|---|
| Guests see nothing | A **live wait page** on the guest's phone, served from S3, computed from what the tables are actually doing |
| Needs a CCTV system and a POS integration | **One ordinary camera** and no POS. Table state comes from vision; seating comes from the host stand |
| Rates waiters from video ("fastest flow", "needs support") | **Printed badges, not faces.** No face recognition, no per-person speed labels, faces blurred in every view. Insights are per table and per alert type |
| Guests must catch someone's eye | **Table cards** (HELP / CHECK) the guest flips, which the camera reads |

## 2. Users

| User | What they get |
|---|---|
| Host | **Floor map** coloured by state, with server initials. **Server rotation** with "Next party". **Waitlist** with walk-in notes, a suggested table and server for each party, a quoted wait and one-tap Seat. **Reservations** with an Arrived button. **Block a table.** Double-seating is refused |
| Server | Alerts for their tables (Not greeted, Check in, Help, Check, Needs bussing), which clear by themselves when their badge visits the table |
| Owner | **Shift Flow**: covers, parties, tables turned, average turn time, peak, a timeline, the next best actions, live floor signals, idle seats valued at the average check, server load as an operational view |
| Guest | A live wait page on their phone from anywhere (S3). On the restaurant Wi-Fi they can join the list and see their place, and the page says when their table is ready |

## 3. Turn vs Shire, feature by feature

From Shire's public demo video. Shire's column shows what's visible in that video, not every feature Shire has.

| Shire feature | Turn |
|---|---|
| Floor map with tables by shape, coloured Open / Seated / Dirty / Blocked, server initials | Yes. Tables are laid out from their camera zones, shaped by seats. Click a free table to block it |
| Server bar with each server's tables and "Next party" | Yes. Next party goes to the lightest-loaded server |
| Waitlist with suggested table, "fits N, server next", quoted time, notes like "birthday", Seat | Yes. Each party gets a different suggested table. Tables aren't offered to a party that would waste more than two seats |
| Reservations tab, Arrived | Yes. Arrived moves the party to the front and seats them if a table fits |
| Add walk-in | Yes, with a note |
| Counts: open, dirty, seated | Yes, at the host stand and on the map legend |
| CCTV-driven seated and dirty detection | Yes. People detection with OpenCV 5 dnn; guests leaving makes the table Dirty; a badge visit makes it Ready |
| "Check-ready" from the camera | A guest flips a CHECK card, read by ArUco. It's simpler and more reliable than guessing from posture |
| Shift Flow: covers, parties, turned, avg turn, peak hour, timeline | Yes |
| Recommendations ("Seat the Rivera party at T4", "Recover two dirty tables") | Yes. "Do this next" is worked out from the live floor every two seconds |
| Revenue opportunity (dollars at risk) | Yes, as idle seats and waiting covers times a configured average check. It's labelled an estimate. No POS needed |
| Waiter load | Yes, as tables per server, explicitly not a ranking |
| Menu pricing guidance from POS data | **No, on purpose.** It needs a POS integration, which is the barrier Turn removes |
| Multi-camera NVR grid | Not yet. One camera per Turn instance (see Limitations) |
| **Guest-facing wait page** | Turn only |
| **Staff identity without faces** (badges) | Turn only |
| **Help / Check cards** | Turn only |
| **Faces blurred in every view, no frames stored** | Turn only |

## 4. Architecture

| Component | Role |
|---|---|
| `vision/VisionService` | Reads frames (webcam, CCTV stream or a file). Hands a frame to a **detection thread** every 200 ms; draws every frame with the latest detections and streams it as **MJPEG** at the camera's frame rate |
| `vision/YoloxDetector`, `PersonDetector` | People detection with OpenCV 5 `dnn`: YOLOX-S (default for wide rooms) or NanoDet (fast, close cameras). Chosen by `PeopleDetector.forModel` from the model file |
| `vision/MarkerDetector` | OpenCV 5 `objdetect.ArucoDetector`, `DICT_4X4_50`. IDs 0 to 9 are staff badges, 20+n is HELP for table n, 30+n is CHECK |
| `vision/FaceBlurrer` | OpenCV 5 `FaceDetectorYN` (YuNet). Works out blur regions on the detection thread; if no face is found inside a person box, the top 30% of the box is blurred instead, so a missed face never shows |
| `floor/FloorEngine` | Table state machine (Empty, Seated, Needs bussing, Ready, plus Blocked), alerts and routing, rotation, waitlist, reservations, wait simulation, Shift Flow |
| `floor/ShiftStats` | Shift totals, the timeline and plain-language tips |
| `web/*` | Spring MVC: `/floor`, `/flow`, `/wait`, JSON APIs, the MJPEG camera stream, `/api/health` |
| `cloud/CloudSync` | AWS SDK v2. Every 10 s writes the wait board and the shift summary to S3; uploads the guest page and its stylesheet at start |
| `demo/DemoSource` | A scripted two-minute, eight-table service that drives the same engine without a camera, labelled DEMO everywhere |
| `infra/turn-demo.yaml` | CloudFormation for the hosted demo on EC2 |

**Data flow.**
1. A camera frame comes in.
2. Detection finds people boxes and markers.
3. Each person's box centre is placed in a table zone.
4. A person inside a slightly grown box around a badge is staff. Everyone else is a guest.
5. That gives one `TableObservation(guests, staff badges, help, check)` per table.
6. `FloorEngine.tick` updates the state.
7. A snapshot goes to the web pages and to CloudSync.

**What counts as a visit.** A badge inside a table's zone, or a person carrying a badge there, counts as a staff visit. The first badge to reach a newly seated table owns it. Unowned alerts go to the server with the lightest load.

**Wait estimate.**
- For a new party of size *k*, Turn simulates the host seating everyone ahead in order. Each party takes the soonest table that fits.
- Each table's time comes from how long it has been seated, plus an average dining time learned from finished tables.
- A table "fits" if it has enough seats and at most two spare. A couple isn't put at a six-top while sixes wait, unless the restaurant has nothing smaller.
- Blocked tables are left out.

## 5. OpenCV 5 implementation

All vision runs in Java through OpenCV 5.0.0's official Java bindings (`opencv-500.jar` + `opencv_java500.dll`), with models from the OpenCV model zoo.

| OpenCV 5 module | Used for |
|---|---|
| `dnn` (`Dnn.readNet`, `blobFromImage`, `NMSBoxes`) | YOLOX-S and NanoDet ONNX models. Pre- and post-processing ported to Java from the zoo's Python demos |
| `objdetect` (`ArucoDetector`, `FaceDetectorYN`) | Staff badges, table cards, faces |
| `videoio` (`VideoCapture`, FFmpeg backend) | Camera and recorded clips, looping |
| `imgproc`, `imgcodecs` | Letterboxing, blur, drawing zones, JPEG encoding for the MJPEG stream |

OpenCV 5 differences we hit and handled:
- NanoDet's six outputs come back in a different order than the zoo demo assumes, so outputs are matched by size, not position.
- `Mat.size()` fails on 3-D blobs, so `total()` and `dims()` are used.
- `HOGDescriptor` is no longer in the Java bindings, so people detection uses `dnn` only.
- Java 25 needs `--enable-native-access=ALL-UNNAMED` for the native library.

Spikes before building:
1. MOG2 background subtraction forgets a seated person within about 4 s, so it can't tell a seated table from an empty one. Rejected.
2. NanoDet at score 0.35 detected traffic cones as people; 0.5 removed them.
3. ArUco badges under about 40 px wide are missed when tilted 45°. Badges are printed large (see `markers/`).
4. NanoDet missed most seated diners in wide dining-room shots, which led to adding YOLOX-S.
5. Detection on the capture thread made the preview a slideshow (about 2.5 frames a second as the page polled). Moving detection to its own thread and streaming MJPEG gives about **22 frames a second** on the 1080p dining clip, measured at the stream.

## 6. AWS deployment

| Piece | Service | Why |
|---|---|---|
| Public guest wait page + live board | **Amazon S3**: `public/index.html`, `turn.css`, `theme.js`, `wait.json` | The restaurant laptop sits behind the restaurant's router, so guests' phones can't reach it. S3 gives an always-up public page without opening a port |
| Private shift history | **S3** `shifts/DATE.json` | The owner keeps a record after the laptop is closed |
| Hosted demo for anyone (and judges) | **EC2** (t3.micro, Amazon Linux 2023, Corretto 25), launched by **CloudFormation** `infra/turn-demo.yaml` | One repeatable stack. Turn runs in demo mode under systemd and publishes its own board to `public/demo/` |
| Least privilege | **IAM** | The laptop's IAM user can only `PutObject` to the bucket. The EC2 role can only read `releases/*` and write `public/demo/*` and `shifts/demo/*` |
| Access | **Systems Manager Session Manager** | No SSH port and no key pair. The security group opens only port 80 |

**Privacy line.** Only counts and estimates go to the cloud. No frames, no faces, no guest names (the local waitlist has names; the cloud board does not).

**Observability.**
- `/api/health` reports the source, uptime, last detection time, preview frame count, AWS sync status and the last error.
- CloudFormation waits for that health check before it marks the stack complete.
- The guest page shows "Not updated for a while" once the board is over 60 s old.

**Failure behaviour.** If AWS is unreachable, the local floor keeps working. CloudSync logs once and retries every tick. The EC2 service restarts on failure.

**Repeatability.**
- `mvnw package` builds `target/turn.jar`.
- Upload it to `releases/`, then create the stack from `infra/turn-demo.yaml`.
- Steps are in [AWS.md](AWS.md).

**Cost.** The demo is one t3.micro plus S3 requests. Delete the stack when you're done.

## 7. Evaluation

### 7.1 Detection on real restaurant footage

The tool is `turn.turn.eval.Evaluate`. It runs both detectors on the same sampled frames (every 10th frame) and writes a CSV plus annotated frames for checking by hand.

- Machine: AMD Ryzen 7 3700X, CPU only, OpenCV 5.0.0 default backend.
- Raw per-frame results: [`docs/eval/`](eval/).

| Clip (free licence) | View | Frames | NanoDet people/frame | YOLOX people/frame | NanoDet ms p50 / p95 | YOLOX ms p50 / p95 |
|---|---|---|---|---|---|---|
| Pexels 16478422, busy sushi restaurant, 1920×1080 | wide, eye level | 34 | 1.4 | 8.1 | 33 / 40 | 130 / 161 |
| Mixkit 4385, restaurant from above, 1280×720 | high angle | 36 | 0.3 | 4.4 | 31 / 42 | 132 / 153 |
| Mixkit 44025, couple eating, 1280×720 | close-up | 35 | 0.9 | 2.0 | 30 / 40 | 134 / 158 |

**Hand check.** One reviewer checked annotated frames. In the busy background, people mostly hidden behind the counter are hard to count, so those numbers are approximate.

| Frame | People clearly visible | YOLOX correct | YOLOX false | NanoDet correct |
|---|---|---|---|---|
| Pexels 16478422, frame 0 | about 12 (5 near the camera) | 5 | 0 | 0 |
| Pexels 16478422, frame 180 | about 14 (6 chefs, 8 diners) | 8 | 1 (same diner boxed twice) | 2 |
| Mixkit 4385, frame 120 | 5 | 4 | 0 | 1 |
| Mixkit 44025, frame 120 | 2 | 2 | 0 | 2 |

**What this means.**
- On wide dining-room views, NanoDet finds too few people for table tracking. YOLOX-S finds every diner near the camera.
- At about 130 ms a frame, YOLOX keeps up with Turn's 200 ms check interval on a laptop CPU. Table states change over seconds, so even 2 checks a second would be enough.
- NanoDet stays the choice for close-up cameras, where it's about four times faster.
- Table state only needs "is anyone at this table", so missing one person at a full table doesn't change it.

### 7.2 Floor logic

There are 14 unit tests in `FloorEngineTest`, driven by a fake clock, so they're exact and repeatable. Each one checks one rule:

- Guests seen for 2 s are seated.
- An unvisited table raises Not greeted. A badge visit clears it and claims the table.
- Shift insights record greet time and answered alerts.
- A CHECK card raises one alert; a visit clears it.
- Guests leaving makes the table Needs bussing. A badge visit makes it Ready.
- Double-seating is refused.
- Seat picks the smallest free table that fits.
- A small party ahead doesn't block a bigger table that is free.
- The wait is 0 while a table is free and grows when the room is full.
- A blocked table is left out of seating and wait times, and comes back when unblocked.
- A table with guests can't be blocked.
- An arriving reservation goes first and is seated when a table fits. Walk-in notes stay with the party.
- Each waiting party is offered a different free table and a server.
- Shift Flow recommends seating and bussing, and counts idle seats and their value.

`CloudSyncTest` checks that the public board has an estimate for each party size and the counts, and no guest names.

### 7.3 End to end on real footage

With the `dining` profile (Pexels 16478422, YOLOX, four table zones), checked in the browser:
- Counter 1, Booth 2 and Booth 3 go Seated from the footage.
- About 12 s later, Not greeted alerts appear on their own, routed to the lightest-loaded server.
- All faces are blurred.
- The preview streams at about 22 frames a second.
- The S3 guest page updated every 10 s from this run, and the private `shifts/` object returned 403 to a public request.

### 7.4 Failure cases (seen, not hypothetical)

| Failure | Where seen | Effect | Mitigation |
|---|---|---|---|
| Staff without badges count as guests | Pexels 16478422: chefs behind the counter | A prep area inside a table zone would look occupied | Zones are drawn around tables only; floor staff wear badges |
| The same person boxed twice | Pexels frame 180 | Guest count +1. Table state is unaffected (seated means guests ≥ 1) | NMS at 0.5; a stricter IoU is possible |
| People hidden behind a counter or another diner are missed | Pexels, back rows | A table that's only partly visible can read as empty | Mount the camera high; one camera per section |
| Tilted or small badges are missed | Spike 3, under about 40 px at 45° | A visit isn't counted, so the alert stays up (the safe direction) | Large badges worn on the chest |
| Blur regions lag a moving person by up to one detection (about 0.3 s) | Preview on the dining clip | A face near the edge of its box could show for a frame | Blur regions are grown 35% around faces and 10% around heads |
| A still person is lost by background subtraction | Spike 1 | Not used; detection is per-frame dnn | None needed |
| NanoDet misses seated diners in high or wide views | Mixkit 4385, 0.3 people a frame | Tables read empty | Use YOLOX-S for these views |

## 8. Gaps and answers

| Question | Answer |
|---|---|
| What if a server forgets their badge? | The alert stays up, which is the safe direction. Nothing gets marked done that wasn't. The host still sees the last-visit time. Badges are a printed card on a lanyard; a spare lives at the host stand |
| Why not face recognition for staff? | It's a surveillance risk and regulated in many places. A badge is opt-in, comes off on a break, and can't identify anyone outside work |
| Do bussers and chefs count as guests? | Only inside a table zone, and zones are drawn around tables. Bussers wear badges; a badge visit is what clears Needs bussing |
| Why no menu pricing like Shire? | It needs POS data, which is the barrier we remove. Turn does the floor without a POS |
| One camera can't see a whole restaurant | One wide camera covers a small dining room, our target. Multi-camera is next: each camera becomes another source feeding the same engine |
| Is it fair to staff? | Turn never scores a person. Insights are per table and per alert type. Server load counts tables, not speed |
| Is filming guests OK? | Faces are blurred in everything Turn shows; no frames are stored or sent. A deployment should post a sign at the door: a camera counts table occupancy and records nothing |
| What if the internet is down? | The floor runs locally and keeps working. The guest page shows it's stale until the connection is back |
| Does it need a GPU? | No. Everything runs on a laptop CPU |
| Dollar values without a POS? | They're estimates from a configured average check, and labelled that way. They show size, not accounting |

## 9. Limitations

- Zones are drawn by hand per camera, in `application.yml`.
- One camera per Turn instance.
- Busy rooms need a CPU that can run YOLOX a few times a second.
- The S3 guest page is read-only. Joining the list happens on the restaurant's local `/wait` or at the host stand.
- Reservations live in memory for the current day. There's no database yet.
- The evaluation is small: three free-licence clips and a hand check by one reviewer. It's not a labelled benchmark.
- Not yet tested with a live restaurant's CCTV.
- The hosted demo runs the scripted service, not real vision. OpenCV 5's Windows build runs the vision on the restaurant laptop.

## 10. Responsible use

- **No face recognition.** Faces are only found so they can be blurred. Staff are known by a badge they choose to wear.
- **No frames are stored** or sent anywhere. Only counts and estimates go to the cloud.
- **No per-person scoring.** Insights are per table and per alert type, so the tool helps a team and doesn't surveil a worker.
- **Guests are told.** Post a sign at the door.
- **Least privilege on AWS.** Write-only IAM user, a narrow EC2 role, no SSH, and public read only on `public/*`.
- **Footage** is free-licence stock (Pexels, Mixkit) and OpenCV's own sample video. No real CCTV of real customers was used.

## 11. Build, run, test

See the [README](../README.md). In short:

- Java 25, the Maven wrapper, the OpenCV 5.0.0 Windows package in `lib/`, and zoo models in `lib/models/`.
- `mvnw test` runs the tests.
- `mvnw spring-boot:run` runs the demo shift.
- `mvnw spring-boot:run -Dspring-boot.run.profiles=dining` runs the real footage.
- `mvnw package` builds `target/turn.jar` for the hosted demo.

**Pinned versions**

| Dependency | Version |
|---|---|
| Spring Boot | 4.1.2 |
| OpenCV | 5.0.0 |
| AWS SDK for Java v2 | 2.57.4 |
| Java | 25 |

## 12. Credits

- **OpenCV 5.0.0** and the **OpenCV model zoo**: YOLOX-S, NanoDet, YuNet.
- **Footage:**
  - Pexels video 16478422 (Pexels licence)
  - Mixkit clips 4385 and 44025 (Mixkit licence)
  - OpenCV's `vtest.avi`
- **Front end design:** Basu.
