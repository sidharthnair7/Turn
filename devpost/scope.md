---
doc: scope
status: approved
---

# Turn

Turn runs a restaurant floor from one camera you already own. It sees which tables need someone, sends the task to the right server, keeps the workload fair, and shows guests at the door an honest wait time.

> **How this scope was made:** Sid gave the direction in his own words ("uses cameras and stuff to monitor if any of the tables needs supervision ... tracks the loads taken by the staff ... I wanna do more than the shire yc project"). Then, short on time, he told the agent to make the scoping calls: "I want you to do it! with all of what shire does as well! Do it" (Oct 26, 2:33pm). The agent made the cuts below and wrote down why. His instruction counts as approval.

## The Unique Kernel
Shire (YC Fall 2026, https://shireintelligence.com/) turns a restaurant's CCTV into live table states and staff alerts. Turn does that floor layer, then covers what Shire leaves out:
- **Guests see the floor too.** A live wait time computed from what the camera actually sees, not a host's guess.
- **Staff are known by a badge, not a face.** A printed marker on the uniform tells the camera "staff". There's no face recognition and no per-person efficiency score, and frames never leave the machine.
- **A guest can ask without waving.** A table card the guest flips (help, or check please) is read by the camera.
- **No CCTV system needed.** One laptop or phone camera runs it.

## Who It's For
The **host of a small, independent restaurant on a Friday night**, with 6 to 12 tables, 2 or 3 servers and no CCTV. Today they watch the floor by eye, guess wait times, and find out a table was forgotten when the guest complains.

Second user: the **guest at the door**, deciding whether to wait or leave.

## The Core Loop
1. The host opens the floor view.
2. The camera keeps every table's state live: empty, seated, waiting, needs bussing, ready.
3. When a table needs someone, the task appears, routed to the server who owns that table.
4. A server with a badge walks over, and the task clears itself.
5. Guests check the wait page and see a real estimate.

They come back because it catches the forgotten table before the guest does.

## Inspiration & Identity
Shire's live host floor and task list (https://shireintelligence.com/). The feel is calm, like a kitchen ticket rail: high-contrast table cards that change colour with state, big type readable across a room, and no dashboard clutter.

## Why This Matters to the Learner
Sid wanted an idea he's passionate about and with real impact, and wants to "do more than the shire yc project". It's also his entry for Build With AI: Basics and, extended, the OpenCV AI Competition (OpenCV 5 + AWS).

## What "Working" Looks Like
On a staged table:
- A person sits at table 2 and the card turns **Seated**.
- No badge visits for the set time, and it turns **Not greeted**, routed to that table's server.
- Someone wearing a staff badge walks up, and the alert clears. That server's load goes up by one.
- A guest flips the "check please" card, and **Waiting on check** appears.
- Guests leave, and the card turns **Needs bussing**.
- Meanwhile the guest page shows "about N min" based on the live tables.

**The "oh, that's cool" beat:** the alert clears itself the moment the badge walks into the frame.

## The POC Boundary
- **Input:** one camera, or a recorded clip of it.
- **Tables:** zones drawn in config.
- **Vision:** people detection (OpenCV 5 dnn), badge and table-card detection (ArUco), privacy blur on the live view.
- **Table state engine:** seated, not greeted, needs check-in, waiting on check, help requested, needs bussing, ready.
- **Staff:** servers known by badge ID. The first badge to visit a newly seated table becomes its server. Live load per server, and a suggestion for the least-loaded server on the next table.
- **Host:** seats a party from the waitlist, gets the best free table suggested for that party size, and is warned against double-seating.
- **Guest page:** join the waitlist, see your position and estimated wait.
- **Demo mode:** a scripted run that drives the same engine without a camera, clearly labelled, so judges can try it.

## Later
- Learning dining time per table size from history.
- SMS when the guest's table is ready.
- Several cameras.
- Shift summary in plain language.

## Explicitly Cut
- **POS, payments, kitchen display, online ordering, reservations by phone, marketing and loyalty, payroll.** These are Shire's other seven products. Each is a product of its own and none proves the camera idea. A proof of concept can't hold them.
- **Face recognition and per-employee efficiency scoring.** Cut on purpose: privacy is part of the kernel.
- **Reading body language** (for example "menus closed, ready to order"). Not reliable at proof-of-concept level. The flip card asks the guest directly instead.
