import { en } from "./en";

export type Locale = "zh-CN" | "en";
export type Message = keyof typeof en;

export function localeFromPath(path: string): Locale {
  return path === "/en" || path.startsWith("/en/") ? "en" : "zh-CN";
}

/** Internal paths are language-neutral; assets and the download API stay shared. */
export function localizePath(path: string, locale: Locale): string {
  const base = path.replace(/^\/en(?=\/|$)/, "") || "/";
  return locale === "en" ? `/en${base}` : base;
}

export function useTranslations(path: string) {
  const locale = localeFromPath(path);
  return {
    locale,
    t: (message: Message): string => locale === "en" ? en[message] : message,
    local: (path: string): string => localizePath(path, locale),
  };
}
