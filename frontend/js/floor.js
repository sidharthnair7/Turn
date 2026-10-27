// Floor view: polls /api/floor once a second and redraws the rotation bar, floor map, alerts, host stand and insights.
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const mmss = (sec) => {
  if (sec == null) return "";
  const m = Math.floor(sec / 60), s = Math.floor(sec % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
};
const store = {
  get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* storage blocked: fine */ } },
};

const STATE_LABEL = {
  empty: "Open", ok: "Seated", attention: "Needs attention", overdue: "Overdue",
  bussing: "Dirty", ready: "Reset, ready", reserved: "Seating", blocked: "Blocked",
};
const SUMMARY_ORDER = ["overdue", "attention", "bussing", "ok", "reserved", "ready", "empty", "blocked"];
const SUMMARY_LABEL = { ...STATE_LABEL, ok: "Seated", ready: "Ready", empty: "Open" };
// Each server keeps one colour everywhere: rotation bar, map badges.
const SERVER_COLORS = ["#e5484d", "#d98a12", "#1fa565", "#2f7cf6", "#7c6cf0", "#e27bff", "#0ea5a4"];
const ICON = {
  people: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="8" r="3.2"/><path d="M3.5 19c.6-3.2 2.8-5 5.5-5s4.9 1.8 5.5 5"/><circle cx="17" cy="9" r="2.4"/><path d="M15.6 14.2c2.4.1 4.2 1.7 4.7 4.8"/></svg>',
  clock: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>',
  spark: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v4M12 17v4M3 12h4M17 12h4M6.3 6.3l2.5 2.5M15.2 15.2l2.5 2.5M6.3 17.7l2.5-2.5M15.2 8.8l2.5-2.5"/></svg>',
  hand: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 11V5.5a1.5 1.5 0 0 1 3 0V11M11 10V4.5a1.5 1.5 0 0 1 3 0V11M14 10.5V6a1.5 1.5 0 0 1 3 0v7.5c0 4-2.5 6.5-6 6.5-2.6 0-4.3-1.3-5.6-3.6L4 14a1.5 1.5 0 0 1 2.6-1.5L8 14.5"/></svg>',
  bill: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h12v18l-3-2-3 2-3-2-3 2z"/><path d="M9 8h6M9 12h6"/></svg>',
};

let snap = null;
const serverColor = (name) => {
  if (!snap || !name) return "var(--faint)";
  const i = snap.servers.findIndex((s) => s.name === name);
  return i < 0 ? "var(--faint)" : SERVER_COLORS[i % SERVER_COLORS.length];
};
const initials = (name) => (name ?? "").replace(/[^A-Za-z]/g, "").slice(0, 2).toUpperCase();

// Only touch the DOM when the markup actually changed, so hover states and CSS transitions survive the 1 s poll.
function setHTML(el, html) {
  if (el._html === html) return false;
  el._html = html;
  el.innerHTML = html;
  return true;
}

// Keyed list update: existing cards are patched in place (so tone changes animate), new ones fade in, gone ones fade out.
function sync(root, items, keyOf, render) {
  const current = new Map();
  for (const el of root.children) if (el.dataset.key) current.set(el.dataset.key, el);
  let prev = null;
  for (const item of items) {
    const key = String(keyOf(item));
    const { cls, html, style } = render(item);
    let el = current.get(key);
    if (el) {
      current.delete(key);
    } else {
      el = document.createElement("div");
      el.dataset.key = key;
      el._entering = true;
      el.addEventListener("animationend", (e) => {
        if (e.target !== el || e.animationName !== "enter") return;
        el._entering = false;
        el.classList.remove("in");
      });
    }
    const nextCls = cls + (el._entering ? " in" : "");
    if (el.className !== nextCls) el.className = nextCls;
    if (style && el._style !== style) { el._style = style; el.style.cssText = style; }
    setHTML(el, html);
    const want = prev ? prev.nextSibling : root.firstChild;
    if (want !== el) root.insertBefore(el, want);
    prev = el;
  }
  for (const el of current.values()) {
    delete el.dataset.key;
    el.classList.add("out");
    setTimeout(() => el.remove(), 260);
  }
  // The empty note goes once real items arrive.
  if (items.length) root.querySelector(".empty-note")?.remove();
}

function emptyNote(root, text) {
  sync(root, [], null, null);
  const note = root.querySelector(".empty-note");
  if (!note) root.insertAdjacentHTML("beforeend", `<div class="empty-note">${text}</div>`);
  else if (note.textContent !== text) note.textContent = text;
}

function renderSummary(tables) {
  const counts = {};
  for (const t of tables) counts[t.tone] = (counts[t.tone] ?? 0) + 1;
  setHTML($("summary"), SUMMARY_ORDER.filter((k) => counts[k]).map((k) =>
    `<span class="chip tone-${k}"><i></i><b class="num">${counts[k]}</b>${SUMMARY_LABEL[k]}</span>`).join(""));
}

// ───────── Rotation bar: each server, their tables, and who gets the next party ─────────
function renderRotation(servers) {
  sync($("rotation"), servers, (s) => s.name, (s) => ({
    cls: "rot" + (s.next ? " next" : ""),
    style: `--sc:${serverColor(s.name)}`,
    html: `<div class="rot-top"><span class="rot-name"><i></i>${esc(s.name)}</span>${s.next ? '<span class="rot-badge">Next party</span>' : `<span class="rot-load num">${s.load}</span>`}</div>
      <div class="rot-tables">${s.tables.length ? s.tables.map((t) => `<span>${esc(t.replace(/^Table /, "T"))}</span>`).join("") : '<em class="none">No tables yet</em>'}</div>`,
  }));
}

// ───────── Floor map: tables placed where their camera zones sit, shaped by size ─────────
function renderMap(tables) {
  const map = $("map");
  if (!tables.length) return;
  const xs = tables.flatMap((t) => [t.x, t.x + t.w]), ys = tables.flatMap((t) => [t.y, t.y + t.h]);
  const minX = Math.min(...xs), maxX = Math.max(...xs), minY = Math.min(...ys), maxY = Math.max(...ys);
  const spanX = Math.max(1, maxX - minX), spanY = Math.max(1, maxY - minY);
  const ratio = Math.min(0.62, Math.max(0.34, spanY / spanX));
  map.style.setProperty("--ratio", ratio);
  sync(map, tables, (t) => t.id, (t) => {
    const cx = (t.x + t.w / 2 - minX) / spanX, cy = (t.y + t.h / 2 - minY) / spanY;
    const round = t.seats <= 2;
    const w = round ? 76 : 70 + t.seats * 11, h = round ? 76 : 70;
    const flags = (t.help ? `<span class="mt-flag" title="Help card">${ICON.hand}</span>` : "") + (t.check ? `<span class="mt-flag" title="Check card">${ICON.bill}</span>` : "");
    const line = t.state === "SEATED" ? mmss(t.seatedForSec)
      : t.tone === "bussing" ? "dirty " + mmss(t.stateSec)
      : t.tone === "reserved" ? "seating" : t.tone === "blocked" ? "blocked" : `${t.seats}p`;
    return {
      cls: `mt tone-${t.tone}${round ? " round" : ""}`,
      style: `left:${8 + cx * 84}%;top:${12 + cy * 76}%;width:${w}px;height:${h}px`,
      html: `<button type="button" data-table="${esc(t.id)}" aria-label="${esc(t.name)}, ${esc(STATE_LABEL[t.tone] ?? t.state)}">
          <span class="mt-num">${t.number}</span><span class="mt-sub num">${line}</span>
        </button>
        ${t.server ? `<span class="mt-srv" style="--sc:${serverColor(t.server)}" title="${esc(t.server)}">${initials(t.server)}</span>` : ""}
        ${flags ? `<span class="mt-flags">${flags}</span>` : ""}
        <span class="mt-name">${esc(t.name)}</span>`,
    };
  });
}

function renderLegend(c) {
  const item = (tone, n, label) => `<span class="lg tone-${tone}"><i></i><b class="num">${n}</b> ${label}</span>`;
  setHTML($("legend"), item("empty", c.open, "Open") + item("ok", c.seated, "Seated") + item("bussing", c.dirty, "Dirty") + item("blocked", c.blocked, "Blocked"));
}

function renderTables(tables) {
  sync($("tables"), tables, (t) => t.name, (t) => {
    const flags = [t.help ? '<span class="flag">Help card</span>' : "", t.check ? '<span class="flag">Check card</span>' : ""].join("");
    let body;
    if (t.state === "SEATED") {
      body = `<div class="timer num">${mmss(t.seatedForSec)}</div>
        <div class="meta">${t.guests} guest${t.guests === 1 ? "" : "s"} · ${t.sinceVisitSec == null ? "<b>not visited yet</b>" : "last visit " + mmss(t.sinceVisitSec) + " ago"}</div>
        <div class="meta">Server <b>${esc(t.server ?? "unassigned")}</b>${t.party ? " · " + esc(t.party) : ""}</div>`;
    } else if (t.reservedFor) {
      body = `<div class="meta rest">Held for <b>${esc(t.reservedFor)}</b></div>`;
    } else if (t.blocked) {
      body = `<div class="meta rest">Out of rotation</div>`;
    } else {
      body = `<div class="meta rest">${t.state === "BUSSING" ? "Guests left " + mmss(t.stateSec) + " ago. Clear and reset." : t.state === "READY" ? "Reset done." : "Free"}</div>`;
    }
    return {
      cls: `table tone-${t.tone}`,
      html: `<div class="top"><span class="name">${esc(t.name)}</span><span class="seats">${t.seats} seats</span></div>
        <div class="state"><i></i>${STATE_LABEL[t.tone] ?? esc(t.state)}</div>
        ${body}
        ${flags ? `<div class="flags">${flags}</div>` : ""}`,
    };
  });
}

function renderAlerts(alerts) {
  const count = $("alert-count");
  count.hidden = !alerts.length;
  count.textContent = alerts.length;
  if (!alerts.length) return emptyNote($("alerts"), "Nothing waiting. Every table is covered.");
  sync($("alerts"), alerts, (a) => `${a.tableName}|${a.type}`, (a) => {
    const tone = a.overdue ? "overdue" : a.type === "BUSSING" ? "bussing" : "attention";
    const who = a.routedTo
      ? (a.suggested ? `No server yet. Send <b>${esc(a.routedTo)}</b> (lightest load)` : `<b>${esc(a.routedTo)}</b>'s table`)
      : "Unassigned";
    return {
      cls: `alert tone-${tone}`,
      html: `<div class="what"><i></i>${esc(a.tableName)} · ${esc(a.label)}</div>
        <div class="age num">${mmss(a.ageSec)}</div>
        <div class="who">${who}</div>`,
    };
  });
}

// ───────── Host stand: counts, waitlist, reservations, add walk-in / booking ─────────
let listTab = store.get("turn.list") === "rsv" ? "rsv" : "wait";
let formOpen = false;

function renderHostCounts(c) {
  const box = (n, label) => `<div><b class="num">${n}</b><span>${label}</span></div>`;
  setHTML($("host-counts"), box(c.open, "Open") + box(c.dirty, "Dirty") + box(c.seated, "Seated"));
}

function quote(eta) {
  if (eta == null) return "No fit";
  return eta === 0 ? "Now" : `~${eta}m`;
}

function renderWaitlist(list, avg, done) {
  $("wait-count").textContent = list.length || "";
  if (!list.length) emptyNote($("waitlist"), "No one waiting.");
  else sync($("waitlist"), list, (p) => p.id, (p) => ({
    cls: "pcard" + (p.suggestedTable ? " has-fit" : ""),
    html: `<div class="pc-top">
        ${p.suggestedTable
          ? `<span class="sugg">${ICON.spark}Suggested ${esc(p.suggestedTable)}${p.suggestedServer ? ` · ${esc(p.suggestedServer)} next` : ""}</span>`
          : `<span class="sugg wait">Waiting for a table that fits</span>`}
        <span class="quote num">${quote(p.etaMin)}</span></div>
      <div class="pc-name">${esc(p.name)}</div>
      <div class="pc-meta"><span>${ICON.people}${p.size}</span><span>${ICON.clock}<span class="num">${mmss(p.waitedSec)}</span></span></div>
      ${p.note ? `<span class="tag">${esc(p.note)}</span>` : ""}
      <div class="pc-actions">
        <button class="btn btn-dark btn-sm" data-seat="${esc(p.id)}" data-table="${esc(p.suggestedTable ?? "")}">Seat</button>
        <button class="btn btn-ghost btn-sm" data-remove="${esc(p.id)}" aria-label="Remove ${esc(p.name)} from the list">Remove</button>
      </div>`,
  }));
  $("avg").textContent = `Average table time ${avg} min${done ? ` · learned from ${done} finished table${done === 1 ? "" : "s"}` : " · starting estimate"}`;
}

function renderReservations(list) {
  $("rsv-count").textContent = list.length || "";
  if (!list.length) return emptyNote($("reservations"), "No reservations left today.");
  sync($("reservations"), list, (r) => r.id, (r) => {
    const t = new Date(r.at).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    const when = r.inSec >= 60 ? `in ${Math.round(r.inSec / 60)}m` : r.inSec >= 0 ? "due now" : `${Math.round(-r.inSec / 60)}m late`;
    return {
      cls: "pcard rsv" + (r.inSec < 0 ? " late" : ""),
      html: `<div class="pc-top"><span class="sugg">${esc(t)}</span><span class="quote num">${when}</span></div>
        <div class="pc-name">${esc(r.name)}</div>
        <div class="pc-meta"><span>${ICON.people}${r.size}</span></div>
        ${r.note ? `<span class="tag">${esc(r.note)}</span>` : ""}
        <div class="pc-actions">
          <button class="btn btn-dark btn-sm" data-arrive="${esc(r.id)}">Arrived</button>
          <button class="btn btn-ghost btn-sm" data-cancel="${esc(r.id)}" aria-label="Cancel ${esc(r.name)}'s reservation">Cancel</button>
        </div>`,
    };
  });
}

function showList() {
  for (const b of document.querySelectorAll(".host-tabs [data-list]")) b.setAttribute("aria-selected", String(b.dataset.list === listTab));
  $("waitlist").hidden = listTab !== "wait";
  $("reservations").hidden = listTab !== "rsv";
  $("add-walkin").hidden = !(formOpen && listTab === "wait");
  $("add-rsv").hidden = !(formOpen && listTab === "rsv");
  $("add-toggle").textContent = formOpen ? "Close" : listTab === "wait" ? "+ Add walk-in" : "+ Add reservation";
}

function secs(v) {
  if (v == null) return "–";
  return v < 60 ? `${v} s` : `${Math.floor(v / 60)}m ${String(v % 60).padStart(2, "0")}s`;
}

function renderInsights(i) {
  if (!i) return;
  const stat = (label, value) => `<div class="stat"><div class="v num">${value}</div><div class="l">${label}</div></div>`;
  setHTML($("insights"), `
    <div class="stats">
      ${stat("covers", i.covers)}
      ${stat("avg first visit", secs(i.avgGreetSec))}
      ${stat("alerts answered", `${i.alertsAnswered}/${i.alertsRaised}`)}
      ${stat("avg table reset", secs(i.avgTurnSec))}
    </div>
    ${i.responses.length ? `<div class="responses">${i.responses.map((r) => `<div><span>${esc(r.label)}</span><b class="num">${secs(r.avgSec)}</b></div>`).join("")}</div>` : ""}
    ${i.tips.length ? `<ul class="tips">${i.tips.map((t) => `<li>${esc(t)}</li>`).join("")}</ul>` : ""}`);
}

let toastTimer;
function toast(msg, bad) {
  const t = $("toast");
  t.textContent = msg;
  t.className = "toast show" + (bad ? " bad" : "");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.className = "toast" + (bad ? " bad" : "")), 3500);
}

async function post(url, body, method = "POST") {
  const res = await fetch(url, { method, headers: { "Content-Type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  const data = await res.json().catch(() => ({}));
  return { ok: res.ok, data };
}

// ───────── Interactions ─────────
$("waitlist").addEventListener("click", async (e) => {
  const seat = e.target.closest("[data-seat]"), remove = e.target.closest("[data-remove]");
  if (seat) {
    seat.disabled = true;
    const table = snap?.tables.find((t) => t.name === seat.dataset.table);
    const { ok, data } = await post("/api/seat", { partyId: seat.dataset.seat, tableId: table?.id ?? null });
    toast(ok ? `Seat them at ${data.table}. Their phone says the table is ready.` : data.error ?? "Could not seat that party.", !ok);
    seat.disabled = false;
  } else if (remove) {
    await fetch("/api/waitlist/" + encodeURIComponent(remove.dataset.remove), { method: "DELETE" });
    toast("Removed from the list.");
  } else return;
  refresh();
});

$("reservations").addEventListener("click", async (e) => {
  const arrive = e.target.closest("[data-arrive]"), cancel = e.target.closest("[data-cancel]");
  if (arrive) {
    const { ok, data } = await post(`/api/reservations/${encodeURIComponent(arrive.dataset.arrive)}/arrive`);
    toast(!ok ? data.error ?? "Could not check them in." : data.table ? `Seat them at ${data.table}.` : "They're first on the waitlist. No table fits yet.", !ok);
  } else if (cancel) {
    await fetch("/api/reservations/" + encodeURIComponent(cancel.dataset.cancel), { method: "DELETE" });
    toast("Reservation cancelled.");
  } else return;
  refresh();
});

document.querySelector(".host-tabs").addEventListener("click", (e) => {
  const b = e.target.closest("[data-list]");
  if (!b) return;
  listTab = b.dataset.list;
  store.set("turn.list", listTab);
  showList();
});
$("add-toggle").addEventListener("click", () => {
  formOpen = !formOpen;
  showList();
  if (formOpen) (listTab === "wait" ? $("add-walkin") : $("add-rsv")).querySelector("input").focus();
});
$("add-walkin").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const { ok, data } = await post("/api/waitlist", { name: f.get("name"), size: Number(f.get("size")), note: f.get("note") });
  toast(ok ? `${data.name} is on the list.` : data.error ?? "Could not add them.", !ok);
  if (ok) { e.target.reset(); formOpen = false; showList(); refresh(); }
});
$("add-rsv").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  const { ok, data } = await post("/api/reservations", { name: f.get("name"), size: Number(f.get("size")), time: f.get("time"), note: f.get("note") });
  toast(ok ? "Booked." : data.error ?? "Could not book that.", !ok);
  if (ok) { e.target.reset(); formOpen = false; showList(); refresh(); }
});

$("map").addEventListener("click", async (e) => {
  const b = e.target.closest("[data-table]");
  if (!b || !snap) return;
  const t = snap.tables.find((x) => x.id === b.dataset.table);
  if (!t) return;
  if (["empty", "ready", "blocked"].includes(t.tone)) {
    const { ok, data } = await post(`/api/tables/${encodeURIComponent(t.id)}/block`);
    toast(!ok ? data.error ?? "Could not change that table." : data.blocked ? `${t.name} is blocked. It's out of seating and wait times.` : `${t.name} is back in rotation.`, !ok);
    refresh();
  } else if (t.state === "SEATED") {
    toast(`${t.name}: ${t.guests} guest${t.guests === 1 ? "" : "s"}, seated ${mmss(t.seatedForSec)}, ${t.server ? t.server + "'s table" : "no server yet"}.`);
  } else {
    toast(`${t.name}: ${STATE_LABEL[t.tone] ?? t.state}.`);
  }
});

let view = store.get("turn.view") === "cards" ? "cards" : "map";
function showView() {
  for (const b of document.querySelectorAll(".view-seg [data-view]")) b.setAttribute("aria-selected", String(b.dataset.view === view));
  $("map-wrap").hidden = view !== "map";
  $("tables").hidden = view !== "cards";
}
document.querySelector(".view-seg").addEventListener("click", (e) => {
  const b = e.target.closest("[data-view]");
  if (!b) return;
  view = b.dataset.view;
  store.set("turn.view", view);
  showView();
});

$("restart").addEventListener("click", async () => {
  await fetch("/api/demo/restart", { method: "POST" });
  toast("Demo restarted.");
  refresh();
});

let cameraTimer;
function updateCamera(source) {
  const wrap = $("camera-wrap");
  if (source === "DEMO") {
    wrap.classList.add("demo");
    $("camera").hidden = true;
    $("camera-note").innerHTML = "<b>Demo mode.</b> Tables are driven by a scripted two-minute service, no camera. Set <code>turn.source</code> to <code>camera:0</code> or a clip to use real vision.";
    return;
  }
  wrap.classList.remove("demo");
  if (cameraTimer) return;
  $("camera-note").innerHTML = "<b>Privacy view.</b> Faces are blurred, staff are labelled by badge number only. Nothing is recorded.";
  // A live MJPEG stream at the camera's own frame rate; if it drops, reconnect.
  const img = $("camera");
  const connect = () => { img.src = "/camera.mjpg?t=" + Date.now(); };
  img.onload = () => { img.hidden = false; };
  img.onerror = () => setTimeout(connect, 1000);
  connect();
  cameraTimer = true;
}

async function refresh() {
  try {
    snap = await (await fetch("/api/floor")).json();
    const s = snap;
    $("source").lastElementChild.textContent = s.source;
    $("source").className = "pill " + (s.source === "DEMO" ? "demo" : "live");
    $("status").textContent = s.sourceStatus ?? "";
    $("restart").hidden = s.source !== "DEMO";
    $("clock").textContent = new Date(s.now).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
    renderSummary(s.tables);
    renderRotation(s.servers);
    renderMap(s.tables);
    renderLegend(s.counts);
    renderTables(s.tables);
    renderAlerts(s.alerts);
    renderHostCounts(s.counts);
    renderWaitlist(s.waitlist, s.avgDiningMin, s.completedSeatings);
    renderReservations(s.reservations);
    renderInsights(s.insights);
    updateCamera(s.source);
    document.body.classList.remove("offline");
  } catch (e) {
    $("status").textContent = "Lost connection to Turn. Retrying…";
    document.body.classList.add("offline");
  }
}

showView();
showList();
refresh();
setInterval(refresh, 1000);
