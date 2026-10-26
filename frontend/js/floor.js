// Floor view: polls /api/floor once a second and redraws tables, alerts, server load and the waitlist.
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const mmss = (sec) => {
  if (sec == null) return "";
  const m = Math.floor(sec / 60), s = Math.floor(sec % 60);
  return `${m}:${String(s).padStart(2, "0")}`;
};

const STATE_LABEL = {
  empty: "Empty", ok: "Seated", attention: "Needs attention", overdue: "Overdue",
  bussing: "Needs bussing", ready: "Ready for next party", reserved: "Seating",
};
const SUMMARY_ORDER = ["overdue", "attention", "bussing", "ok", "reserved", "ready", "empty"];
const SUMMARY_LABEL = { ...STATE_LABEL, ok: "Seated", ready: "Ready", empty: "Free" };

// Only touch the DOM when the markup actually changed, so hover states and CSS transitions survive the 1 s poll.
function setHTML(el, html) {
  if (el._html === html) return;
  el._html = html;
  el.innerHTML = html;
}

// Keyed list update: existing cards are patched in place (so tone changes animate), new ones fade in, gone ones fade out.
function sync(root, items, keyOf, render) {
  const current = new Map();
  for (const el of root.children) if (el.dataset.key) current.set(el.dataset.key, el);
  let prev = null;
  for (const item of items) {
    const key = String(keyOf(item));
    const { cls, html } = render(item);
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
  if (!root.querySelector(".empty-note")) root.insertAdjacentHTML("beforeend", `<div class="empty-note">${text}</div>`);
}

function renderSummary(tables) {
  const counts = {};
  for (const t of tables) counts[t.tone] = (counts[t.tone] ?? 0) + 1;
  setHTML($("summary"), SUMMARY_ORDER.filter((k) => counts[k]).map((k) =>
    `<span class="chip tone-${k}"><i></i><b class="num">${counts[k]}</b>${SUMMARY_LABEL[k]}</span>`).join(""));
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
    } else {
      body = `<div class="meta rest">${t.state === "BUSSING" ? "Guests left. Clear and reset." : t.state === "READY" ? "Reset done." : "Free"}</div>`;
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

function renderServers(servers, suggested) {
  const max = Math.max(3, ...servers.map((s) => s.load));
  sync($("servers"), servers, (s) => s.name, (s) => ({
    cls: "server",
    html: `<span class="sname">${esc(s.name)}</span>
      <div class="meter"><div class="fill" style="width:${(s.load / max) * 100}%"></div></div>
      <span class="num load">${s.load}</span>
      <div class="tables-owned">${s.tables.length ? esc(s.tables.join(", ")) : "no active tables"}</div>`,
  }));
  setHTML($("suggest"), suggested ? `Next table goes to <b>${esc(suggested)}</b>` : "");
}

function renderWaitlist(list, avg, done) {
  const count = $("wait-count");
  count.hidden = !list.length;
  count.textContent = list.length;
  if (!list.length) emptyNote($("waitlist"), "No one waiting.");
  else sync($("waitlist"), list, (p) => p.id, (p) => ({
    cls: "party",
    html: `<div class="pos num">${p.position}</div>
      <div class="who"><b>${esc(p.name)}</b> · party of ${p.size}
        <small>waiting ${mmss(p.waitedSec)} · ${p.etaMin == null ? "no table fits" : p.etaMin === 0 ? "table free now" : "about " + p.etaMin + " min"}</small></div>
      <button class="btn btn-dark btn-sm" data-seat="${esc(p.id)}">Seat</button>`,
  }));
  $("avg").textContent = `Average table time ${avg} min${done ? ` · learned from ${done} finished table${done === 1 ? "" : "s"}` : " · starting estimate"}`;
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

$("waitlist").addEventListener("click", async (e) => {
  const btn = e.target.closest("[data-seat]");
  if (!btn) return;
  btn.disabled = true;
  const res = await fetch("/api/seat", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ partyId: btn.dataset.seat }) });
  const body = await res.json().catch(() => ({}));
  if (res.ok) toast(`Seat them at ${body.table}.`);
  else toast(body.error ?? "Could not seat that party.", true);
  btn.disabled = false;
  refresh();
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
  const img = $("camera");
  const load = () => {
    const next = new Image();
    next.onload = () => { img.src = next.src; img.hidden = false; };
    next.src = "/camera.jpg?t=" + Date.now();
  };
  load();
  cameraTimer = setInterval(load, 400);
}

async function refresh() {
  try {
    const s = await (await fetch("/api/floor")).json();
    $("source").lastElementChild.textContent = s.source;
    $("source").className = "pill " + (s.source === "DEMO" ? "demo" : "live");
    $("status").textContent = s.sourceStatus ?? "";
    $("restart").hidden = s.source !== "DEMO";
    $("clock").textContent = new Date(s.now).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
    renderSummary(s.tables);
    renderTables(s.tables);
    renderAlerts(s.alerts);
    renderServers(s.servers, s.suggestedServer);
    renderWaitlist(s.waitlist, s.avgDiningMin, s.completedSeatings);
    renderInsights(s.insights);
    updateCamera(s.source);
    document.body.classList.remove("offline");
  } catch (e) {
    $("status").textContent = "Lost connection to Turn. Retrying…";
    document.body.classList.add("offline");
  }
}

refresh();
setInterval(refresh, 1000);
