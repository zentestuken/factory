package org.folio.factory.app.web;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UiFormatTest {

    @Test
    void instructionBlocksSplitsParagraphsAndLists() {
        List<Map<String, Object>> blocks = UiFormat.instructionBlocks("""
                Read this first.
                It continues here.

                - First item
                - Second item

                Closing note.
                """);

        assertThat(blocks).hasSize(3);
        assertThat(blocks.get(0)).containsEntry("type", "paragraph")
                .containsEntry("text", "Read this first. It continues here.");
        assertThat(blocks.get(1)).containsEntry("type", "list")
                .containsEntry("items", List.of("First item", "Second item"));
        assertThat(blocks.get(2)).containsEntry("type", "paragraph")
                .containsEntry("text", "Closing note.");
    }

    @Test
    void wrappedBulletLinesJoinIntoOneItem() {
        // YAML block scalars wrap long bullets; a continuation must not become a paragraph.
        List<Map<String, Object>> blocks = UiFormat.instructionBlocks("""
                - testrail_section — the destination folder. Either a path string
                  or a numeric section ID.
                - refs — the References field.
                """);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.getFirst().get("items")).isEqualTo(List.of(
                "testrail_section — the destination folder. Either a path string or a numeric section ID.",
                "refs — the References field."));
    }

    @Test
    void blankAndNullInstructionsYieldNoBlocks() {
        assertThat(UiFormat.instructionBlocks(null)).isEmpty();
        assertThat(UiFormat.instructionBlocks("   \n  ")).isEmpty();
    }
}
