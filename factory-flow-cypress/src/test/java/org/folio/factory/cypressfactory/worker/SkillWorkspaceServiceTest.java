package org.folio.factory.cypressfactory.worker;

import org.folio.factory.connectors.UnconfiguredConnectors;
import org.folio.factory.connectors.github.GitHubConnector;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.cypressfactory.CypressFlowProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillWorkspaceServiceTest {

    private CypressFlowProperties properties(String folioAiRepo) {
        return new CypressFlowProperties("", "main", folioAiRepo, null, null, null, null, 3, null, null, true);
    }

    private SkillWorkspaceService service(GitHubConnector gitHub, String folioAiRepo) {
        return new SkillWorkspaceService(gitHub, properties(folioAiRepo));
    }

    /** Serves one file and nothing else. */
    private static GitHubConnector gitHubServing(String path, String content) {
        return new GitHubConnector() {
            @Override public void createBranch(String repo, String base, String branch) { }
            @Override public void commitFiles(String repo, String branch, Map<String, String> f, String m) { }
            @Override public String createPullRequest(String r, String h, String b, String t, String body) {
                return "";
            }
            @Override public String getFileContent(String repo, String branch, String p) {
                return p.equals(path) ? content : null;
            }
            @Override public List<String> listDirectory(String repo, String branch, String p) {
                return List.of();
            }
        };
    }

    // ── Tier resolution ──────────────────────────────────────────────────────

    @Test
    void notReadyBeforeSync() {
        assertThat(service(new UnconfiguredConnectors.GitHub(), "").isReady()).isFalse();
    }

    @Test
    void fallsThroughToClasspathWhenCloneAndGitHubAreUnavailable() {
        SkillWorkspaceService svc = service(new UnconfiguredConnectors.GitHub(), "");

        SkillWorkspaceService.Asset asset = svc.read(SkillWorkspaceService.SKILL_MD).orElseThrow();

        assertThat(asset.source()).isEqualTo(SkillWorkspaceService.Source.CLASSPATH);
        assertThat(asset.content()).contains("Mandatory Workflow");
        assertThat(asset.detail()).contains("may be outdated");
    }

    @Test
    void examplesAlsoResolveFromTheBundle() {
        SkillWorkspaceService svc = service(new UnconfiguredConnectors.GitHub(), "");

        SkillWorkspaceService.Asset asset = svc.read(SkillWorkspaceService.EXAMPLES_MD).orElseThrow();

        assertThat(asset.source()).isEqualTo(SkillWorkspaceService.Source.CLASSPATH);
        assertThat(asset.content()).isNotBlank();
    }

    @Test
    void prefersGitHubOverTheClasspathBundle() {
        GitHubConnector gitHub = gitHubServing(
                SkillWorkspaceService.SKILL_DIR + "/" + SkillWorkspaceService.SKILL_MD, "# Fresh skill");

        SkillWorkspaceService.Asset asset = service(gitHub, "folio-org/folio-eureka-ai-dev")
                .read(SkillWorkspaceService.SKILL_MD).orElseThrow();

        assertThat(asset.source()).isEqualTo(SkillWorkspaceService.Source.GITHUB);
        assertThat(asset.content()).isEqualTo("# Fresh skill");
    }

    @Test
    void skipsGitHubWhenNoRepoIsConfigured() {
        GitHubConnector gitHub = gitHubServing(
                SkillWorkspaceService.SKILL_DIR + "/" + SkillWorkspaceService.SKILL_MD, "# Fresh skill");

        SkillWorkspaceService.Asset asset = service(gitHub, "")
                .read(SkillWorkspaceService.SKILL_MD).orElseThrow();

        assertThat(asset.source()).isEqualTo(SkillWorkspaceService.Source.CLASSPATH);
    }

    // ── Hard vs soft dependencies ────────────────────────────────────────────

    @Test
    void aMissingContextFileIsSoftAndReturnsEmpty() {
        SkillWorkspaceService svc = service(new UnconfiguredConnectors.GitHub(), "");

        assertThat(svc.read("references/context/marc-authority.md")).isEmpty();
    }

    @Test
    void requireSkillSucceedsFromTheBundle() {
        SkillWorkspaceService svc = service(new UnconfiguredConnectors.GitHub(), "");

        assertThat(svc.requireSkill()).contains("Mandatory Workflow");
    }

    // ── Delta anchors: does each factory delta still describe something real? ──

    @Test
    void bundledSkillStillAnchorsEveryDelta() {
        SkillWorkspaceService svc = service(new UnconfiguredConnectors.GitHub(), "");

        assertThat(svc.unanchoredDeltas(svc.requireSkill())).isEmpty();
    }

    @Test
    void namesTheDeltaWhoseAnchorPhraseDisappeared() {
        SkillWorkspaceService svc = service(new UnconfiguredConnectors.GitHub(), "");
        String edited = svc.requireSkill().replace("Ask for the TestRail Section ID", "Post the cases");

        assertThat(svc.unanchoredDeltas(edited)).containsExactly("delta 3 - never touch TestRail");
    }

    @Test
    void reportsEveryDeltaWhenTheSkillTextIsUnrecognisable() {
        SkillWorkspaceService svc = service(new UnconfiguredConnectors.GitHub(), "");

        assertThat(svc.unanchoredDeltas("# Some completely rewritten skill")).hasSize(3);
        assertThat(svc.unanchoredDeltas("")).hasSize(3);
        assertThat(svc.unanchoredDeltas(null)).hasSize(3);
    }

    @Test
    void requireSkillThrowsWhenEveryTierFails() {
        SkillWorkspaceService svc = new SkillWorkspaceService(
                new UnconfiguredConnectors.GitHub(), properties("")) {
            @Override
            public java.util.Optional<Asset> read(String relativePath) {
                return java.util.Optional.empty();
            }
        };

        assertThatThrownBy(svc::requireSkill)
                .isInstanceOf(AgentExecutionException.class)
                .hasMessageContaining("refusing to generate");
    }

    @Test
    void areaDriftFlagsContextFilesTheRouterCannotSelectAndKnownAreasWithoutAFile(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        for (String area : org.folio.factory.cypressfactory.prefetch.AreaDetector.KNOWN_AREAS) {
            java.nio.file.Files.writeString(dir.resolve(area + ".md"), "x");
        }
        assertThat(SkillWorkspaceService.areaDrift(dir)).isEmpty();

        java.nio.file.Files.writeString(dir.resolve("brand-new.md"), "x");
        java.nio.file.Files.delete(dir.resolve("dcb.md"));

        assertThat(SkillWorkspaceService.areaDrift(dir))
                .containsExactly("unroutable:brand-new", "missing-file:dcb");
    }

    @Test
    void areaDriftIsSilentWhenThereIsNoCloneToInspect(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        assertThat(SkillWorkspaceService.areaDrift(dir.resolve("absent"))).isEmpty();
    }

    @Test
    void ruleFilesResolveFromTheBundleWhenCloneAndGitHubAreUnavailable() {
        SkillWorkspaceService.SkillReferences rules =
                service(new UnconfiguredConnectors.GitHub(), "").ruleFiles();

        assertThat(rules.missing()).isEmpty();
        for (String path : SkillWorkspaceService.RULE_FILES) {
            assertThat(rules.text()).contains("## `" + path + "`");
        }
        assertThat(rules.sourceNote()).startsWith("3 of 3 rule files");
    }

    @Test
    void areaGoldenCasesResolveWithTheirPathPreserved() {
        SkillWorkspaceService svc = service(new UnconfiguredConnectors.GitHub(), "");

        assertThat(svc.read("references/examples/orders.md")).isPresent();
        assertThat(svc.read("references/orders.md")).isEmpty();
    }
}
