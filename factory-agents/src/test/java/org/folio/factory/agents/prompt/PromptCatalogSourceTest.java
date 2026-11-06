package org.folio.factory.agents.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptCatalogSourceTest {

    private final PromptCatalog catalog = new PromptCatalog();

    @Test
    void resolvesTheOwningModuleFromAnExplodedBuildUri() {
        catalog.scan();

        // factory-agents ships its own test prompts, so the scan finds at least one entry
        // and must attribute it to a real module directory rather than the bare convention.
        assertThat(catalog.all()).isNotEmpty();
        String dir = catalog.sourceDirectory(catalog.all().getFirst().workerId());
        assertThat(dir).endsWith("prompts/" + catalog.all().getFirst().workerId())
                .contains("src/main/resources/")
                .startsWith("factory-");
    }

    @Test
    void unknownPromptIdFallsBackToTheConventionalPath() {
        catalog.scan();

        assertThat(catalog.sourceDirectory("no-such-prompt")).isEqualTo("prompts/no-such-prompt");
    }
}
