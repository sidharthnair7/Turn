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

function renderTables(tables) {
  $("tables").innerHTML = tables.map((t) => {
    const flags = [t.help ? '<span class="flag">HELP card</span>' : "", t.check ? '<span class="flag">CHECK card</span>' : ""].join("");
    let body;
    if (t.state === "SEATED") {
      body = `<div class="timer num">${mmss(t.seatedForSec)}</div>
        <div class="meta">${t.guests} guest${t.guests === 1 ? "" : "s"} · ${t.sinceVisitSec == null ? "<b>not visited yet</b>" : "last visit " + mmss(t.sinceVisitSec) + " ago"}</div>
        <div class="meta">Server: <b>${esc(t.server ?? "unassigned")}</b>${t.party ? " · " + esc(t.party) : ""}</div>`;
    } else if (t.reservedFor) {
      body = `<div class="meta" style="margin-top:10px">Held for <b>${esc(t.reservedFor)}</b></div>`;
    } else {
      body = `<div class="meta" style="margin-top:10px">${t.state === "BUSSING" ? "Guests left. Clear and reset." : t.state === "READY" ? "Reset done." : "Free"}</div>`;
    }
    return `<div class="table t-${t.tone}">
      <div class="top"><span class="name">${esc(t.name)}</span><span class="seats">${t.seats} seats</span></div>
      <div class="state">${STATE_LABEL[t.tone] ?? t.state}</div>
      ${body}
      <div class="flags">${flags}</div>
    </div>`;
  }).join("");
}

function renderAlerts(alerts) {
  if (!alerts.length) {
    $("alerts").innerHTML = '<div class="empty-note">Nothing waiting. Every table is covered.</div>';
    return;
  }
  $("alerts").innerHTML = alerts.map((a) => {
    const cls = a.overdue ? "overdue" : a.type === "BUSSING" ? "bussing" : "";
    const who = a.routedTo
      ? (a.suggested ? `No server yet. Send <b>${esc(a.routedTo)}</b> (lightest load)` : `<b>${esc(a.routedTo)}</b>'s table`)
      : "Unassigned";
    return `<div class="alert ${cls}">
      <div class="what">${esc(a.tableName)} · ${esc(a.label)}</div>
      <div class="age num">${mmss(a.ageSec)}</div>
      <div class="who">${who}</div>
    </div>`;
  }).join("");
}

function renderServers(servers, suggested) {
  const max = Math.max(3, ...servers.map((s) => s.load));
  $("servers").innerHTML = servers.map((s) => `
    <div class="server">
      <span><b>${esc(s.name)}</b></span>
      <div class="bar"><div class="fill" style="width:${(s.load / max) * 100}%"></div></div>
      <span class="num">${s.load}</span>
      <div class="tables-owned">${s.tables.length ? esc(s.tables.join(", ")) : "no active tables"}</div>
    </div>`).join("");
  $("suggest").innerHTML = suggested ? `Next table goes to <b>${esc(suggested)}</b>` : "";
}

function renderWaitlist(list, avg, done) {
  $("waitlist").innerHTML = list.length
    ? list.map((p) => `
      <div class="party">
        <div class="pos num">${p.position}</div>
        <div class="who"><b>${esc(p.name)}</b> · ${p.size}
          <small>waiting ${mmss(p.waitedSec)} · ${p.etaMin == null ? "no table fits" : p.etaMin === 0 ? "table free now" : "about " + p.etaMin + " min"}</small></div>
        <button class="primary" data-seat="${esc(p.id)}">Seat</button>
      </div>`).join("")
    : '<div class="empty-note">No one waiting.</div>';
  $("avg").textContent = `Average table time: ${avg} min${done ? ` (learned from ${done} finished table${done === 1 ? "" : "s"})` : " (starting estimate)"}`;
}

function secs(v) {
  if (v == null) return "–";
  return v < 60 ? `${v} s` : `${Math.floor(v / 60)}m ${String(v % 60).padStart(2, "0")}s`;
}

function renderInsights(i) {
  if (!i) return;
  const stat = (label, value) => `<div class="stat"><div class="v num">${value}</div><div class="l">${label}</div></div>`;
  $("insights").innerHTML = `
    <div class="stats">
      ${stat("covers", i.covers)}
      ${stat("avg first visit", secs(i.avgGreetSec))}
      ${stat("alerts answered", `${i.alertsAnswered}/${i.alertsRaised}`)}
      ${stat("avg table reset", secs(i.avgTurnSec))}
    </div>
    ${i.responses.length ? `<div class="responses">${i.responses.map((r) => `<div><span>${esc(r.label)}</span><b class="num">${secs(r.avgSec)}</b></div>`).join("")}</div>` : ""}
    <ul class="tips">${i.tips.map((t) => `<li>${esc(t)}</li>`).join("")}</ul>`;
}

let toastTimer;
function toast(msg, bad) {
  const t = $("toast");
  t.textContent = msg;
  t.className = "toast show" + (bad ? " bad" : "");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.className = "toast"), 3500);
}

$("waitlist").addEventListener("click", async (e) => {
  const id = e.target.dataset?.seat;
  if (!id) return;
  const res = await fetch("/api/seat", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ partyId: id }) });
  const body = await res.json().catch(() => ({}));
  if (res.ok) toast(`Seat them at ${body.table}.`);
  else toast(body.error ?? "Could not seat that party.", true);
  refresh();
});

$("restart").addEventListener("click", async () => {
  await fetch("/api/demo/restart", { method: "POST" });
  toast("Demo restarted.");
  refresh();
});

let cameraTimer;
function updateCamera(source) {
  if (source === "DEMO") {
    $("camera").hidden = true;
    $("camera-note").textContent = "Demo mode: tables are driven by a scripted two-minute service, no camera. Set turn.source to camera:0 or a clip to use real vision.";
    return;
  }
  if (cameraTimer) return;
  $("camera-note").textContent = "Privacy view: faces are blurred, staff are labelled by badge number only. Nothing is recorded.";
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
    $("source").textContent = s.source;
    $("source").className = "pill " + (s.source === "DEMO" ? "demo" : "live");
    $("status").textContent = s.sourceStatus;
    $("restart").hidden = s.source !== "DEMO";
    $("clock").textContent = new Date(s.now).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
    renderTables(s.tables);
    renderAlerts(s.alerts);
    renderServers(s.servers, s.suggestedServer);
    renderWaitlist(s.waitlist, s.avgDiningMin, s.completedSeatings);
    renderInsights(s.insights);
    updateCamera(s.source);
  } catch (e) {
    $("status").textContent = "Lost connection to Turn. Retrying…";
  }
}

refresh();
setInterval(refresh, 1000);
