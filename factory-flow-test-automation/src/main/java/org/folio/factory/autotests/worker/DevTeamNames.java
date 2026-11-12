package org.folio.factory.autotests.worker;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * TestRail Dev Team id -> team name, read from the case flow's team table
 * ({@code references/dev-teams.md}, on the app's classpath). Only needed for automation-only runs,
 * which start from TestRail cases that carry the team as a bare option id; a full run gets the name
 * in the hand-off. Without the table, lookups are empty and the generator infers the tag itself.
 */
final class DevTeamNames {

    static final String RESOURCE = "references/dev-teams.md";

    private final Map<Integer, String> byTestRailId;

    private DevTeamNames(Map<Integer, String> byTestRailId) {
        this.byTestRailId = byTestRailId;
    }

    static DevTeamNames load() {
        try (InputStream in = DevTeamNames.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            return in == null ? new DevTeamNames(Map.of())
                    : parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new DevTeamNames(Map.of());
        }
    }

    /** Rows {@code | Jira name | Jira ID | TestRail name | TestRail ID | Superseded by |}. */
    static DevTeamNames parse(String markdown) {
        Map<Integer, String> names = new HashMap<>();
        for (String line : markdown.split("\\R")) {
            String[] cells = line.split("\\|", -1);
            if (cells.length < 6 || !line.strip().startsWith("|")) continue;
            String testRailName = cells[3].strip();
            String testRailId = cells[4].strip();
            if (testRailName.isEmpty() || !testRailId.matches("\\d+")) continue;
            names.put(Integer.parseInt(testRailId), testRailName);
        }
        return new DevTeamNames(names);
    }

    Optional<String> nameFor(int testRailId) {
        return Optional.ofNullable(byTestRailId.get(testRailId));
    }
}
