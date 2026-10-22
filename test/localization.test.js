import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resourceStrings, renderBrowserTemplate } from '../scripts/resource-strings.mjs';
import { t } from '../common/src/main/assets/web/text.js';

const template = readFileSync(new URL('../common/src/main/assets/web/index.html', import.meta.url), 'utf8');
test('all browser messages exist in each Android resource catalog', () => {
  for (const module of ['shared', 'common']) {
    const base = resourceStrings('values', module);
    const argumentsIn = value => [...value.matchAll(/%(\d+)\$[0-9]*([a-zA-Z])/g)].map(match => match[1] + match[2]).sort();
    for (const qualifier of ['values-zh-rCN', 'values-zh-rTW']) {
      const catalog = resourceStrings(qualifier, module);
      assert.deepEqual(Object.keys(catalog).sort(), Object.keys(base).sort());
      for (const key of Object.keys(base)) assert.deepEqual(argumentsIn(catalog[key]), argumentsIn(base[key]), key);
    }
  }
  for (const [qualifier, title] of [['values', 'Connect your CarPlay display'], ['values-zh-rCN', '连接 CarPlay 车机屏幕'], ['values-zh-rTW', '連線 CarPlay 車機螢幕']]) {
    const messages = resourceStrings(qualifier), html = renderBrowserTemplate(template, messages);
    assert.ok(html.includes(title));
    assert.ok(!html.includes('{{'));
  }
});
test('localized messages preserve positional arguments without interpreting their contents', () => {
  globalThis.wheelplayMessages = { test: '%2$s / %1$s / %2$s' };
  try { assert.equal(t('test', '$& <script>', 'iPhone'), 'iPhone / $& <script> / iPhone'); }
  finally { delete globalThis.wheelplayMessages; }
});
test('browser template interpolation escapes markup', () => {
  assert.equal(renderBrowserTemplate('<p>{{text}}</p>', { text: '"<&' }), '<p>&quot;&lt;&amp;</p>');
});

test('Android exports every browser template and literal message key, not just the desktop fixture', () => {
  const android = readFileSync(new URL('../common/src/main/java/com/shilapi/xcertplay/web/BrowserStrings.kt', import.meta.url), 'utf8');
  const keys = new Set([...android.matchAll(/"([a-z0-9_]+)"\s+to\s+UiR\.string\.([a-z0-9_]+)/g)].map(match => {
    assert.equal(match[1], match[2]); return match[1];
  }));
  const required = [...template.matchAll(/\{\{([a-z0-9_]+)\}\}/g)].map(match => match[1]);
  for (const name of ['app.js', 'browser-audio.js']) {
    const js = readFileSync(new URL(`../common/src/main/assets/web/${name}`, import.meta.url), 'utf8');
    required.push(...[...js.matchAll(/\bt\(['"]([a-z0-9_]+)['"]/g)].map(match => match[1]));
  }
  for (const key of required) if (key !== 'locale') assert.ok(keys.has(key), `Android BrowserStrings is missing ${key}`);
});
