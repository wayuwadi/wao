package cn.wayuwa.wao.service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import cn.wayuwa.wao.domain.HostedPageEntity;

/** Trusted WAO shell around an unchanged uploaded document in an opaque-origin iframe. */
final class HostedPageDemoRenderer {
    private static final Pattern TITLE = Pattern.compile("(?is)<title(?:\\s[^>]*)?>(.*?)</title\\s*>");

    private HostedPageDemoRenderer() {}

    static byte[] render(HostedPageEntity page) {
        String original = new String(page.getHtmlContent(), StandardCharsets.UTF_8);
        var match = TITLE.matcher(original);
        String tabTitle = match.find() ? match.group(1) : page.getTitle();
        String shell = """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>__TITLE__</title>
                  __ICON__
                  <style>html,body{margin:0;width:100%;height:100%;overflow:hidden}iframe{display:block;border:0;width:100%;height:100%}</style>
                </head>
                <body>
                  <iframe id="demo" title="__TITLE__" sandbox="allow-scripts allow-forms allow-modals allow-popups"></iframe>
                  <script>
                    const pageId = '__PAGE_ID__';
                    const encodedHtml = '__ENCODED_HTML__';
                    const frame = document.getElementById('demo');
                    const storageKey = 'wao.hosted-page.' + pageId;
                    const storageLimit = 2000000;
                    const snapshot = Object.create(null);
                    try {
                      const persisted = JSON.parse(localStorage.getItem(storageKey) || '{}');
                      if (persisted && typeof persisted === 'object' && !Array.isArray(persisted)) {
                        for (const [key, value] of Object.entries(persisted)) {
                          if (typeof value === 'string') snapshot[key] = value;
                        }
                      }
                    } catch { /* Storage is unavailable; the page still opens. */ }

                    function installDemoStorage(initial, pageId, parentOrigin) {
                      const values = Object.assign(Object.create(null), initial);
                      const publish = () => parent.postMessage({ type: 'wao-demo-storage', pageId, values }, parentOrigin);
                      const storage = {
                        getItem(key) { key = String(key); return Object.hasOwn(values, key) ? values[key] : null; },
                        setItem(key, value) {
                          key = String(key);
                          const previous = values[key];
                          values[key] = String(value);
                          if (JSON.stringify(values).length > 2000000) {
                            if (previous === undefined) delete values[key];
                            else values[key] = previous;
                            throw new DOMException('Demo storage is full', 'QuotaExceededError');
                          }
                          publish();
                        },
                        removeItem(key) { delete values[String(key)]; publish(); },
                        clear() { for (const key of Object.keys(values)) delete values[key]; publish(); },
                        key(index) { return Object.keys(values)[index] ?? null; },
                      };
                      Object.defineProperty(storage, 'length', { get: () => Object.keys(values).length });
                      const proxy = new Proxy(storage, {
                        get(target, key) {
                          if (key in target) return Reflect.get(target, key);
                          return typeof key === 'string' ? target.getItem(key) ?? undefined : undefined;
                        },
                        set(target, key, value) {
                          if (typeof key !== 'string') return false;
                          target.setItem(key, value);
                          return true;
                        },
                      });
                      Object.defineProperty(window, 'localStorage', { configurable: true, value: proxy });
                    }

                    window.addEventListener('message', (event) => {
                      if (event.source !== frame.contentWindow) return;
                      const message = event.data;
                      if (!message || message.type !== 'wao-demo-storage' || message.pageId !== pageId) return;
                      if (!message.values || typeof message.values !== 'object' || Array.isArray(message.values)) return;
                      const clean = Object.create(null);
                      for (const [key, value] of Object.entries(message.values)) {
                        if (typeof value !== 'string') return;
                        clean[key] = value;
                      }
                      const serialized = JSON.stringify(clean);
                      if (serialized.length > storageLimit) return;
                      try { localStorage.setItem(storageKey, serialized); } catch { /* Keep the current session usable. */ }
                    });

                    const original = new TextDecoder().decode(Uint8Array.from(atob(encodedHtml), c => c.charCodeAt(0)));
                    const shim = '<script>(' + installDemoStorage.toString() + ')('
                      + JSON.stringify(snapshot) + ',' + JSON.stringify(pageId) + ',' + JSON.stringify(location.origin)
                      + ');<' + '/script>';
                    const head = /<head\\b[^>]*>/i.exec(original);
                    const doctype = /^\\s*<!doctype[^>]*>/i.exec(original);
                    frame.srcdoc = head
                      ? original.slice(0, head.index + head[0].length) + shim + original.slice(head.index + head[0].length)
                      : doctype
                        ? original.slice(0, doctype[0].length) + shim + original.slice(doctype[0].length)
                        : shim + original;
                  </script>
                </body>
                </html>
                """;
        return shell.replace("__TITLE__", escape(tabTitle))
                .replace("__ICON__", page.getIconContent() == null ? ""
                        : "<link rel=\"icon\" href=\"/api/pages/" + page.getSlug() + "/icon\">")
                .replace("__PAGE_ID__", page.getId())
                .replace("__ENCODED_HTML__", Base64.getEncoder().encodeToString(page.getHtmlContent()))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
