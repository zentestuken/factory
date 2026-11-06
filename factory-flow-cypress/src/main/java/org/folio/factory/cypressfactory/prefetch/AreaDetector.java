package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.jira.JiraIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Validator and fallback for the LLM routing call.
 *
 * <p>SKILL.md treats area detection as agent judgment ("the story itself is the source of
 * truth"; story text outranks the Jira project prefix), so the router decides. This class
 * only (a) resolves the router's answers to real context files and (b) keyword-scores as a
 * fallback when the router fails, returns nothing usable, or hallucinates.</p>
 */
public class AreaDetector {

    private static final Logger log = LoggerFactory.getLogger(AreaDetector.class);

    /** SKILL.md: "A story can span two areas — read both context files." */
    public static final int MAX_AREAS = 2;

    /** The 30 context-file stems under {@code references/context/}. */
    public static final List<String> KNOWN_AREAS = List.of(
            "agreements", "bulk-edit", "check-in", "check-out", "circulation-log",
            "circulation-settings", "consortium-manager", "course-reserves", "data-export",
            "data-import", "dcb", "eholdings", "eureka", "fees-fines", "finance", "inventory",
            "invoices", "licenses", "lists", "loans", "marc-authority",
            "marc-bib-quickmarc", "mediated-requests", "oai-pmh", "orders", "organizations",
            "patron-notices", "receiving", "requests", "users");

    // Fallback only. Jira project key prefix → area(s).
    private static final Map<String, List<String>> PROJECT_KEY_AREAS = Map.ofEntries(
            Map.entry("AE", List.of("eholdings")),
            Map.entry("APPDI", List.of("data-import")),
            Map.entry("APPINV", List.of("inventory")),
            Map.entry("APPMARCMIG", List.of("marc-authority")),
            Map.entry("APPMARCST", List.of("marc-authority")),
            Map.entry("APPQM", List.of("marc-bib-quickmarc", "marc-authority")),
            Map.entry("CIRC", List.of("circulation-settings")),
            Map.entry("CIRCSTORE", List.of("circulation-settings")),
            Map.entry("DIUTILS", List.of("data-import")),
            Map.entry("EUREKA", List.of("eureka")),
            Map.entry("KEYCLOAK", List.of("eureka")),
            Map.entry("MGRENTITLE", List.of("eureka")),
            Map.entry("MODLOGINKC", List.of("eureka")),
            Map.entry("MODROLESKC", List.of("eureka")),
            Map.entry("MODSCHED", List.of("eureka")),
            Map.entry("STCOR", List.of("eureka")),
            Map.entry("UID", List.of("eureka")),
            Map.entry("UIPSELAPP", List.of("eureka")),
            Map.entry("UISAUTHCOM", List.of("eureka")),
            Map.entry("EDGCOURSES", List.of("course-reserves")),
            Map.entry("EDGEINV", List.of("inventory")),
            Map.entry("EDGEUSERS", List.of("users")),
            Map.entry("EDGLTI", List.of("course-reserves")),
            Map.entry("EDGOAIPMH", List.of("oai-pmh")),
            Map.entry("EDGORDERS", List.of("orders")),
            Map.entry("EDGPATRON", List.of("users")),
            Map.entry("GMU", List.of("marc-authority")),
            Map.entry("LIBLINKDM4", List.of("marc-authority")),
            Map.entry("MCBFF", List.of("circulation-settings")),
            Map.entry("MDEXP", List.of("data-export")),
            Map.entry("MODBULKED", List.of("bulk-edit")),
            Map.entry("MODBULKOPS", List.of("bulk-edit")),
            Map.entry("MODCAL", List.of("circulation-settings")),
            Map.entry("MODCAT", List.of("marc-authority")),
            Map.entry("MODCITEM", List.of("circulation-settings")),
            Map.entry("MODCON", List.of("consortium-manager")),
            Map.entry("MODCONSKC", List.of("consortium-manager")),
            Map.entry("MODCR", List.of("course-reserves")),
            Map.entry("MODCXINV", List.of("inventory")),
            Map.entry("MODDATAIMP", List.of("data-import")),
            Map.entry("MODDICORE", List.of("data-import")),
            Map.entry("MODEXPS", List.of("data-export")),
            Map.entry("MODEXPW", List.of("data-export")),
            Map.entry("MODFEE", List.of("fees-fines")),
            Map.entry("MODFIN", List.of("finance")),
            Map.entry("MODFISTO", List.of("finance")),
            Map.entry("MODINV", List.of("inventory")),
            Map.entry("MODINVIMP", List.of("inventory")),
            Map.entry("MODINVOICE", List.of("invoices")),
            Map.entry("MODINVOSTO", List.of("invoices")),
            Map.entry("MODINVPRO", List.of("inventory")),
            Map.entry("MODINVSTOR", List.of("inventory")),
            Map.entry("MODINVUP", List.of("inventory")),
            Map.entry("MODKBEBSCO", List.of("eholdings")),
            Map.entry("MODKBEKBJ", List.of("eholdings")),
            Map.entry("MODLDI", List.of("data-import")),
            Map.entry("MODLISTS", List.of("lists")),
            Map.entry("MODMARCATH", List.of("marc-authority")),
            Map.entry("MODMARCMIG", List.of("marc-authority")),
            Map.entry("MODOAIPMH", List.of("oai-pmh")),
            Map.entry("MODORDERS", List.of("orders")),
            Map.entry("MODORDSTOR", List.of("orders")),
            Map.entry("MODORG", List.of("organizations")),
            Map.entry("MODORGSTOR", List.of("organizations")),
            Map.entry("MODPATBLK", List.of("users")),
            Map.entry("MODPATRON", List.of("users")),
            Map.entry("MODQM", List.of("marc-bib-quickmarc", "marc-authority")),
            Map.entry("MODREC", List.of("receiving")),
            Map.entry("MODREQMED", List.of("mediated-requests", "requests")),
            Map.entry("MODSOURCE", List.of("inventory", "data-import")),
            Map.entry("MODUIMP", List.of("users")),
            Map.entry("MODUSERBL", List.of("users")),
            Map.entry("MODUSERS", List.of("users")),
            Map.entry("MODUSERSKC", List.of("eureka")),
            Map.entry("UIBULKED", List.of("bulk-edit")),
            Map.entry("UICAL", List.of("circulation-settings")),
            Map.entry("UICAT", List.of("marc-authority")),
            Map.entry("UICHKIN", List.of("check-in")),
            Map.entry("UICHKOUT", List.of("check-out")),
            Map.entry("UICIRC", List.of("circulation-settings")),
            Map.entry("UICIRCLOG", List.of("circulation-log", "circulation-settings")),
            Map.entry("UICONSET", List.of("consortium-manager")),
            Map.entry("UICR", List.of("course-reserves")),
            Map.entry("UIDATIMP", List.of("data-import")),
            Map.entry("UIDEXP", List.of("data-export")),
            Map.entry("UIEH", List.of("eholdings")),
            Map.entry("UIF", List.of("finance")),
            Map.entry("UIIN", List.of("inventory")),
            Map.entry("UIINIMP", List.of("inventory")),
            Map.entry("UILISTS", List.of("lists")),
            Map.entry("UIMARCAUTH", List.of("marc-authority")),
            Map.entry("UINV", List.of("invoices")),
            Map.entry("UIOAIPMH", List.of("oai-pmh")),
            Map.entry("UIOR", List.of("orders")),
            Map.entry("UIORG", List.of("organizations")),
            Map.entry("UIORGS", List.of("organizations")),
            Map.entry("UIPCIR", List.of("inventory")),
            Map.entry("UIPFO", List.of("organizations")),
            Map.entry("UIQM", List.of("marc-bib-quickmarc", "marc-authority")),
            Map.entry("UIREC", List.of("receiving")),
            Map.entry("UIREQ", List.of("requests")),
            Map.entry("UIREQMED", List.of("mediated-requests", "requests")),
            Map.entry("UIROLES", List.of("eureka")),
            Map.entry("UISINVCOMP", List.of("inventory")),
            Map.entry("UISMRCCOMP", List.of("marc-authority")),
            Map.entry("UIU", List.of("users"))
    );

    // Ordered: first match wins. Mirrors the Area Detection table in SKILL.md.
    private static final List<Map.Entry<String, String>> AREA_KEYWORDS = List.of(
            Map.entry("bulk edit", "bulk-edit"),
            Map.entry("bulk-edit", "bulk-edit"),
            Map.entry("data import", "data-import"),
            Map.entry("data-import", "data-import"),
            Map.entry("data export", "data-export"),
            Map.entry("data-export", "data-export"),
            Map.entry("marc authority", "marc-authority"),
            Map.entry("marc-authority", "marc-authority"),
            Map.entry("marc bib", "marc-bib-quickmarc"),
            Map.entry("quickmarc", "marc-bib-quickmarc"),
            Map.entry("purchase order", "orders"),
            Map.entry("po line", "orders"),
            Map.entry("pol ", "orders"),
            Map.entry("encumbrance", "orders"),
            Map.entry("acquisitions unit", "orders"),
            Map.entry("mediated request", "mediated-requests"),
            Map.entry("mediated-request", "mediated-requests"),
            Map.entry("patron notice", "patron-notices"),
            Map.entry("notice template", "patron-notices"),
            Map.entry("notice policy", "patron-notices"),
            Map.entry("circulation settings", "circulation-settings"),
            Map.entry("loan policy", "circulation-settings"),
            Map.entry("request policy", "circulation-settings"),
            Map.entry("lost item fee policy", "circulation-settings"),
            Map.entry("circulation log", "circulation-log"),
            Map.entry("circ log", "circulation-log"),
            Map.entry("course reserve", "course-reserves"),
            Map.entry("course-reserve", "course-reserves"),
            Map.entry("oai-pmh", "oai-pmh"),
            Map.entry("oai pmh", "oai-pmh"),
            Map.entry("harvest", "oai-pmh"),
            Map.entry("keycloak", "eureka"),
            Map.entry("capability set", "eureka"),
            Map.entry("authorization role", "eureka"),
            Map.entry("authorization policy", "eureka"),
            Map.entry("refresh token", "eureka"),
            Map.entry("session timeout", "eureka"),
            Map.entry("consortium manager", "consortium-manager"),
            Map.entry("consortia", "consortium-manager"),
            Map.entry("consortium", "consortium-manager"),
            Map.entry("ecs", "consortium-manager"),
            Map.entry("affiliation", "consortium-manager"),
            Map.entry("central tenant", "consortium-manager"),
            Map.entry("member tenant", "consortium-manager"),
            Map.entry("eholdings", "eholdings"),
            Map.entry("invoice", "invoices"),
            Map.entry("voucher", "invoices"),
            Map.entry("receiving", "receiving"),
            Map.entry("piece", "receiving"),
            Map.entry("fiscal year", "finance"),
            Map.entry("fund distribution", "finance"),
            Map.entry("ledger", "finance"),
            Map.entry("budget", "finance"),
            Map.entry("finance", "finance"),
            Map.entry("allocation", "finance"),
            Map.entry("rollover", "finance"),
            Map.entry("fee/fine", "fees-fines"),
            Map.entry("fee fine", "fees-fines"),
            Map.entry("manual charge", "fees-fines"),
            Map.entry("waive", "fees-fines"),
            Map.entry("check in", "check-in"),
            Map.entry("check-in", "check-in"),
            Map.entry("check out", "check-out"),
            Map.entry("check-out", "check-out"),
            Map.entry("checkout", "check-out"),
            Map.entry("declare lost", "loans"),
            Map.entry("claim returned", "loans"),
            Map.entry("loan detail", "loans"),
            Map.entry("anonymization", "loans"),
            Map.entry("request", "requests"),
            Map.entry("hold shelf", "requests"),
            Map.entry("recall", "requests"),
            Map.entry("agreements", "agreements"),
            Map.entry("agreement line", "agreements"),
            Map.entry("license", "licenses"),
            Map.entry("lists", "lists"),
            Map.entry("fqm", "lists"),
            Map.entry("marc", "marc-authority"),
            Map.entry("organization", "organizations"),
            Map.entry("vendor", "organizations"),
            Map.entry("edi", "organizations"),
            Map.entry("inventory", "inventory"),
            Map.entry("instance", "inventory"),
            Map.entry("holdings", "inventory"),
            Map.entry("item ", "inventory"),
            Map.entry("users", "users"),
            Map.entry("patron", "users"),
            Map.entry("proxy", "users"),
            Map.entry("orders", "orders"),
            Map.entry("circulation", "circulation-settings"),
            Map.entry("loan", "loans"),
            Map.entry("fund", "finance")
    );

    /**
     * Resolves the router's area names against the real context files, falling back to
     * keyword scoring when nothing usable comes back. Never returns an empty list.
     */
    public List<String> resolve(List<String> routerAreas, JiraIssue issue) {
        Set<String> resolved = new LinkedHashSet<>();
        if (routerAreas != null) {
            for (String raw : routerAreas) {
                String normalized = normalize(raw);
                if (normalized == null) {
                    log.warn("[area] Router returned unknown area '{}' - dropped", raw);
                } else if (resolved.size() < MAX_AREAS) {
                    resolved.add(normalized);
                }
            }
        }
        if (!resolved.isEmpty()) {
            return List.copyOf(resolved);
        }
        List<String> fallback = detectByKeywords(issue);
        log.warn("[area] Router produced no usable area - falling back to keyword detection: {}", fallback);
        return fallback;
    }

    /** Keyword + project-prefix detection. Used only when the router cannot be trusted. */
    public List<String> detectByKeywords(JiraIssue issue) {
        List<String> areas = new ArrayList<>();

        if (issue != null && issue.key() != null && !issue.key().isBlank()) {
            int dash = issue.key().indexOf('-');
            String prefix = (dash > 0 ? issue.key().substring(0, dash) : issue.key())
                    .toUpperCase(Locale.ROOT);
            List<String> keyAreas = PROJECT_KEY_AREAS.get(prefix);
            if (keyAreas != null) {
                for (String a : keyAreas) {
                    if (areas.size() < MAX_AREAS && !areas.contains(a)) areas.add(a);
                }
            }
        }

        if (areas.size() < MAX_AREAS) {
            String text = searchText(issue);
            for (Map.Entry<String, String> entry : AREA_KEYWORDS) {
                if (areas.size() >= MAX_AREAS) break;
                if (!areas.contains(entry.getValue()) && text.contains(entry.getKey())) {
                    areas.add(entry.getValue());
                }
            }
        }

        return areas.isEmpty() ? List.of("generic") : areas;
    }

    /** The area table rendered for the router prompt. */
    public String areaTable() {
        Map<String, List<String>> keywordsByArea = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : AREA_KEYWORDS) {
            keywordsByArea.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        Map<String, List<String>> prefixesByArea = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : PROJECT_KEY_AREAS.entrySet()) {
            for (String area : e.getValue()) {
                prefixesByArea.computeIfAbsent(area, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        prefixesByArea.values().forEach(v -> v.sort(String::compareTo));

        StringBuilder sb = new StringBuilder("| Area (context file stem) | Jira prefixes | Keywords |\n");
        sb.append("|---|---|---|\n");
        for (String area : KNOWN_AREAS) {
            List<String> prefixes = prefixesByArea.getOrDefault(area, List.of());
            List<String> keywords = keywordsByArea.getOrDefault(area, List.of());
            sb.append("| `").append(area).append("` | ")
                    .append(prefixes.isEmpty() ? "—" : String.join(", ", prefixes)).append(" | ")
                    .append(keywords.isEmpty() ? "—" : String.join(", ", keywords)).append(" |\n");
        }
        return sb.toString();
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String cleaned = raw.strip().toLowerCase(Locale.ROOT)
                .replace('_', '-').replace(' ', '-')
                .replaceAll("\\.md$", "")
                .replaceAll("[`\"']", "");
        if (cleaned.isBlank()) return null;
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) cleaned = cleaned.substring(slash + 1);
        return KNOWN_AREAS.contains(cleaned) ? cleaned : null;
    }

    private static String searchText(JiraIssue issue) {
        if (issue == null) return "";
        StringBuilder sb = new StringBuilder();
        if (issue.summary() != null) sb.append(' ').append(issue.summary().toLowerCase(Locale.ROOT));
        if (issue.description() != null) sb.append(' ').append(issue.description().toLowerCase(Locale.ROOT));
        if (issue.labels() != null) {
            for (String label : issue.labels()) sb.append(' ').append(label.toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
