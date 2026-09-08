#!/usr/bin/env python3
"""Apply the bounded v0.8.0 hard-navigation compatibility changes before Vite --base."""
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
patches = {
    "src/utils/request.ts": [("window.location.href = '/login'", "window.location.href = import.meta.env.BASE_URL + 'login'"),
                             ("window.location.pathname === '/login'", "window.location.pathname === import.meta.env.BASE_URL + 'login'")],
    "src/views/settings/TenantInfo.vue": [("window.location.href = '/login'", "window.location.href = import.meta.env.BASE_URL + 'login'")],
    "src/utils/tenantSwitch.ts": [("const SAFE_FALLBACK_PATH = '/platform/knowledge-bases'", "const SAFE_FALLBACK_PATH = import.meta.env.BASE_URL + 'platform/knowledge-bases'")],
}
for relative, replacements in patches.items():
    path = root / relative
    content = path.read_text()
    for before, after in replacements:
        if before not in content and after not in content:
            raise SystemExit(f"Upstream changed: {relative}; review compatibility before upgrading")
        content = content.replace(before, after)
    path.write_text(content)
print("WeKnora frontend subpath compatibility ready")
