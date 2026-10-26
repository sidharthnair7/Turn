// Light/dark theme shared by the floor and wait pages. Loaded in <head> so the right theme paints first.
(function () {
  const KEY = "turn.theme";
  const root = document.documentElement;
  let saved = null;
  try { saved = localStorage.getItem(KEY); } catch (e) { /* storage blocked: follow the system */ }
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
