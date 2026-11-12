package org.folio.factory.autotests.workspace;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The FOLIO environments specs run against, one per kind of case: {@code non-ecs} and {@code ecs}.
 * Rendered as stripes-testing's own {@code environments.js} (gitignored there), with
 * {@code activeEnvironment} set per case from its TestRail "ECS Enabled" field - so every spec
 * runs on the environment its case was written for.
 *
 * <p>Defaults are bundled ({@code cypress-run-environments.json}); a file named by
 * {@code FACTORY_CYPRESS_AUTOMATION_RUN_ENVS} replaces them, same shape.</p>
 */
public class RunEnvironments {

    public static final String NON_ECS = "non-ecs";
    public static final String ECS = "ecs";
    static final String BUNDLED = "cypress-run-environments.json";
    private static final JsonMapper JSON = new JsonMapper();

    private final JsonNode profiles;

    RunEnvironments(JsonNode profiles) {
        for (String name : new String[] {NON_ECS, ECS}) {
            if (!profiles.path(name).path("baseUrl").isString()) {
                throw new IllegalStateException("Spec-run environments need a '" + name + "' profile with a baseUrl");
            }
        }
        this.profiles = profiles;
    }

    /** @param overrideFile blank for the bundled defaults */
    public static RunEnvironments load(String overrideFile) {
        try {
            if (overrideFile != null && !overrideFile.isBlank()) {
                return new RunEnvironments(JSON.readTree(Files.readString(Path.of(overrideFile), StandardCharsets.UTF_8)));
            }
            try (InputStream in = RunEnvironments.class.getClassLoader().getResourceAsStream(BUNDLED)) {
                if (in == null) throw new IllegalStateException("Missing bundled " + BUNDLED);
                return new RunEnvironments(JSON.readTree(in));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read spec-run environments: " + e.getMessage(), e);
        }
    }

    /** {@code ecs} when the TestRail case has "ECS Enabled" set, otherwise {@code non-ecs}. */
    public static String profileFor(JsonNode caseDetails) {
        return caseDetails.path("custom_ecs_enabled").asBoolean(false) ? ECS : NON_ECS;
    }

    public String baseUrl(String profile) {
        return profiles.path(profile).path("baseUrl").asString("");
    }

    /** stripes-testing's {@code environments.js}, with {@code profile} active. */
    public String environmentsJs(String profile) {
        return "// Written by the FOLIO AI factory before each spec run; overwritten for every case.\n"
                + "const activeEnvironment = " + JSON.writeValueAsString(profile) + ";\n"
                + "const environments = " + JSON.writerWithDefaultPrettyPrinter().writeValueAsString(profiles) + ";\n"
                + "module.exports = { activeEnvironment, environments };\n";
    }
}
