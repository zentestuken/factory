package org.folio.factory.cypressfactory.prefetch;

import org.folio.factory.connectors.UnconfiguredConnectors;
import org.folio.factory.cypressfactory.worker.TafSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FragmentPrefetchTest {

    @TempDir
    Path taf;

    private FragmentPrefetch prefetch() {
        TafSyncService ready = new TafSyncService() {
            @Override public boolean isReady() { return true; }
            @Override public Path tafRoot() { return taf; }
        };
        return new FragmentPrefetch(ready, new UnconfiguredConnectors.GitHub());
    }

    private void fragment(String relativePath, String content) throws Exception {
        Path file = taf.resolve("cypress/support/fragments").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void eurekaPoolsTheSettingsDirectoriesAndOnlyTheNamedUsersFiles() throws Exception {
        fragment("settings/authorization-roles/authorizationRoles.js", "role capabilities hidden checkbox");
        fragment("settings/authorization-policies/authorizationPolicies.js", "policy");
        fragment("users/userEdit.js", "assign role");
        fragment("users/usersCard.js", "user roles accordion");
        fragment("users/waiveFeeFineModal.js", "waive");

        String text = prefetch().scan(List.of("eureka"), "Show hidden capabilities checkbox on role details").text();

        assertThat(text).contains("### cypress/support/fragments/settings/authorization-roles/authorizationRoles.js");
        assertThat(text).contains("authorizationPolicies.js").contains("userEdit.js").contains("usersCard.js");
        assertThat(text).doesNotContain("waiveFeeFineModal.js").doesNotContain("No fragment directory");
    }

    @Test
    void anUnmappedAreaStillUsesItsOwnNameAsTheDirectory() throws Exception {
        fragment("finance/budgets.js", "budget");

        String text = prefetch().scan(List.of("finance"), "budget rollover").text();

        assertThat(text).contains("### cypress/support/fragments/finance/budgets.js");
    }

    @Test
    void anAreaWithNoMappedPathOnDiskSaysSo() {
        String text = prefetch().scan(List.of("eureka"), "anything").text();

        assertThat(text).contains("No fragment directory found at `cypress/support/fragments/settings/authorization-roles`");
    }
}
