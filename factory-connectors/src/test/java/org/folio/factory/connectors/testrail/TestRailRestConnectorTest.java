package org.folio.factory.connectors.testrail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TestRailRestConnectorTest {

    private MockRestServiceServer server;
    private TestRailRestConnector connector;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        connector = new TestRailRestConnector(
                new TestRailProperties("https://testrail.example.org", "bot", "key", 12L, null), builder);
    }

    @Test
    void addCaseReturnsId() {
        server.expect(requestTo("https://testrail.example.org/index.php?/api/v2/add_case/55"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.title").value("Login works"))
                .andRespond(withSuccess("{\"id\": 991}", MediaType.APPLICATION_JSON));

        assertThat(connector.addCase(55, "Login works", "steps", "expected")).isEqualTo(991);
    }

    @Test
    void addRunUsesConfiguredProject() {
        server.expect(requestTo("https://testrail.example.org/index.php?/api/v2/add_run/12"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.include_all").value(false))
                .andRespond(withSuccess("{\"id\": 300}", MediaType.APPLICATION_JSON));

        assertThat(connector.addRun("Test Factory run", List.of(991L))).isEqualTo(300);
    }

    @Test
    void addResultsMapsPassFailToStatusIds() {
        server.expect(requestTo("https://testrail.example.org/index.php?/api/v2/add_results_for_cases/300"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.results[0].case_id").value(991))
                .andExpect(jsonPath("$.results[0].status_id").value(1))
                .andRespond(withSuccess());

        connector.addResults(300, Map.of(991L, true), "automated");
        server.verify();
    }

    @Test
    void addResultsMapsFailureAndNullCommentToEmptyString() {
        server.expect(requestTo("https://testrail.example.org/index.php?/api/v2/add_results_for_cases/300"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.results[0].case_id").value(991))
                .andExpect(jsonPath("$.results[0].status_id").value(5))
                .andExpect(jsonPath("$.results[0].comment").value(""))
                .andRespond(withSuccess());

        connector.addResults(300, Map.of(991L, false), null);
        server.verify();
    }

    @Test
    void getSectionsReturnsAllSectionsWithFullPaths() {
        String sectionsJson = """
                {"sections":[
                  {"id":10,"name":"Inventory","parent_id":null,"depth":0},
                  {"id":20,"name":"MARC","parent_id":null,"depth":0},
                  {"id":11,"name":"Search","parent_id":10,"depth":1},
                  {"id":21,"name":"MARC authority","parent_id":20,"depth":1}
                ]}""";
        server.expect(requestTo(
                "https://testrail.example.org/index.php?/api/v2/get_sections/12&limit=250&offset=0"))
                .andExpect(method(GET))
                .andRespond(withSuccess(sectionsJson, MediaType.APPLICATION_JSON));

        var sections = connector.getSections();

        assertThat(sections).hasSize(4);
        assertThat(sections).extracting(s -> s.fullPath())
                .containsExactlyInAnyOrder(
                        "Inventory",
                        "MARC",
                        "Inventory › Search",
                        "MARC › MARC authority");
        assertThat(sections).extracting(s -> s.id())
                .containsExactlyInAnyOrder(10L, 20L, 11L, 21L);
    }

    @Test
    void getSectionsIncludesDeepSectionsUnlikeListSectionPaths() {
        String sectionsJson = """
                {"sections":[
                  {"id":1,"name":"Root","parent_id":null,"depth":0},
                  {"id":2,"name":"Child","parent_id":1,"depth":1},
                  {"id":3,"name":"Grand","parent_id":2,"depth":2},
                  {"id":4,"name":"Deep","parent_id":3,"depth":3}
                ]}""";
        server.expect(requestTo(
                "https://testrail.example.org/index.php?/api/v2/get_sections/12&limit=250&offset=0"))
                .andExpect(method(GET))
                .andRespond(withSuccess(sectionsJson, MediaType.APPLICATION_JSON));

        var sections = connector.getSections();

        // getSections has no depth filter — all 4 are returned
        assertThat(sections).hasSize(4);
        assertThat(sections).extracting(s -> s.fullPath())
                .contains("Root › Child › Grand › Deep");
    }

    @Test
    void listSectionPathsReturnsSortedPathsUpToDepth2() {
        String sectionsJson = """
                {"sections":[
                  {"id":10,"name":"Inventory","parent_id":null,"depth":0},
                  {"id":20,"name":"MARC","parent_id":null,"depth":0},
                  {"id":11,"name":"Search","parent_id":10,"depth":1},
                  {"id":21,"name":"MARC authority","parent_id":20,"depth":1},
                  {"id":12,"name":"Browse results","parent_id":11,"depth":2},
                  {"id":99,"name":"Deep leaf","parent_id":12,"depth":3}
                ]}""";
        server.expect(requestTo(
                "https://testrail.example.org/index.php?/api/v2/get_sections/12&limit=250&offset=0"))
                .andExpect(method(GET))
                .andRespond(withSuccess(sectionsJson, MediaType.APPLICATION_JSON));

        List<String> paths = connector.listSectionPaths();

        assertThat(paths).containsExactly(
                "Inventory",
                "Inventory \u203a Search",
                "Inventory \u203a Search \u203a Browse results",
                "MARC",
                "MARC \u203a MARC authority");
    }

    @Test
    void listSectionPathsExcludesSectionsWhenDepthExceedsTwo() {
        String sectionsJson = """
                {"sections":[
                  {"id":1,"name":"Root","parent_id":null,"depth":0},
                  {"id":2,"name":"Child","parent_id":1,"depth":1},
                  {"id":3,"name":"Grand","parent_id":2,"depth":2},
                  {"id":4,"name":"Great","parent_id":3,"depth":3}
                ]}""";
        server.expect(requestTo(
                "https://testrail.example.org/index.php?/api/v2/get_sections/12&limit=250&offset=0"))
                .andExpect(method(GET))
                .andRespond(withSuccess(sectionsJson, MediaType.APPLICATION_JSON));

        List<String> paths = connector.listSectionPaths();

        assertThat(paths).doesNotContain("Root \u203a Child \u203a Grand \u203a Great");
        assertThat(paths).contains("Root \u203a Child \u203a Grand");
    }
}
