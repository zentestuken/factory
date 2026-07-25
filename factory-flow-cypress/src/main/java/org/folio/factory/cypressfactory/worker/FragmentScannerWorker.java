package org.folio.factory.cypressfactory.worker;

import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.core.agent.AgentWorker;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans the checked-out TAF (stripes-testing) for available Cypress fragment helpers.
 * Walks cypress/support/fragments/ looking for ES module and CommonJS exports.
 * Produces fragment_library.md listing found helpers; skips gracefully if no TAF root is configured.
 */
public class FragmentScannerWorker implements AgentWorker {

    public static final String ID = "cypress-fragment-scanner";

    private static final Logger log = LoggerFactory.getLogger(FragmentScannerWorker.class);

    // Matches: export default class Foo, export class Foo, export function foo, export const foo
    private static final Pattern ES_EXPORT = Pattern.compile(
            "^\\s*export\\s+(?:default\\s+)?(?:class|function|const|let|var)\\s+(\\w+)", Pattern.MULTILINE);
    // Matches: module.exports = { FooBar, ... } or module.exports = FooBar
    private static final Pattern CJS_EXPORT = Pattern.compile(
            "^\\s*module\\.exports\\s*=\\s*(?:\\{[^}]*\\}|(\\w+))", Pattern.MULTILINE);

    private final CypressFlowProperties properties;

    public FragmentScannerWorker(CypressFlowProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        if (properties.tafRoot().isBlank()) {
            log.info("FACTORY_CYPRESS_TAF_ROOT not configured — fragment library will be empty");
            return AgentResult.of("fragment_library.md",
                    "# Fragment Library\n\n_TAF root not configured; no fragments scanned._\n");
        }

        Path fragmentsDir = Path.of(properties.tafRoot(), "cypress", "support", "fragments");
        if (!Files.isDirectory(fragmentsDir)) {
            log.info("Fragments directory not found at {} — skipping scan", fragmentsDir);
            return AgentResult.of("fragment_library.md",
                    "# Fragment Library\n\n_Fragments directory not found at `" + fragmentsDir + "`._\n");
        }

        List<FragmentEntry> entries = new ArrayList<>();
        try (var walker = Files.walk(fragmentsDir)) {
            walker.filter(p -> p.toString().endsWith(".js") && Files.isRegularFile(p))
                    .forEach(p -> scanFile(p, fragmentsDir, entries));
        } catch (IOException e) {
            log.warn("Failed to walk fragments directory {}: {}", fragmentsDir, e.getMessage());
        }

        return AgentResult.of("fragment_library.md", buildMarkdown(entries));
    }

    private void scanFile(Path file, Path base, List<FragmentEntry> entries) {
        try {
            String content = Files.readString(file);
            String relativePath = base.relativize(file).toString().replace('\\', '/');
            List<String> names = extractExports(content);
            if (!names.isEmpty()) {
                entries.add(new FragmentEntry(relativePath, names));
            }
        } catch (IOException e) {
            log.debug("Could not read fragment file {}: {}", file, e.getMessage());
        }
    }

    private List<String> extractExports(String content) {
        List<String> names = new ArrayList<>();
        Matcher es = ES_EXPORT.matcher(content);
        while (es.find()) {
            names.add(es.group(1));
        }
        if (names.isEmpty()) {
            Matcher cjs = CJS_EXPORT.matcher(content);
            if (cjs.find() && cjs.group(1) != null) {
                names.add(cjs.group(1));
            }
        }
        return names;
    }

    private String buildMarkdown(List<FragmentEntry> entries) {
        if (entries.isEmpty()) {
            return "# Fragment Library\n\n_No fragment exports found under cypress/support/fragments/._\n";
        }
        StringBuilder sb = new StringBuilder("# Fragment Library\n\n");
        sb.append("Available Cypress fragment helpers in stripes-testing:\n\n");
        for (FragmentEntry entry : entries) {
            sb.append("- **").append(entry.path()).append("**: ")
              .append(String.join(", ", entry.exports())).append('\n');
        }
        return sb.toString();
    }

    private record FragmentEntry(String path, List<String> exports) {}
}
