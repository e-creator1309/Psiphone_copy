#!/usr/bin/env python3
"""
inject-server-entry.py
Patches EmbeddedValues.java with a real psiphond server entry before the APK build.
Called automatically by build.yml when the SERVER_ENTRY secret is set.
"""
import os, re, sys

EMBEDDED_VALUES = "app/src/main/java/com/psiphon3/psiphonlibrary/EmbeddedValues.java"

def patch(path, server_entry, server_entry_key=""):
    with open(path) as f:
        src = f.read()

    # Patch EMBEDDED_SERVER_LIST
    src = re.sub(
        r'public static final String EMBEDDED_SERVER_LIST\[\] = \{[^}]*\};',
        'public static final String EMBEDDED_SERVER_LIST[] = {"' + server_entry + '"};',
        src
    )

    # Patch SERVER_ENTRY_SIGNATURE_PUBLIC_KEY
    if server_entry_key:
        src = re.sub(
            r'public static final String SERVER_ENTRY_SIGNATURE_PUBLIC_KEY = "[^"]*";',
            'public static final String SERVER_ENTRY_SIGNATURE_PUBLIC_KEY = "' + server_entry_key + '";',
            src
        )

    # Make sure IGNORE_NON_EMBEDDED is false (allow discovery of more servers)
    src = re.sub(
        r'public static final boolean IGNORE_NON_EMBEDDED_SERVER_ENTRIES = \w+;',
        'public static final boolean IGNORE_NON_EMBEDDED_SERVER_ENTRIES = false;',
        src
    )

    with open(path, "w") as f:
        f.write(src)

    print(f"[inject] Patched {path}")
    print(f"[inject] Server entry length: {len(server_entry)} chars")
    if server_entry_key:
        print(f"[inject] Server entry key length: {len(server_entry_key)} chars")

if __name__ == "__main__":
    entry = os.environ.get("SERVER_ENTRY") or (sys.argv[1] if len(sys.argv) > 1 else "")
    key   = os.environ.get("SERVER_ENTRY_KEY") or (sys.argv[2] if len(sys.argv) > 2 else "")

    if not entry:
        print("[inject] SERVER_ENTRY not set — skipping (open-source stub build)")
        sys.exit(0)

    patch(EMBEDDED_VALUES, entry.strip(), key.strip())
    print("[inject] Done.")
