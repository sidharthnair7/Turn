// Light/dark theme shared by the floor and wait pages. Loaded in <head> so the right theme paints first.
(function () {
  const KEY = "turn.theme";
  const root = document.documentElement;
  let saved = null;
  try { saved = localStorage.getItem(KEY); } catch (e) { /* storage blocked: follow the system */ }
  // ?theme=dark or ?theme=light forces a theme for this load (used for screenshots and links).
  const forced = new URLSearchParams(location.search).get("theme");
  if (forced === "dark" || forced === "light") saved = forced;
  const dark = saved ? saved === "dark" : matchMedia("(prefers-color-scheme: dark)").matches;
  root.dataset.theme = dark ? "dark" : "light";

  document.addEventListener("click", (e) => {
    if (!e.target.closest("[data-theme-toggle]")) return;
    const next = root.dataset.theme === "dark" ? "light" : "dark";
    root.classList.add("theming");
    root.dataset.theme = next;
    try { localStorage.setItem(KEY, next); } catch (err) { /* ignore */ }
    setTimeout(() => root.classList.remove("theming"), 700);
  });
})();
