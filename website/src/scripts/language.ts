const locale = document.documentElement.lang;

// Explicit document URLs always win. Only a return to the default homepage uses
// the saved preference; query strings and homepage section anchors are preserved.
try {
  if (location.pathname === "/" && localStorage.getItem("wheelplay-site-language") === "en") {
    location.replace(`/en/${location.search}${location.hash}`);
  }
} catch {
  // Navigation remains functional when storage is unavailable.
}

for (const link of document.querySelectorAll<HTMLAnchorElement>("[data-language-switch]")) {
  link.addEventListener("click", (event) => {
    // Modified clicks should open another language without changing this tab's preference.
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    try {
      localStorage.setItem("wheelplay-site-language", link.dataset.languageSwitch!);
    } catch {}
    if ((location.pathname === "/" || location.pathname === "/en/") && location.hash) {
      link.hash = location.hash;
    }
  });
}

// The shared static 404 includes both languages even without JavaScript.
if (document.body.classList.contains("not-found")) {
  const english = location.pathname.startsWith("/en/");
  document.documentElement.lang = english ? "en" : locale;
  if (english) document.title = "Page not found · WheelPlay";
  const switcher = document.querySelector<HTMLAnchorElement>("[data-language-switch]");
  if (english && switcher) {
    switcher.href = "/";
    switcher.lang = switcher.hreflang = "zh-CN";
    switcher.dataset.languageSwitch = "zh-CN";
    switcher.textContent = "中文";
    switcher.setAttribute("aria-label", "切换到中文");
  }
  for (const block of document.querySelectorAll<HTMLElement>("[data-error-language]")) {
    block.hidden = block.dataset.errorLanguage !== (english ? "en" : "zh-CN");
  }
}

export {};
