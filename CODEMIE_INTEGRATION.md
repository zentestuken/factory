# CodeMie Proxy Integration

This document describes the changes made to route the app's Anthropic API calls through the
**CodeMie corporate auth proxy** instead of requiring a direct `ANTHROPIC_API_KEY`.
Re-apply these changes whenever the upstream repo is merged or rebased.

---

## Why

EPAM's CodeMie platform runs a local HTTP proxy (a Node.js process) that handles Anthropic
authentication via corporate SSO. The vanilla repo expects `ANTHROPIC_API_KEY` to be set.
After the changes below, the app:

1. Uses a real `ANTHROPIC_API_KEY` if one is present (CI / non-EPAM environments).
2. Falls back to `ANTHROPIC_BASE_URL` if that env var is set.
3. Auto-discovers the CodeMie proxy port by scanning local Node.js processes.
4. Errors loudly at startup if none of the above are found.

---

## Changed files

### 1. `factory-app/src/main/resources/application.yaml`

**Line ~19** — update the default model to the latest stable version:

```diff
-          model: ${FACTORY_LLM_MODEL:claude-sonnet-4-5}
+          model: ${FACTORY_LLM_MODEL:claude-sonnet-4-6}
```

Update the fallback value whenever the upstream default model is bumped; check
[Anthropic model IDs](https://docs.anthropic.com/en/docs/about-claude/models) for the current
latest.

---

### 2. `factory-app/src/main/java/org/folio/factory/app/FactoryApplication.java`

**a) Add imports** after the existing imports block:

```java
import java.io.BufferedReader;
import java.io.InputStreamReader;
```

**b) Call `configureCodemieProxy()` as the first statement in `main`:**

```java
public static void main(String[] args) {
    configureCodemieProxy();
    SpringApplication.run(FactoryApplication.class, args);
}
```

**c) Add the three private helper methods** (paste verbatim inside the class body):

```java
private static void configureCodemieProxy() {
    if (isNonBlank(System.getenv("ANTHROPIC_API_KEY"))) {
        return;
    }
    String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
    if (!isNonBlank(baseUrl)) {
        int port = detectCodemieProxyPort();
        if (port > 0) {
            baseUrl = "http://localhost:" + port;
        }
    }
    if (isNonBlank(baseUrl)) {
        System.setProperty("spring.ai.anthropic.api-key", "proxy-handled");
        System.setProperty("spring.ai.anthropic.base-url", baseUrl);
        System.out.printf("%n[factory] CodeMie proxy - routing Anthropic API to %s%n%n", baseUrl);
    } else {
        System.err.println("\n[factory] CodeMie proxy not found — set ANTHROPIC_API_KEY or ANTHROPIC_BASE_URL\n");
    }
}

private static int detectCodemieProxyPort() {
    String script =
        "Get-Process node -ErrorAction SilentlyContinue | ForEach-Object {" +
        " $id = $_.Id;" +
        " $cmd = (Get-WmiObject Win32_Process -Filter \"ProcessId=$id\").CommandLine;" +
        " if ($cmd -match 'codemie') {" +
        "  Get-NetTCPConnection -OwningProcess $id -ErrorAction SilentlyContinue" +
        "  | Where-Object State -eq 'Listen'" +
        "  | Select-Object -First 1 -ExpandProperty LocalPort" +
        " }" +
        "}";
    try {
        Process proc = new ProcessBuilder("powershell", "-Command", script)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.matches("\\d+")) {
                    proc.destroyForcibly();
                    return Integer.parseInt(line);
                }
            }
        }
        proc.waitFor();
    } catch (Exception e) {
        System.err.println("[factory] CodeMie port detection error: " + e.getMessage());
    }
    return -1;
}

private static boolean isNonBlank(String s) {
    return s != null && !s.isBlank();
}
```

---

## How it works — priority order

| Priority | Condition | Action |
|----------|-----------|--------|
| 1 (highest) | `ANTHROPIC_API_KEY` env var is set | Uses it directly; proxy logic skipped entirely |
| 2 | `ANTHROPIC_BASE_URL` env var is set | Uses that URL with a dummy key `proxy-handled` |
| 3 | CodeMie Node.js proxy is running locally | Auto-detects port via PowerShell; sets `base-url` to `http://localhost:<port>` |
| 4 (fallback) | None of the above | Prints an error to stderr and continues (Spring will fail on first LLM call) |

The auto-detect is **Windows-only** (uses PowerShell / WMI). On Linux/Mac CI the proxy won't be
found — set `ANTHROPIC_API_KEY` or `ANTHROPIC_BASE_URL` explicitly.

---

## Verification

After starting the app, look for one of these lines in stdout:

```
[factory] CodeMie proxy - routing Anthropic API to http://localhost:XXXXX
```

or (if using a direct key) — no proxy message at all (silent pass-through).

If you see:

```
[factory] CodeMie proxy not found — set ANTHROPIC_API_KEY or ANTHROPIC_BASE_URL
```

either start the CodeMie desktop app / CLI proxy, or export `ANTHROPIC_API_KEY`.

---

## Notes for future upstream merges

- These changes are isolated to `FactoryApplication.java` and `application.yaml`.
- `FactoryApplication.java` upstream only contains `main()` — conflicts are unlikely; just
  re-insert the `configureCodemieProxy()` call and the three helper methods.
- `application.yaml` conflict is limited to the `model:` line — keep whichever is the newer
  model name, or let the `FACTORY_LLM_MODEL` env var override it at runtime.
- No new dependencies are introduced (uses only `java.io` and `java.lang`).
