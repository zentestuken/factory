package org.folio.factory.cypressfactory.plan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The FOLIO development teams, read from {@code references/dev-teams.md} at startup.
 *
 * <p>Shared by both cypress workers, like {@link TestPlanParser}: the generator normalises the
 * team it reads off the ticket, and the TestRail sync turns that name into the numeric
 * {@code custom_dev_team} option id. Keeping the data in a resource rather than a constant
 * means filling in a missing id is an edit, not a code change.</p>
 *
 * <p>A team whose TestRail id is unknown is still <em>recognised</em>. That distinction is the
 * point of this class: "we do not know this team" and "we know this team but not its TestRail
 * id" need different handling, and the previous hardcoded map could not tell them apart.</p>
 */
public class DevTeamCatalog {

    private static final Logger log = LoggerFactory.getLogger(DevTeamCatalog.class);
    private static final String RESOURCE = "references/dev-teams.md";

    /** One row of the table; {@code testRailId} is null when it has not been filled in yet. */
    public record DevTeam(String jiraName, Integer jiraId, String testRailName, Integer testRailId) {
        public boolean syncable() {
            return testRailId != null;
        }
    }

    private final Map<String, DevTeam> byKey = new LinkedHashMap<>();
    private final List<DevTeam> teams = new ArrayList<>();

    public DevTeamCatalog() {
        this(load());
    }

    DevTeamCatalog(String table) {
        parse(table);
        long withId = teams.stream().filter(DevTeam::syncable).count();
        log.info("[dev-teams] {} teams loaded, {} with a TestRail id", teams.size(), withId);
    }

    private static String load() {
        try {
            return new ClassPathResource(RESOURCE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }

    private void parse(String table) {
        for (String line : table.lines().toList()) {
            String row = line.strip();
            if (!row.startsWith("|")) {
                continue;
            }
            List<String> cells = cells(row);
            // Skips the header and the |---|---| separator: both fail the numeric Jira id test.
            if (cells.size() < 4) {
                continue;
            }
            Integer jiraId = number(cells.get(1));
            if (jiraId == null) {
                continue;
            }
            DevTeam team = new DevTeam(cells.get(0), jiraId, cells.get(2), number(cells.get(3)));
            teams.add(team);
            index(team.jiraName(), team);
            index(team.testRailName(), team);
        }
    }

    private void index(String name, DevTeam team) {
        if (name != null && !name.isBlank()) {
            byKey.putIfAbsent(key(name), team);
        }
    }

    private static List<String> cells(String row) {
        String trimmed = row.substring(1, row.endsWith("|") ? row.length() - 1 : row.length());
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static Integer number(String cell) {
        if (cell == null || cell.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(cell.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Case-insensitive, and tolerant of a trailing "Team" — Jira and TestRail disagree on
     * capitalisation (Bama vs BAMA) and values sometimes arrive decorated.
     */
    private static String key(String name) {
        String normalised = name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (normalised.endsWith(" team")) {
            normalised = normalised.substring(0, normalised.length() - " team".length()).strip();
        }
        return normalised;
    }

    public Optional<DevTeam> find(String name) {
        return name == null || name.isBlank()
                ? Optional.empty()
                : Optional.ofNullable(byKey.get(key(name)));
    }

    /** The canonical Jira spelling of a recognised team, or the input unchanged. */
    public String canonicalName(String name) {
        return find(name).map(DevTeam::jiraName).orElse(name == null ? "" : name.strip());
    }

    /** The TestRail option id, empty when the team is unknown <em>or</em> has no id yet. */
    public Optional<Integer> testRailId(String name) {
        return find(name).map(DevTeam::testRailId);
    }

    public List<DevTeam> all() {
        return List.copyOf(teams);
    }

    /** Comma-separated canonical names, for listing the valid set inside a prompt. */
    public String nameList() {
        return String.join(", ", teams.stream().map(DevTeam::jiraName).toList());
    }
}
