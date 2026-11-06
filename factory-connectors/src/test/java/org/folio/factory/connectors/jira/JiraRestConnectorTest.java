package org.folio.factory.connectors.jira;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class JiraRestConnectorTest {

    private MockRestServiceServer server;
    private JiraRestConnector connector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        connector = new JiraRestConnector(
                new JiraProperties("https://jira.example.org", "bot@example.org", "secret"), builder);
    }

    @Test
    void getIssueMapsFields() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andRespond(withSuccess("""
                        {"key": "ERM-42",
                         "fields": {
                            "summary": "Add agreement endpoint",
                            "description": "As a user...",
                            "status": {"name": "Ready for QA"},
                            "issuetype": {"name": "Story"},
                            "labels": ["ai-factory"]}}
                        """, MediaType.APPLICATION_JSON));

        JiraIssue issue = connector.getIssue("ERM-42");

        assertThat(issue.key()).isEqualTo("ERM-42");
        assertThat(issue.summary()).isEqualTo("Add agreement endpoint");
        assertThat(issue.status()).isEqualTo("Ready for QA");
        assertThat(issue.issueType()).isEqualTo("Story");
        assertThat(issue.labels()).containsExactly("ai-factory");
        server.verify();
    }

    @Test
    void getIssueSerializesNonTextualDescription() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"key": "ERM-42",
                         "fields": {
                            "summary": "Add agreement endpoint",
                            "description": {"type": "doc", "content": [{"type": "paragraph"}]},
                            "status": {"name": "Ready for QA"},
                            "issuetype": {"name": "Story"},
                            "labels": []}}
                        """, MediaType.APPLICATION_JSON));

        JiraIssue issue = connector.getIssue("ERM-42");

        assertThat(issue.description())
                .contains("\"type\":\"doc\"")
                .contains("\"content\":[{\"type\":\"paragraph\"}]");
        server.verify();
    }

    @Test
    void transitionResolvesIdByName() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42/transitions"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"transitions": [
                          {"id": "11", "name": "In Progress"},
                          {"id": "31", "name": "QA Complete"}]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42/transitions"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.transition.id").value("31"))
                .andRespond(withSuccess());

        connector.transitionIssue("ERM-42", "QA Complete");
        server.verify();
    }

    @Test
    void transitionFailsWhenNameUnknown() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42/transitions"))
                .andRespond(withSuccess("{\"transitions\": []}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.transitionIssue("ERM-42", "Nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no transition named");
    }

    @Test
    void getLinkedIssuesFetchesEachLinkedKey() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"key": "ERM-42",
                         "fields": {"summary": "main", "description": "", "status": {"name": "Open"},
                           "issuetype": {"name": "Story"}, "labels": [],
                           "issuelinks": [
                             {"type": {"name": "Blocks"}, "inwardIssue":  {"key": "MODERM-10"}},
                             {"type": {"name": "Blocks"}, "outwardIssue": {"key": "MODERM-11"}}
                           ]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/MODERM-10"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"key": "MODERM-10",
                         "fields": {"summary": "BE impl", "description": "AC: ...", "status": {"name": "Open"},
                           "issuetype": {"name": "Sub-task"}, "labels": []}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/MODERM-11"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"key": "MODERM-11",
                         "fields": {"summary": "FE tests", "description": "AC: ...", "status": {"name": "Open"},
                           "issuetype": {"name": "Sub-task"}, "labels": []}}
                        """, MediaType.APPLICATION_JSON));

        List<JiraIssue> linked = connector.getLinkedIssues("ERM-42");

        assertThat(linked).hasSize(2);
        assertThat(linked.get(0).key()).isEqualTo("MODERM-10");
        assertThat(linked.get(1).key()).isEqualTo("MODERM-11");
        server.verify();
    }

    @Test
    void getLinkedIssuesReturnsEmptyWhenNoLinks() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"key": "ERM-42",
                         "fields": {"summary": "s", "description": "", "status": {"name": "Open"},
                           "issuetype": {"name": "Story"}, "labels": [], "issuelinks": []}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(connector.getLinkedIssues("ERM-42")).isEmpty();
        server.verify();
    }

    @Test
    void stripsUnknownCustomFieldsAndRemapsKnownOnes() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"key": "ERM-42",
                         "fields": {
                            "summary": "s", "description": "d",
                            "status": {"name": "Open"}, "issuetype": {"name": "Story"}, "labels": [],
                            "customfield_10057": {"title": "ERM Team", "id": "7"},
                            "customfield_99999": null,
                            "customfield_10001": "some noise"}}
                        """, MediaType.APPLICATION_JSON));

        JiraIssue issue = connector.getIssue("ERM-42");

        assertThat(issue.raw().path("fields").has("customfield_10057")).isFalse();
        assertThat(issue.raw().path("fields").has("customfield_99999")).isFalse();
        assertThat(issue.raw().path("fields").has("customfield_10001")).isFalse();
        assertThat(issue.raw().path("fields").path("devTeam").path("title").asString(""))
                .isEqualTo("ERM Team");
        server.verify();
    }

    @Test
    void addCommentPostsBody() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42/comment"))
                .andExpect(method(POST))
                .andExpect(content().json("{\"body\": \"Test factory finished\"}"))
                .andRespond(withSuccess());

        connector.addComment("ERM-42", "Test factory finished");
        server.verify();
    }

    @Test
    void downloadAttachmentFollowsThe303ToTheMediaHost() {
        // Jira answers the attachment content endpoint with 303 and a pre-signed media URL. Spring
        // RestClient does not follow redirects, so the single-hop version returned an empty body -
        // which is how UIU-3562 reached the gate with both mockups marked "download failed".
        server.expect(requestTo("https://jira.example.org/rest/api/2/attachment/content/122774"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", org.hamcrest.Matchers.startsWith("Basic ")))
                .andRespond(withStatus(HttpStatus.SEE_OTHER)
                        .header("Location", "https://api.media.example/file/abc?token=signed"));
        server.expect(requestTo("https://api.media.example/file/abc?token=signed"))
                .andExpect(method(GET))
                // The signed URL authorizes itself; forwarding Jira credentials to another host
                // would leak them for nothing.
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(new byte[] {(byte) 0x89, 'P', 'N', 'G'},
                        MediaType.IMAGE_PNG));

        byte[] bytes = connector.downloadAttachment(
                "https://jira.example.org/rest/api/2/attachment/content/122774");

        assertThat(bytes).containsExactly((byte) 0x89, 'P', 'N', 'G');
        server.verify();
    }

    @Test
    void downloadAttachmentReturnsBytesServedWithoutARedirect() {
        server.expect(requestTo("https://jira.example.org/rest/api/2/attachment/content/1"))
                .andRespond(withSuccess(new byte[] {1, 2, 3}, MediaType.IMAGE_PNG));

        assertThat(connector.downloadAttachment(
                "https://jira.example.org/rest/api/2/attachment/content/1")).containsExactly(1, 2, 3);
    }

    @Test
    void downloadAttachmentGivesUpQuietlyWhenTheRedirectHasNoLocation() {
        // Degrades to "no image" rather than failing a run - the caller is enriching a prompt.
        server.expect(requestTo("https://jira.example.org/rest/api/2/attachment/content/2"))
                .andRespond(withStatus(HttpStatus.SEE_OTHER));

        assertThat(connector.downloadAttachment(
                "https://jira.example.org/rest/api/2/attachment/content/2")).isNull();
    }

    @Test
    void getIssueRequestsTheRenderedBodyOnlyWhenAskedTo() {
        // The overload is opt-in so Test Factory keeps the exact request it always made.
        server.expect(requestTo("https://jira.example.org/rest/api/2/issue/ERM-42"))
                .andRespond(withSuccess("{\"key\":\"ERM-42\",\"fields\":{}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(
                        "https://jira.example.org/rest/api/2/issue/ERM-42?expand=renderedFields"))
                .andRespond(withSuccess("{\"key\":\"ERM-42\",\"fields\":{},"
                        + "\"renderedFields\":{\"description\":\"<p>hi</p>\"}}",
                        MediaType.APPLICATION_JSON));

        connector.getIssue("ERM-42");
        JiraIssue rendered = connector.getIssue("ERM-42", true);

        assertThat(rendered.raw().path("renderedFields").path("description").asString(""))
                .isEqualTo("<p>hi</p>");
        server.verify();
    }
}
