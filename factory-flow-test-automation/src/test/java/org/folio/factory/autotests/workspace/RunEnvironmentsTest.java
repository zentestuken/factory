package org.folio.factory.autotests.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEnvironmentsTest {

    private static final JsonMapper JSON = new JsonMapper();

    @TempDir
    Path tmp;

    @Test
    void theCaseEcsFlagPicksTheProfile() {
        assertThat(RunEnvironments.profileFor(JSON.readTree("{\"custom_ecs_enabled\":true}"))).isEqualTo("ecs");
        assertThat(RunEnvironments.profileFor(JSON.readTree("{\"custom_ecs_enabled\":false}"))).isEqualTo("non-ecs");
        assertThat(RunEnvironments.profileFor(JSON.readTree("{}"))).isEqualTo("non-ecs");
    }

    @Test
    void bundledDefaultsRenderAsTheTafEnvironmentsFile() {
        String js = RunEnvironments.load("").environmentsJs("ecs");

        assertThat(js).contains("const activeEnvironment = \"ecs\";",
                "\"OKAPI_HOST\" : \"https://ecs-folio-etesting-snapshot-kong.ci.folio.org\"",
                "\"OKAPI_TENANT\" : \"consortium\"", "\"ecs_env_name\" : \"snapshot\"",
                "\"baseUrl\" : \"https://folio-etesting-snapshot-diku.ci.folio.org\"",
                "module.exports = { activeEnvironment, environments };");
    }

    @Test
    void anOverrideFileReplacesTheDefaultsAndMustHaveBothProfiles() throws Exception {
        Path ok = tmp.resolve("envs.json");
        Files.writeString(ok, "{\"non-ecs\":{\"baseUrl\":\"https://a\",\"env\":{}},\"ecs\":{\"baseUrl\":\"https://b\",\"env\":{}}}");
        Path missingEcs = tmp.resolve("bad.json");
        Files.writeString(missingEcs, "{\"non-ecs\":{\"baseUrl\":\"https://a\"}}");

        assertThat(RunEnvironments.load(ok.toString()).baseUrl("ecs")).isEqualTo("https://b");
        assertThatThrownBy(() -> RunEnvironments.load(missingEcs.toString()))
                .hasMessageContaining("'ecs' profile");
    }
}
