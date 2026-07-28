package org.folio.factory.testfactory.worker;

import org.folio.factory.agents.artifact.FrontmatterCodec;
import org.folio.factory.agents.llm.AbstractLlmAgentWorker;
import org.folio.factory.connectors.jira.JiraConnector;
import org.folio.factory.connectors.jira.JiraIssue;
import org.folio.factory.core.agent.AgentContext;
import org.folio.factory.core.agent.AgentExecutionException;
import org.folio.factory.core.agent.AgentResult;
import org.folio.factory.testfactory.model.ScopeManifest;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parses a Jira story (inline payload or fetched via the Jira connector) and
 * produces {@code scope_manifest.md}: affected components, endpoints, risk and
 * ambiguities.
 */
public class TriageAgentWorker extends AbstractLlmAgentWorker {

    public static final String ID = "triage-agent";

    private final JiraConnector jiraConnector;
    private final FrontmatterCodec frontmatterCodec;

    public TriageAgentWorker(ChatClient chatClient, JiraConnector jiraConnector,
                             FrontmatterCodec frontmatterCodec) {
        super(chatClient);
        this.jiraConnector = jiraConnector;
        this.frontmatterCodec = frontmatterCodec;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public AgentResult execute(AgentContext context) {
        JsonNode payload = context.triggerPayload();
        if (payload == null) {
            throw new AgentExecutionException("Triage requires a trigger payload with an issueKey");
        }
        String issueKey = payload.path("issueKey").asString("");
        JsonNode inlineIssue = payload.get("issue");
        String issueJson;
        List<JiraIssue> linkedIssues;
        if (inlineIssue != null && !inlineIssue.isNull()) {
            issueJson = inlineIssue.toString();
            linkedIssues = List.of();
        } else {
            // Data-critical connector call: failures count against the retry budget.
            issueJson = jiraConnector.getIssue(issueKey).raw().toString();
            linkedIssues = jiraConnector.getLinkedIssues(issueKey);
        }
        String linkedIssuesJson = linkedIssues.isEmpty() ? "none"
                : linkedIssues.stream().map(i -> i.raw().toString()).collect(Collectors.joining("\n---\n"));

        ScopeManifest manifest = callForEntity(
                Map.of("issue_json", issueJson, "linked_issues_json", linkedIssuesJson),
                ScopeManifest.class);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issue_key", manifest.issueKey() == null || manifest.issueKey().isBlank()
                ? issueKey : manifest.issueKey());
        metadata.put("summary", manifest.summary());
        metadata.put("components", manifest.components());
        metadata.put("endpoints", manifest.endpoints());
        metadata.put("risk_level", manifest.riskLevel());
        metadata.put("ambiguities", manifest.ambiguities());
        metadata.put("acceptance_criteria", manifest.acceptanceCriteria());
        metadata.put("ecs_enabled", manifest.ecsEnabled());
        metadata.put("team", manifest.team());
        return resultWithUsage("scope_manifest.md",
                frontmatterCodec.render(metadata, manifest.analysis()));
    }
}
