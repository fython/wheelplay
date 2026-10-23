// This site is a product introduction. It never contacts a phone or a CarPlay server.
const themeButton = document.querySelector(".theme-toggle");
const preference = window.matchMedia("(prefers-color-scheme: dark)");
let chosenTheme;
try {
  chosenTheme = localStorage.getItem("wheelplay-site-theme");
} catch {
  /* Storage is optional. */
}
if (!["light", "dark"].includes(chosenTheme)) chosenTheme = null;

function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  const dark = theme === "dark";
  themeButton.setAttribute("aria-pressed", String(dark));
  themeButton.setAttribute(
    "aria-label",
    dark ? "切换到浅色主题" : "切换到深色主题",
  );
  document.querySelector('meta[name="theme-color"]').content = dark
    ? "#101720"
    : "#f8fafc";
}
applyTheme(chosenTheme || (preference.matches ? "dark" : "light"));
themeButton.hidden = false;
themeButton.addEventListener("click", () => {
  chosenTheme =
    document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  applyTheme(chosenTheme);
  try {
    localStorage.setItem("wheelplay-site-theme", chosenTheme);
  } catch {
    /* Continue without persistence. */
  }
});
preference.addEventListener("change", (event) => {
  if (!chosenTheme) applyTheme(event.matches ? "dark" : "light");
});

const tabs = [...document.querySelectorAll('[role="tab"]')];
function selectTab(tab) {
  for (const candidate of tabs) {
    const selected = candidate === tab;
    candidate.setAttribute("aria-selected", String(selected));
    candidate.tabIndex = selected ? 0 : -1;
    document.getElementById(candidate.getAttribute("aria-controls")).hidden =
      !selected;
  }
  document.getElementById("preview-mode").textContent =
    `${tab.dataset.view.toUpperCase()} / PREVIEW`;
}
for (const [index, tab] of tabs.entries()) {
  tab.addEventListener("click", () => selectTab(tab));
  tab.addEventListener("keydown", (event) => {
    const targets = {
      ArrowRight: (index + 1) % tabs.length,
      ArrowLeft: (index + tabs.length - 1) % tabs.length,
      Home: 0,
      End: tabs.length - 1,
    };
    if (!(event.key in targets)) return;
    event.preventDefault();
    const target = tabs[targets[event.key]];
    selectTab(target);
    target.focus();
  });
}
