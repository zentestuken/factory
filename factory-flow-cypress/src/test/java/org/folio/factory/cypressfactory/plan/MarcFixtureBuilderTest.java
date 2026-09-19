package org.folio.factory.cypressfactory.plan;

import org.folio.factory.cypressfactory.plan.TestPlanParser.AttachmentKind;
import org.junit.jupiter.api.Test;
import org.marc4j.MarcStreamReader;
import org.marc4j.marc.ControlField;
import org.marc4j.marc.DataField;
import org.marc4j.marc.Record;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarcFixtureBuilderTest {

    @Test
    void buildsAValidBibRecordThatRoundTripsThroughMarc4j() {
        String breaker = """
                =020  ##$a9780000000001
                =245  10$aTest Title :$bA Subtitle /$cJane Author.
                """;

        MarcFixtureBuilder.Result result = MarcFixtureBuilder.build(breaker, AttachmentKind.MARC_BIB);

        assertThat(result.valid()).isTrue();
        assertThat(result.bytes()).isNotEmpty();

        Record record = reparse(result.bytes());
        assertThat(record.getLeader().getTypeOfRecord()).isEqualTo('a');
        assertThat(((DataField) record.getVariableField("245")).getSubfieldsAsString("a"))
                .isEqualTo("Test Title :");
        assertThat(((DataField) record.getVariableField("020")).getSubfieldsAsString("a"))
                .isEqualTo("9780000000001");
    }

    // ── Template merge: model overrides per tag, golden fills every gap ────────

    @Test
    void leaderAndAllUnmentionedFieldsFallBackToTheGoldenTemplate() {
        // The model wrote only 245 - leader, 001, 005 and 008 must all come from the golden
        // bib record untouched.
        Record record = reparse(MarcFixtureBuilder.build("=245  10$aA Title", AttachmentKind.MARC_BIB).bytes());
        Record golden = goldenRecord("marcBibFileForC1332477.mrc");

        // Positions 0-4 (record length) are always recomputed by the writer from actual content,
        // which differs here since the model's title is shorter than golden's - compare only the
        // part of the leader the template actually controls.
        assertThat(record.getLeader().marshal().substring(5)).isEqualTo(golden.getLeader().marshal().substring(5));
        assertThat(((ControlField) record.getVariableField("001")).getData())
                .isEqualTo(((ControlField) golden.getVariableField("001")).getData());
        assertThat(((ControlField) record.getVariableField("005")).getData())
                .isEqualTo(((ControlField) golden.getVariableField("005")).getData());
        assertThat(field008FromRecord(record)).isEqualTo(field008FromRecord(golden));
    }

    @Test
    void anAuthorityFieldTheModelNeverMentionsStillFallsBackToGolden() {
        // marcAuthFileForC407009.mrc also carries an 010 (LCCN-style id) alongside 100 - the
        // model only overrides 100, so 010 must still come through from the template.
        Record record = reparse(MarcFixtureBuilder.build(
                "=100  1#$aOverridden Heading", AttachmentKind.MARC_AUTHORITY).bytes());

        assertThat(record.getVariableField("010")).isNotNull();
        assertThat(((DataField) record.getVariableField("100")).getSubfieldsAsString("a"))
                .isEqualTo("Overridden Heading");
    }

    @Test
    void aModelSupplied008ReplacesTheGoldenValueEntirely() {
        String custom008 = "260101s2026    xxu           000 0 eng d";
        MarcFixtureBuilder.Result result = MarcFixtureBuilder.build(
                "=008  " + custom008 + "\n=245  10$aA Title", AttachmentKind.MARC_BIB);

        assertThat(result.valid()).isTrue();
        assertThat(field008(result)).isEqualTo(custom008);
        assertThat(field008(result)).isNotEqualTo(golden008("marcBibFileForC1332477.mrc"));
    }

    @Test
    void aModelSuppliedLeaderReplacesTheGoldenLeaderEntirely() {
        // 'c' at position 6 (type of record) - deliberately different from both golden bib's
        // 'a' and anything the writer recomputes, so this can only pass if the override held.
        String customLeader = "00050ccm a2200025 i 4500";
        MarcFixtureBuilder.Result result = MarcFixtureBuilder.build(
                "=LDR  " + customLeader + "\n=245  10$aA Title", AttachmentKind.MARC_BIB);

        assertThat(result.valid()).isTrue();
        assertThat(reparse(result.bytes()).getLeader().getTypeOfRecord()).isEqualTo('c');
    }

    @Test
    void aMalformedLeaderOverrideIsFlaggedNotSilentlyTruncated() {
        MarcFixtureBuilder.Result result = MarcFixtureBuilder.build(
                "=LDR  too short\n=245  10$aA Title", AttachmentKind.MARC_BIB);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("24 characters"));
    }

    @Test
    void renderGoldenExamplesIncludesAllThreeKindsWithLeaderAndAllFields() {
        String rendered = MarcFixtureBuilder.renderGoldenExamples();

        assertThat(rendered).contains("marc-bib").contains("marc-authority").contains("marc-holdings");
        assertThat(rendered).contains("=LDR  ").contains("=008  ").contains("=245  ").contains("=852  ");
    }

    @Test
    void acceptsAnyOfTheAuthorityMainEntryTags() {
        for (String tag : new String[]{"100", "110", "111", "130"}) {
            MarcFixtureBuilder.Result result = MarcFixtureBuilder.build(
                    "=" + tag + "  1#$aSome heading", AttachmentKind.MARC_AUTHORITY);
            assertThat(result.valid()).describedAs("tag %s", tag).isTrue();
        }
    }

    @Test
    void flagsMissingKeyFieldWhenTheModelOverridesItWithSomethingUnusable() {
        // Golden always back-fills the key field when the model doesn't mention it at all, so
        // "missing key field" can only fire when the model explicitly claims that tag and then
        // fails to produce a usable field - claiming it excludes golden's own fallback for it.
        assertThat(MarcFixtureBuilder.build("=245  10", AttachmentKind.MARC_BIB).errors())
                .anyMatch(e -> e.contains("245"));
        assertThat(MarcFixtureBuilder.build("=100  1#", AttachmentKind.MARC_AUTHORITY).errors())
                .anyMatch(e -> e.contains("1XX"));
        assertThat(MarcFixtureBuilder.build("=852  01", AttachmentKind.MARC_HOLDINGS).errors())
                .anyMatch(e -> e.contains("852"));
    }

    @Test
    void flagsAMalformedLine() {
        MarcFixtureBuilder.Result result = MarcFixtureBuilder.build(
                "245 10$aMissing the double space after the tag", AttachmentKind.MARC_BIB);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("malformed line"));
    }

    @Test
    void flagsADataFieldMissingIndicators() {
        MarcFixtureBuilder.Result result = MarcFixtureBuilder.build("=245  $aTitle", AttachmentKind.MARC_BIB);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("indicators"));
    }

    @Test
    void flagsADataFieldWithNoSubfields() {
        MarcFixtureBuilder.Result result = MarcFixtureBuilder.build("=245  10", AttachmentKind.MARC_BIB);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("no subfields"));
    }

    @Test
    void treatsHashAsABlankIndicator() {
        MarcFixtureBuilder.Result result = MarcFixtureBuilder.build(
                "=245  10$aTitle\n=020  ##$a9780000000001", AttachmentKind.MARC_BIB);

        assertThat(result.valid()).isTrue();
        Record record = reparse(result.bytes());
        assertThat(((DataField) record.getVariableField("020")).getSubfieldsAsString("a"))
                .isEqualTo("9780000000001");
    }

    @Test
    void parsesControlFieldsOtherThan008Verbatim() {
        MarcFixtureBuilder.Result result = MarcFixtureBuilder.build(
                "=001  in00000000123\n=245  10$aTitle", AttachmentKind.MARC_BIB);

        assertThat(result.valid()).isTrue();
        assertThat(((ControlField) reparse(result.bytes()).getVariableField("001")).getData())
                .isEqualTo("in00000000123");
    }

    @Test
    void flagsBlankContent() {
        assertThat(MarcFixtureBuilder.build("", AttachmentKind.MARC_BIB).valid()).isFalse();
        assertThat(MarcFixtureBuilder.build(null, AttachmentKind.MARC_BIB).valid()).isFalse();
    }

    @Test
    void refusesToBuildANonMarcKind() {
        MarcFixtureBuilder.Result result = MarcFixtureBuilder.build("a,b,c", AttachmentKind.CSV);

        assertThat(result.valid()).isFalse();
    }

    // ── Multi-record attachments (blank-line-separated) ────────────────────────

    @Test
    void splitRecordsReturnsOneBlockWhenThereIsNoBlankLine() {
        assertThat(MarcFixtureBuilder.splitRecords("=245  10$aA Title")).hasSize(1);
    }

    @Test
    void splitRecordsSplitsOnABlankLineBetweenRecords() {
        List<String> blocks = MarcFixtureBuilder.splitRecords(
                "=245  10$aFirst Title\n\n=245  10$aSecond Title");

        assertThat(blocks).containsExactly("=245  10$aFirst Title", "=245  10$aSecond Title");
    }

    @Test
    void buildAllBuildsOneRecordPerBlock() {
        List<MarcFixtureBuilder.Result> results = MarcFixtureBuilder.buildAll(
                "=245  10$aFirst Title\n\n=245  10$aSecond Title", AttachmentKind.MARC_BIB);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(MarcFixtureBuilder.Result::valid);
        assertThat(((DataField) reparse(results.get(0).bytes()).getVariableField("245")).getSubfieldsAsString("a"))
                .isEqualTo("First Title");
        assertThat(((DataField) reparse(results.get(1).bytes()).getVariableField("245")).getSubfieldsAsString("a"))
                .isEqualTo("Second Title");
    }

    @Test
    void buildAllReportsErrorsForTheSpecificBlockThatFailed() {
        List<MarcFixtureBuilder.Result> results = MarcFixtureBuilder.buildAll(
                "=245  10$aGood Title\n\n=245  10", AttachmentKind.MARC_BIB);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).valid()).isTrue();
        assertThat(results.get(1).valid()).isFalse();
    }

    private static Record reparse(byte[] bytes) {
        MarcStreamReader reader = new MarcStreamReader(new ByteArrayInputStream(bytes));
        assertThat(reader.hasNext()).isTrue();
        return reader.next();
    }

    private static String field008(MarcFixtureBuilder.Result result) {
        assertThat(result.valid()).as(String.valueOf(result.errors())).isTrue();
        return field008FromRecord(reparse(result.bytes()));
    }

    private static String field008FromRecord(Record record) {
        return ((ControlField) record.getVariableField("008")).getData();
    }

    private static Record goldenRecord(String resourceName) {
        try (var in = MarcFixtureBuilderTest.class.getResourceAsStream(
                "/references/marc-examples/" + resourceName)) {
            MarcStreamReader reader = new MarcStreamReader(in);
            assertThat(reader.hasNext()).isTrue();
            return reader.next();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String golden008(String resourceName) {
        return field008FromRecord(goldenRecord(resourceName));
    }
}
