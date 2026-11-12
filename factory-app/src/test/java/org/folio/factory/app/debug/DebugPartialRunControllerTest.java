package org.folio.factory.app.debug;

import org.folio.factory.app.debug.DebugPartialRunController.PartialRunRequest;
import org.folio.factory.core.registry.model.FlowDescriptor;
import org.folio.factory.core.registry.model.StepDescriptor;
import org.folio.factory.core.registry.model.StepType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebugPartialRunControllerTest {

    private static final FlowDescriptor FLOW = new FlowDescriptor("f", "F", "1", null, null, null,
            List.of(agent("gen"), agent("sync"), agent("automation-select")), null);

    private static StepDescriptor agent(String id) {
        return new StepDescriptor(id, StepType.AGENT, "w-" + id, null, null, null, null, null, null);
    }

    private static PartialRunRequest request(Integer index, String id) {
        return new PartialRunRequest("f", index, id, null, null);
    }

    @Test
    void resolvesTheStartStepById() {
        assertThat(DebugPartialRunController.startIndex(FLOW, request(null, "automation-select"))).isEqualTo(2);
    }

    @Test
    void stillAcceptsAnIndex() {
        assertThat(DebugPartialRunController.startIndex(FLOW, request(1, null))).isEqualTo(1);
    }

    @Test
    void anUnknownIdNamesTheStepsThatExist() {
        assertThatThrownBy(() -> DebugPartialRunController.startIndex(FLOW, request(null, "automate")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[gen, sync, automation-select]");
    }

    @Test
    void requiresExactlyOneOfIdAndIndex() {
        assertThatThrownBy(() -> DebugPartialRunController.startIndex(FLOW, request(null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DebugPartialRunController.startIndex(FLOW, request(0, "gen")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
