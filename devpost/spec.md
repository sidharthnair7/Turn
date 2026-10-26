---
doc: spec
status: approved
---

# Turn — Technical Spec

> Technical choices were made by the agent at Sid's instruction (Oct 26, 2:33pm), on the stack he picked: Java + OpenCV 5. Choices were checked against throwaway spikes run on his laptop earlier the same day.

## How This Works, In Plain Language
A background thread reads frames from a camera or a video file. For each frame:
1. OpenCV 5 finds people with a small neural network.
2. OpenCV 5 finds printed square markers: staff badges and table cards.
3. Each person is assigned to a table zone. A person carrying a badge counts as staff.
4. Those counts go to the floor engine, a small state machine per table that decides states and alerts.

The web pages ask the server for the floor state every second and redraw it. Demo mode feeds the same engine from a script instead of the camera.

## The Core Journey Through the System
`VideoSource` → `Vision` (people + markers) → `TableObservation` per table → `FloorEngine.tick()` → `FloorSnapshot` → `GET /api/floor` → `floor.js` renders.

`/wait` calls `GET /api/wait` and `POST /api/waitlist`. The host's Seat calls `POST /api/seat`.

## Stack
- **Language and framework:** Java 25, Spring Boot 4.1 (web MVC), the starter Sid generated.
- **Vision:** OpenCV 5.0.0 Java bindings, installed locally as `org.opencv:opencv:5.0.0` from OpenCV's official Windows package.
  - The native DLLs live in `lib/`, which is gitignored.
  - **People:** OpenCV zoo NanoDet (`object_detection_nanodet_2022nov.onnx`, Apache-2.0) through `org.opencv.dnn`, person class only, score threshold 0.5 (spike: 0.35 flagged traffic cones).
  - **Markers:** `ArucoDetector`, `DICT_4X4_50`.
- **Front end:** plain HTML, CSS and JS in `frontend/` at the repo root, packaged by Maven as the static web root. No build step.

## Where It Runs and How Someone Tries It
- Locally on Windows: `mvnw spring-boot:run`, then open http://localhost:8080 (Floor) and http://localhost:8080/wait.
- Video source, set with `turn.source` in `application.properties`:
  - `demo`, the default: no camera needed
  - `camera:0`
  - `file:path/to/clip.mp4`
- Not deployed for Basics. The OpenCV competition entry adds an AWS piece later.

## Look and Feel
- Dark background `#0f1115`, cards `#1a1d24`.
- State colours:
  - Empty `#4b5563`
  - Seated `#22c55e`
  - Attention `#f59e0b`
  - Overdue `#ef4444`
  - Bussing `#3b82f6`
  - Ready `#a3e635`
- System UI font. Timers in tabular numerals.

## Components

### `vision`
- **`VisionService`:** owns the capture thread at about 5 checks per second, and keeps the latest blurred, annotated JPEG for the stream.
- **`PersonDetector`:** NanoDet pre- and post-processing, ported from the OpenCV zoo Python demo. The OpenCV 5 outputs are matched by size.
- **`MarkerDetector`:** ArUco IDs and centres.
- **`NativeLoader`:** loads `opencv_java500.dll` from `turn.opencv-dir`.

### `floor`
- **`FloorEngine`:** tables, servers, alerts, waitlist, dining-time average. It's thread-safe (synchronized), and `tick(observations, now)` is called by the vision or demo source.
- **`TableState`:** the enum EMPTY, SEATED, BUSSING, READY. Alerts are separate objects.
- **`WaitEstimator`:** a pure function from tables and waitlist to estimates.

### `demo`
- **`DemoSource`:** a timed script of observations covering every state and alert in about 2 minutes, looping.

### `web`
- **`FloorController`:** REST JSON.
- **`StreamController`:** MJPEG at `/stream.mjpg`. Blurred frames only. In demo it draws a schematic floor instead.

## Data Model (in memory)
- **`TableConfig`:** id, name, seats, zone `{x,y,w,h}`, helpMarker, checkMarker.
- **`Table`:** state, guests, seatedAt, lastStaffVisitAt, serverBadge, help flag, check flag, stateSince.
- **`Server`:** badgeId, name.
- **`Alert`:** id, tableId, type (NOT_GREETED, CHECK_IN, HELP, CHECK, BUSSING), raisedAt, severity, routedTo.
- **`Party`:** id, name, size, joinedAt.
- **Marker IDs:** staff badges 0 to 9; HELP card for table n = 20+n; CHECK card for table n = 30+n.

## File Structure
```
src/main/java/turn/turn/
  TurnApplication.java
  config/TurnProperties.java
  floor/        FloorEngine, Table, TableState, Alert, AlertType, Party, WaitEstimator, TableObservation, FloorSnapshot
  vision/       NativeLoader, PersonDetector, MarkerDetector, VisionService
  demo/         DemoSource
  web/          FloorController, StreamController, WebConfig
src/main/resources/
  application.yml, application-clip.yml
frontend/
  index.html, wait.html, css/turn.css, js/floor.js, js/wait.js
src/test/java/turn/turn/floor/FloorEngineTest.java
lib/ (gitignored): opencv-500.jar, *.dll, models/, badges/
```

## External Services and Dependencies
- **None at runtime.**
- **OpenCV 5:** the jar is installed to the local Maven repository. The DLLs and model are downloaded once; see README.

## Important Failure Modes
- **Background subtraction forgets still people within about 4 s (spike),** so it isn't used for "seated". The detector is used instead.
- **Small badges:** markers under about 40 px, turned 45°, were missed in the spike. Badges need to be big, or the camera within about 2 m.
- **A detector miss for one frame would flicker the state.** Debounced by the 2 s seat and 5 s leave times.
- **The camera fails to open:** the source reports "no camera" and the floor stays usable.

## What Was Simplified and Why
- **Fixed zones from config, not drawn in the UI:** saves time, and the zones rarely change.
- **One owner per table:** see the PRD.
- **Dining-time average is global, not per party size:** less data needed.

## Decisions and Open Issues
- **OpenCV 5 API differences found in the spikes:**
  - `getPerspectiveTransform` is now in `org.opencv.geometry.Geometry`.
  - `HOGDescriptor` is gone.
  - `Mat.size()` throws on 3-D Mats.
  - `org.opencv.objdetect.Dictionary` clashes with `java.util.Dictionary`.
