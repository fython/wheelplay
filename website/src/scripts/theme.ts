type Theme = "light" | "dark";
const english = document.documentElement.lang === "en";

const themeButton = document.querySelector<HTMLButtonElement>(".theme-toggle");
const themeColor = document.querySelector<HTMLMetaElement>(
  'meta[name="theme-color"]',
);
const preference = window.matchMedia("(prefers-color-scheme: dark)");
let chosenTheme: Theme | null = null;
try {
  const savedTheme = localStorage.getItem("wheelplay-site-theme");
  if (savedTheme === "light" || savedTheme === "dark") chosenTheme = savedTheme;
} catch {
  // Storage is optional; the toggle still works for this visit.
}

function applyTheme(theme: Theme) {
  document.documentElement.dataset.theme = theme;
  const dark = theme === "dark";
  themeButton?.setAttribute("aria-pressed", String(dark));
  themeButton?.setAttribute(
    "aria-label",
    english
      ? dark ? "Switch to light theme" : "Switch to dark theme"
      : dark ? "切换到浅色主题" : "切换到深色主题",
  );
  if (themeColor) themeColor.content = dark ? "#101720" : "#f8fafc";
}

applyTheme(chosenTheme || (preference.matches ? "dark" : "light"));
if (themeButton) {
  themeButton.hidden = false;
  themeButton.addEventListener("click", () => {
    chosenTheme =
      document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    applyTheme(chosenTheme);
    try {
      localStorage.setItem("wheelplay-site-theme", chosenTheme);
    } catch {
      // Continue without persistence.
    }
  });
}
preference.addEventListener("change", (event) => {
  if (!chosenTheme) applyTheme(event.matches ? "dark" : "light");
});

export {};
