---
doc: prd
status: approved
---

# Turn — Product Requirements

> Decisions in this PRD were made by the agent at Sid's instruction ("I want you to do it! ... Do it", Oct 26, 2:33pm), working inside the approved scope.

## The Core Journey
1. The host opens **Floor** (`/`). They see every table as a card, an alert rail, server load, the waitlist, and a privacy-blurred camera view.
2. A party sits at a table. Within a couple of seconds the card turns **Seated** and starts a timer.
3. If no staff badge reaches that table in time, a **Not greeted** alert appears at the top of the rail, tagged with the table's server. If the table has no server yet, the alert suggests the least-loaded server.
4. A server wearing a badge walks to the table. The alert clears by itself, and that server becomes the table's owner if it had none. Their load goes up.
5. During the meal:
   - If nobody has visited for a while, **Check in** appears.
   - If the guest flips the table card to **HELP** or **CHECK**, the matching alert appears.
   - Each clears when a badge visits.
6. The guests leave. The card turns **Needs bussing**. A badge visit with no guests present makes it **Ready**, then **Empty**.
7. A guest at the door opens **Wait** (`/wait`, from a QR code). They see the live estimate, join the waitlist with name and party size, and watch their position.
8. On Floor, the host presses **Seat** on a waitlist party:
   - Turn suggests the best free table for that party size.
   - Turn warns if the chosen table is occupied (double-seating).

## Screens and Layout
- **Floor (`/`), host view, laptop or tablet in landscape:**
  - Header: "Turn", source badge (CAMERA / DEMO), clock.
  - Left two-thirds: table cards in a grid, then the camera view.
  - Right third:
    - alert rail (newest and most urgent first)
    - server load
    - waitlist with Seat buttons
- **Wait (`/wait`), guest view, phone portrait:**
  - One big number: "about 12 min", or "A table is free now".
  - A join form (name, party size).
  - After joining: "You're #2", updating live.
  - A line on privacy: "Turn counts tables, never faces."

## Look and Feel
- Calm and high-contrast, like a kitchen ticket rail, readable from across a room.
- Dark background. Table cards in clear state colours:
  - Empty: grey
  - Seated / OK: green
  - Needs attention: amber
  - Overdue: red
  - Bussing: blue
- Big numbers for timers, one font family, no dashboard clutter.
- Alerts read as short tasks: "Table 2 · Not greeted · 0:45 · Priya".

## Features and Behavior

### Table states
- **Empty → Seated:** guests are seen in the table zone for 2 s or more.
- **Seated → Needs bussing:** no guests for 5 s or more.
- **Needs bussing → Ready:** a staff badge visits while no guests are present.
- **Ready → Empty:** after 10 s, or straight to Seated if guests sit.

### Alerts (cleared by the next badge visit to that table)
- **Not greeted:** seated, and no badge visit within the greet time (demo 15 s, real 120 s).
- **Check in:** seated, and the last visit was longer ago than the check-in time (demo 40 s, real 10 min).
- **Help requested:** the table's HELP card is visible.
- **Waiting on check:** the table's CHECK card is visible.
- **Needs bussing:** shown while the table is in that state.
- **Severity:** amber when raised, red after it has been open for 2x its threshold.

### Staff and load
- A server is a badge ID with a name (config).
- The first badge to visit a newly seated table becomes its server.
- Load = the number of active tables a server owns.
- Every routed alert names the owner. Unowned tables suggest the least-loaded server.

### Waitlist and wait estimate
- A guest joins with name and party size, and gets an ID and a position.
- **Estimate:**
  - If a free table fits the party: 0 ("A table is free now").
  - Otherwise: each fitting busy table's remaining time is (average dining time minus elapsed), floored at 2 min, plus 2 min to turn the table. The guest's estimate is the k-th smallest remaining time, where k is their position among parties that table size can fit.
- **Average dining time:** starts at 45 min, then becomes the running average of completed seatings.
- **Host Seat:** picks the smallest free table that fits. If the host picks a busy table, Turn refuses with a double-seating warning.

### Privacy
- Every detected person is blurred in the camera view.
- Staff show only as a badge label, never a name drawn on the image.
- Frames are never saved or sent anywhere.
- There's no face recognition and no per-person performance score. Load counts tables, not speed.

### Demo mode
- A scripted run of one service drives the same engine through every state and alert in about 2 minutes. It's labelled DEMO everywhere.
- Judges can run it without a camera.

## States and Boundaries
- **Camera missing or clip ended:** the camera view says so. The engine keeps the last known state, and DEMO stays available.
- **No servers configured:** alerts are unrouted.
- **Waitlist empty:** the guest page still shows the estimate for a party of 2.
- **A person standing in two zones:** counted at the table whose zone holds the centre of their box.

## Product Decisions
- **Badge, not face.** Decided for privacy. It also costs nothing to try.
- **Table cards, not body language.** Ask the guest instead of guessing.
- **One owner per table, set by the first visit.** Simple, and matches how servers claim tables.
- **The estimate uses the live floor plus a learned average.** It's honest about being an estimate.

## What We're Building
- Floor view
- Wait page
- Table state engine
- Alerts routed to servers
- Load
- Waitlist with seat suggestion and double-seat guard
- Vision from a camera or clip
- Privacy blur
- Demo mode

## Deferred From the POC
- Dining time learned per party size
- SMS when the table is ready
- Multiple cameras
- Shift summary

## Possible Later Enhancements
- Bussers as a separate role
- Section balancing by seat count
- A QR code printed per table

## Non-Goals
POS, payments, kitchen display, online ordering, phone reservations, marketing, payroll, face recognition, staff scoring.

## Open Questions
- Real greet and check-in times differ per restaurant, so they're set in config.
