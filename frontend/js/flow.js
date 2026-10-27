// Shift flow: polls /api/flow every two seconds. Totals, next best actions, live signals, capacity, timeline, server load.
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const money = (v) => "$" + Math.round(v).toLocaleString();
const SERVER_COLORS = ["#e5484d", "#d98a12", "#1fa565", "#2f7cf6", "#7c6cf0", "#e27bff", "#0ea5a4"];
const ICON = {
  overdue: '<path d="M12 8v5M12 16.5v.5"/><path d="M10.3 3.8 2.6 17a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 3.8a2 2 0 0 0-3.4 0Z"/>',
  seat: '<path d="M6 20v-6h12v6M8 14V6a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v8"/>',
  bus: '<path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/>',
  quote: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
  blocked: '<circle cx="12" cy="12" r="8.5"/><path d="m6 6 12 12"/>',
  calm: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
};

function setHTML(el, html) {
  if (el._html === html) return;
  el._html = html;
  el.innerHTML = html;
}

function time(sec) {
  if (sec == null) return "–";
  const s = Math.round(sec);
  return s < 60 ? `${s}s` : `${Math.floor(s / 60)}m ${String(s % 60).padStart(2, "0")}s`;
}

const clock = (ms, withSec) => new Date(ms).toLocaleTimeString([], withSec ? { hour: "numeric", minute: "2-digit", second: "2-digit" } : { hour: "numeric", minute: "2-digit" });

function renderKpis(f) {
  const kpi = (label, value, sub) => `<div class="kpi"><div class="l">${label}</div><div class="v num">${value}</div><div class="s">${sub}</div></div>`;
  const turn = f.avgTurnMin == null ? "–" : f.avgTurnMin < 1 ? `${Math.round(f.avgTurnMin * 60)}s` : `${Math.round(f.avgTurnMin)}m`;
  setHTML($("kpis"),
    kpi("Covers", f.covers, "guests seated") +
    kpi("Parties", f.parties, "seated this shift") +
    kpi("Tables turned", f.tablesTurned, "finished and left") +
    kpi("Avg turn time", turn, "seated to leaving") +
    kpi("Peak", f.peakStart == null ? "–" : clock(f.peakStart, f.bucketSec < 3600), "most covers"));
}

function renderActions(list) {
  setHTML($("actions"), list.map((a) => `<div class="act act-${esc(a.kind)}">
      <span class="act-i"><svg viewBox="0 0 24 24" aria-hidden="true">${ICON[a.kind] ?? ICON.calm}</svg></span>
      <div><div class="act-t">${esc(a.title)}</div><div class="act-d">${esc(a.detail)}</div></div></div>`).join(""));
}

function renderSignals(list) {
  if (!list.length) return setHTML($("signals"), '<div class="empty-note">Nothing unusual on the floor.</div>');
  setHTML($("signals"), list.map((s) => `<div class="sig tone-${esc(s.tone)}">
      <span class="sig-t">${esc(s.table.replace(/^Table /, "T"))}</span>
      <div><div class="sig-h">${esc(s.title)}</div><div class="sig-d">${esc(s.detail)}</div></div></div>`).join(""));
}

function renderCapacity(c, check) {
  $("check-note").textContent = `At a ${money(check)} average check`;
  const tile = (v, l, sub, tone) => `<div class="cap${tone ? " tone-" + tone : ""}"><div class="v num">${v}</div><div class="l">${l}</div>${sub ? `<div class="s">${sub}</div>` : ""}</div>`;
  setHTML($("capacity"),
    tile(c.seatsToTurn, "seats waiting to turn", c.tablesToTurn ? `${c.tablesToTurn} dirty table${c.tablesToTurn === 1 ? "" : "s"} · ${money(c.idleValue)} a turn` : "No dirty tables", c.seatsToTurn ? "bussing" : "") +
    tile(c.coversWaiting, "guests on the list", c.coversWaiting ? `${money(c.waitingValue)} of demand waiting` : "Nobody waiting", c.coversWaiting ? "attention" : "") +
    tile(c.openingSoon, "tables opening soon", "Near the end of a typical meal", "") +
    tile(c.nextOpenMin == null ? "–" : `${c.nextOpenMin}m`, "until the next table opens", c.nextOpenTable ? `Likely ${esc(c.nextOpenTable)}` : "No seated tables", ""));
}

function renderTimeline(buckets, bucketSec) {
  $("bucket-note").textContent = bucketSec >= 3600 ? "Covers per hour" : `Covers per ${bucketSec} s (demo runs fast)`;
  if (!buckets.length) return setHTML($("timeline"), '<div class="empty-note">The timeline fills in as tables are seated.</div>');
  const max = Math.max(1, ...buckets.map((b) => b.covers));
  setHTML($("timeline"), buckets.map((b) => `<div class="tl-row">
      <span class="tl-time num">${clock(b.start, bucketSec < 3600)}</span>
      <div class="tl-bar"><div class="tl-fill" style="width:${(b.covers / max) * 100}%"></div></div>
      <span class="tl-v num"><b>${b.covers}</b> covers</span>
      <span class="tl-meta">${b.seated} seated · ${b.completed} finished</span></div>`).join(""));
}

function renderServers(servers, insights) {
  const max = Math.max(3, ...servers.map((s) => s.load));
  setHTML($("servers"), servers.map((s, i) => `<div class="srv" style="--sc:${SERVER_COLORS[i % SERVER_COLORS.length]}">
      <span class="srv-n"><i></i>${esc(s.name)}${s.next ? '<span class="rot-badge">Next party</span>' : ""}</span>
      <div class="meter"><div class="fill" style="width:${(s.load / max) * 100}%"></div></div>
      <span class="srv-l">${s.load} table${s.load === 1 ? "" : "s"}${s.tables.length ? " · " + esc(s.tables.join(", ")) : ""}</span></div>`).join(""));
  const r = insights.responses;
  setHTML($("responses"), r.length
    ? `<div class="responses"><div class="resp-h"><span>Response time by alert</span><span>avg</span></div>${r.map((x) => `<div><span>${esc(x.label)} <small>×${x.count}</small></span><b class="num">${time(x.avgSec)}</b></div>`).join("")}</div>`
    : "");
}

async function refresh() {
  try {
    const f = await (await fetch("/api/flow")).json();
    $("source").lastElementChild.textContent = f.source;
    $("source").className = "pill " + (f.source === "DEMO" ? "demo" : "live");
    $("clock").textContent = new Date(f.now).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
    $("since").textContent = `Current shift since ${clock(f.shiftStart, true)}.`;
    renderKpis(f);
    renderActions(f.actions);
    renderSignals(f.signals);
    renderCapacity(f.capacity, f.avgCheck);
    renderTimeline(f.timeline, f.bucketSec);
    renderServers(f.servers, f.insights);
    document.body.classList.remove("offline");
  } catch (e) {
    $("since").textContent = "Lost connection to Turn. Retrying…";
    document.body.classList.add("offline");
  }
}

refresh();
setInterval(refresh, 2000);
