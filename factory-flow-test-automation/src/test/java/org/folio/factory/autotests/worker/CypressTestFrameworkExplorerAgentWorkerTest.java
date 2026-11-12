package org.folio.factory.autotests.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CypressTestFrameworkExplorerAgentWorkerTest {

    @TempDir
    Path cypress;

    private void file(String relative) throws Exception {
        Path p = cypress.resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
    }

    @Test
    void anAreaKeepsOnlyItsE2eFoldersPlusAllOfSupportAndDropsTestData() throws Exception {
        file("e2e/eureka/roles.cy.js");
        file("e2e/inventory/browse.cy.js");
        file("e2e/inn-reach-integration/a.cy.js");
        file("e2e/fees-&-fines/b.cy.js");
        file("support/fragments/users/usersCard.js");
        file("fixtures/marc.mrc");

        assertThat(CypressTestFrameworkExplorerAgentWorker.listFiles(cypress, List.of("eureka", "inn-reach")))
                .containsExactly("./e2e/eureka/roles.cy.js", "./e2e/inn-reach-integration/a.cy.js",
                        "./support/fragments/users/usersCard.js");
        assertThat(CypressTestFrameworkExplorerAgentWorker.listFiles(cypress, List.of("fees-fines")))
                .contains("./e2e/fees-&-fines/b.cy.js").doesNotContain("./e2e/eureka/roles.cy.js");
    }

    @Test
    void noAreaOrNoMatchingFolderListsEveryTest() throws Exception {
        file("e2e/eureka/roles.cy.js");
        file("e2e/inventory/browse.cy.js");

        assertThat(CypressTestFrameworkExplorerAgentWorker.listFiles(cypress, List.of())).hasSize(2);
        assertThat(CypressTestFrameworkExplorerAgentWorker.listFiles(cypress, List.of("serials-management"))).hasSize(2);
    }
}
