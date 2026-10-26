// Guest wait page: shows the live estimate for the chosen party size, lets a guest join, then tracks their place.
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const calm = matchMedia("(prefers-reduced-motion: reduce)");
let partyId = null;
try { partyId = localStorage.getItem("turn.party"); } catch (e) { /* storage blocked: guest just won't be remembered */ }

// Only touch the DOM when the markup changed, so the 2 s poll doesn't restart animations.
function setHTML(el, html) {
  if (el._html === html) return false;
  el._html = html;
  el.innerHTML = html;
  return true;
}

// Party size as a segmented control that drives the (hidden) select the rest of the page reads.
function buildSizes() {
  const select = $("size");
  $("size-seg").innerHTML = [...select.options].map((o) =>
    `<button type="button" role="radio" aria-checked="${o.selected}" data-size="${o.value}">${o.value}</button>`).join("");
  $("size-seg").addEventListener("click", (e) => {
    const b = e.target.closest("[data-size]");
    if (!b || select.value === b.dataset.size) return;
    select.value = b.dataset.size;
    for (const x of $("size-seg").children) x.setAttribute("aria-checked", String(x === b));
    select.dispatchEvent(new Event("change"));
  });
}

let shownEta;
function countTo(el, from, to) {
  if (calm.matches || from == null || from === to) { el.textContent = to; return; }
  const start = performance.now(), dur = 600;
  const step = (now) => {
    const p = Math.min(1, (now - start) / dur), k = 1 - Math.pow(1 - p, 3);
    el.textContent = Math.round(from + (to - from) * k);
    if (p < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

function showEstimate(eta, waiting) {
  const est = $("estimate");
  if (eta === null || eta === undefined) {
    setHTML(est, '<span class="none">No table fits that size</span>');
    $("sub").textContent = "Ask the host. They may be able to join tables.";
    shownEta = null;
  } else if (eta === 0) {
    setHTML(est, '<span class="free"><i></i>A table is free now</span>');
    $("sub").textContent = "Join the list and the host will seat you.";
    shownEta = 0;
  } else {
    if (setHTML(est, `<span class="n rainbow-text">${eta}</span><small>min</small>`) || shownEta !== eta) {
      countTo(est.querySelector(".n"), typeof shownEta === "number" && shownEta > 0 ? shownEta : null, eta);
    }
    shownEta = eta;
    $("sub").textContent = `About ${eta} minutes, based on the tables right now. ${waiting} part${waiting === 1 ? "y" : "ies"} ahead on the list.`;
  }
}

async function refresh() {
  const size = $("size").value;
  const q = new URLSearchParams({ size });
  if (partyId) q.set("party", partyId);
  try {
    const w = await (await fetch("/api/wait?" + q)).json();
    $("demo-note").hidden = w.source !== "DEMO";
    document.body.classList.toggle("joined", Boolean(w.mine || w.seated));
    if (w.mine) {
      $("join").hidden = true;
      $("mine").hidden = false;
      $("mine").className = "mine";
      const eta = w.mine.etaMin;
      setHTML($("mine"), `<div class="label">You're on the list, ${esc(w.mine.name)}</div>
        <div class="mine-row"><div class="ring num">#${w.mine.position}</div>
          <div class="mine-eta">${eta === null ? "No table fits your party yet." : eta === 0 ? "A table is opening for you now." : `About <b class="num">${eta} min</b>`}</div></div>
        <button id="leave" class="btn btn-ghost btn-sm" type="button">Leave the list</button>`);
      showEstimate(eta, w.mine.position - 1);
    } else if (w.seated) {
      $("mine").hidden = false;
      $("join").hidden = true;
      $("mine").className = "mine ready";
      setHTML($("mine"), '<div class="ready-title">Your table is ready.</div><div class="sub">Head to the host stand.</div>');
      showEstimate(w.etaMin, w.waiting);
    } else {
      $("mine").hidden = true;
      $("join").hidden = false;
      showEstimate(w.etaMin, w.waiting);
    }
    document.body.classList.remove("offline");
  } catch (e) {
    $("sub").textContent = "Can't reach the restaurant right now. Retrying…";
    document.body.classList.add("offline");
  }
}

function forget() {
  partyId = null;
  try { localStorage.removeItem("turn.party"); } catch (e) { /* ignore */ }
  refresh();
}

$("mine").addEventListener("click", async (e) => {
  if (!e.target.closest("#leave")) return;
  await fetch("/api/waitlist/" + encodeURIComponent(partyId), { method: "DELETE" });
  forget();
});
$("size").addEventListener("change", refresh);
$("join").addEventListener("submit", async (e) => {
  e.preventDefault();
  const btn = $("join").querySelector("button");
  btn.disabled = true;
  const res = await fetch("/api/waitlist", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: $("name").value, size: Number($("size").value) }),
  }).catch(() => null);
  btn.disabled = false;
  if (!res || !res.ok) return;
  const p = await res.json();
  partyId = p.id;
  try { localStorage.setItem("turn.party", p.id); } catch (err) { /* ignore */ }
  refresh();
});

buildSizes();
refresh();
setInterval(refresh, 2000);
