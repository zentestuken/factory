package org.folio.factory.agents.prompt;

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enumerates the (workerId, promptName) pairs bundled as classpath prompt
 * templates. The classpath is fixed at runtime, so the scan runs once at startup
 * (works inside jars via {@code classpath*:} + the pattern resolver, the same
 * mechanism {@code FlowRegistry} uses for flow descriptors).
 */
@Component
public class PromptCatalog {

    public record PromptRef(String workerId, String promptName) {
    }

    private static final String LOCATION_PATTERN = "classpath*:prompts/*/*.md";
    private static final String MARKER = "prompts/";
    private static final String SUFFIX = ".md";

    /**
     * Matches the owning Maven module in a resource URI, whether the prompt resolves from a
     * packaged jar ({@code .../factory-flow-cypress-1.0.0.jar!/prompts/...}) or from an
     * exploded build ({@code .../factory-flow-cypress/target/classes/prompts/...}).
     */
    private static final Pattern MODULE =
            Pattern.compile("(factory-[a-z0-9-]*?)(?:-\\d[^/]*)?(?:\\.jar!|/target)/");

    private List<PromptRef> refs = List.of();
    private final Map<String, String> sourcePaths = new HashMap<>();

    @PostConstruct
    void scan() {
        try {
            List<PromptRef> found = new ArrayList<>();
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION_PATTERN)) {
                String uri = resource.getURI().toString();
                PromptRef ref = parse(resource);
                if (ref != null) {
                    found.add(ref);
                    sourcePaths.computeIfAbsent(ref.workerId(), id -> sourcePath(id, uri));
                }
            }
            // Dedupe: the same template can resolve from both target/classes and a
            // packaged jar on the classpath, yielding identical PromptRefs.
            this.refs = found.stream().distinct()
                    .sorted(Comparator.comparing(PromptRef::workerId).thenComparing(PromptRef::promptName))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot scan prompt templates at " + LOCATION_PATTERN, e);
        }
    }

    public List<PromptRef> all() {
        return refs;
    }

    public boolean exists(String workerId, String promptName) {
        return refs.contains(new PromptRef(workerId, promptName));
    }

    /**
     * Repo-relative path of a prompt directory's templates, for telling a human where to edit
     * them — {@code factory-flow-cypress/src/main/resources/prompts/<id>}. The owning module is
     * recovered from the resource URI; when that cannot be determined the conventional
     * {@code prompts/<id>} suffix is returned, which is still unique across the repo.
     */
    public String sourceDirectory(String workerId) {
        return sourcePaths.getOrDefault(workerId, MARKER + workerId);
    }

    private static String sourcePath(String workerId, String uri) {
        Matcher module = MODULE.matcher(uri);
        String prefix = module.find() ? module.group(1) + "/src/main/resources/" : "";
        return prefix + MARKER + workerId;
    }

    public List<String> promptsFor(String workerId) {
        return refs.stream()
                .filter(ref -> ref.workerId().equals(workerId))
                .map(PromptRef::promptName)
                .toList();
    }

    private PromptRef parse(Resource resource) throws IOException {
        String uri = resource.getURI().toString();
        int marker = uri.lastIndexOf(MARKER);
        if (marker < 0 || !uri.endsWith(SUFFIX)) {
            return null;
        }
        String relative = uri.substring(marker + MARKER.length(), uri.length() - SUFFIX.length());
        int slash = relative.indexOf('/');
        if (slash <= 0 || slash == relative.length() - 1 || relative.indexOf('/', slash + 1) >= 0) {
            return null;
        }
        return new PromptRef(relative.substring(0, slash), relative.substring(slash + 1));
    }
}
