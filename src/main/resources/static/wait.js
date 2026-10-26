// Guest wait page: shows the live estimate for the chosen party size, lets a guest join, then tracks their place.
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
let partyId = null;
try { partyId = localStorage.getItem("turn.party"); } catch (e) { /* storage blocked: guest just won't be remembered */ }

function showEstimate(eta, waiting) {
  if (eta === null || eta === undefined) {
    $("estimate").innerHTML = '<span style="font-size:28px">No table fits that size</span>';
    $("sub").textContent = "Ask the host. They may be able to join tables.";
  } else if (eta === 0) {
    $("estimate").innerHTML = '<span class="free">A table is free now</span>';
    $("sub").textContent = "Join the list and the host will seat you.";
  } else {
    $("estimate").innerHTML = `${eta}<small> min</small>`;
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
    if (w.mine) {
      $("join").hidden = true;
      $("mine").hidden = false;
      const eta = w.mine.etaMin;
      $("mine").innerHTML = `<div class="sub" style="margin:0">You're on the list, ${esc(w.mine.name)}</div>
        <div class="pos num">#${w.mine.position}</div>
        <div>${eta === null ? "No table fits your party yet." : eta === 0 ? "A table is opening for you now." : `About ${eta} min`}</div>
        <button id="leave" style="margin-top:12px">Leave the list</button>`;
      $("leave").onclick = async () => {
        await fetch("/api/waitlist/" + encodeURIComponent(partyId), { method: "DELETE" });
        forget();
      };
      showEstimate(eta, w.mine.position - 1);
    } else if (w.seated) {
      $("mine").hidden = false;
      $("join").hidden = true;
      $("mine").innerHTML = '<div class="pos">Your table is ready.</div><div class="sub" style="margin:6px 0 0">Head to the host stand.</div>';
      showEstimate(w.etaMin, w.waiting);
    } else {
      $("mine").hidden = true;
      $("join").hidden = false;
      showEstimate(w.etaMin, w.waiting);
    }
  } catch (e) {
    $("sub").textContent = "Can't reach the restaurant right now. Retrying…";
  }
}

function forget() {
  partyId = null;
  try { localStorage.removeItem("turn.party"); } catch (e) { /* ignore */ }
  refresh();
}

$("size").addEventListener("change", refresh);
$("join").addEventListener("submit", async (e) => {
  e.preventDefault();
  const res = await fetch("/api/waitlist", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ name: $("name").value, size: Number($("size").value) }),
  });
  if (!res.ok) return;
  const p = await res.json();
  partyId = p.id;
  try { localStorage.setItem("turn.party", p.id); } catch (err) { /* ignore */ }
  refresh();
});

refresh();
setInterval(refresh, 2000);
