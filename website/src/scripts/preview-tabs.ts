// These panels are illustrations; switching tabs never contacts a device.
const preview = document.querySelector<HTMLElement>(".preview-panel");
const tabs = [
  ...(preview?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? []),
];
const mode = preview?.querySelector<HTMLElement>("#preview-mode");

function selectTab(tab: HTMLButtonElement) {
  for (const candidate of tabs) {
    const selected = candidate === tab;
    candidate.setAttribute("aria-selected", String(selected));
    candidate.tabIndex = selected ? 0 : -1;
    const panelId = candidate.getAttribute("aria-controls");
    const panel = panelId ? document.getElementById(panelId) : null;
    if (panel) panel.hidden = !selected;
  }
  if (mode) mode.textContent = `${tab.dataset.view?.toUpperCase()} / PREVIEW`;
}

for (const [index, tab] of tabs.entries()) {
  tab.addEventListener("click", () => selectTab(tab));
  tab.addEventListener("keydown", (event) => {
    const targets: Record<string, number> = {
      ArrowRight: (index + 1) % tabs.length,
      ArrowLeft: (index + tabs.length - 1) % tabs.length,
      Home: 0,
      End: tabs.length - 1,
    };
    const targetIndex = targets[event.key];
    if (targetIndex === undefined) return;
    const target = tabs[targetIndex];
    if (!target) return;
    event.preventDefault();
    selectTab(target);
    target.focus();
  });
}

export {};
