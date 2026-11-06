package org.folio.factory.cypressfactory.plan;

import org.folio.factory.cypressfactory.plan.TestPlanParser.AttachmentKind;
import org.marc4j.MarcStreamReader;
import org.marc4j.MarcStreamWriter;
import org.marc4j.marc.ControlField;
import org.marc4j.marc.DataField;
import org.marc4j.marc.MarcFactory;
import org.marc4j.marc.Record;
import org.marc4j.marc.Subfield;
import org.marc4j.marc.VariableField;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the LLM's breaker-text MARC content into real ISO 2709 bytes via marc4j, which
 * computes the leader/directory arithmetic by construction instead of trusting the model to
 * get fixed-width offsets right.
 *
 * <p>Deterministic and LLM-free. Used two ways: the case-gen worker calls {@link #build}
 * during the review loop purely to surface {@link Result#errors()} as regeneration feedback
 * (bytes discarded); {@code TestRailSyncAgentWorker} calls it again post-gate to get the bytes
 * actually uploaded. Running it twice is deliberate, not wasteful — a human can amend breaker
 * text at the gate, so the bytes must be built from whatever the approved artifact says, not
 * cached from generation time.</p>
 *
 * <p><b>Template merge, per field.</b> Every kind has a golden reference record
 * ({@code references/marc-examples/}, one real working record per kind supplied by MQA). The
 * model's breaker-text is a sparse overlay on top of it: any tag the model writes — including
 * the pseudo-tag {@code LDR} for the leader — replaces that tag's value outright; any tag the
 * model never mentions falls through from the golden record unchanged. This is deliberate, not
 * a fallback for missing input: administrative fields (leader, 008, 001, 005, ...) are usually
 * irrelevant to what a case is testing, so the model is expected to leave most of them out and
 * let the template carry them — and to write one explicitly only when the scenario is actually
 * about that field's content (e.g. a case built around a specific 008 date).</p>
 */
public final class MarcFixtureBuilder {

    /** {@code =TAG  rest}: two spaces after the tag, exactly like real breaker-text tools emit. */
    private static final Pattern FIELD_LINE = Pattern.compile("^=(\\S+)\\s\\s(.*)$");
    private static final Pattern SUBFIELD_SPLIT = Pattern.compile("(?=\\$)");
    private static final String LEADER_TAG = "LDR";
    private static final int LEADER_LENGTH = 24;

    private static final Map<AttachmentKind, GoldenSkeleton> GOLDEN = loadGoldenSkeletons();

    private MarcFixtureBuilder() {
    }

    public record Result(byte[] bytes, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /** Every field of a real reference record, keyed by tag, in file order; leader kept separately. */
    private record GoldenSkeleton(String leader, Map<String, List<String>> fieldsByTag) {
    }

    /**
     * Splits multi-record breaker-text (blank-line-separated) into per-record blocks. A case
     * needing more than one record of the same kind (e.g. two bib records to link) writes them
     * one after another in the same fenced attachment, separated by a blank line.
     */
    public static List<String> splitRecords(String breakerText) {
        if (breakerText == null || breakerText.isBlank()) {
            return List.of(breakerText == null ? "" : breakerText);
        }
        List<String> blocks = new ArrayList<>();
        for (String block : breakerText.split("\n\\s*\n")) {
            if (!block.isBlank()) blocks.add(block);
        }
        if (blocks.isEmpty()) blocks.add(breakerText);
        return blocks;
    }

    /** {@link #build} applied to every record in {@link #splitRecords(String)}, in order. */
    public static List<Result> buildAll(String breakerText, AttachmentKind kind) {
        List<Result> results = new ArrayList<>();
        for (String block : splitRecords(breakerText)) {
            results.add(build(block, kind));
        }
        return results;
    }

    public static Result build(String breakerText, AttachmentKind kind) {
        List<String> errors = new ArrayList<>();
        if (kind == null || !kind.isMarc()) {
            errors.add("build() called with a non-MARC kind: " + kind);
            return new Result(null, errors);
        }
        if (breakerText == null || breakerText.isBlank()) {
            errors.add("no attachment content to build");
            return new Result(null, errors);
        }

        GoldenSkeleton golden = GOLDEN.get(kind);
        MarcFactory factory = MarcFactory.newInstance();

        List<String[]> modelLines = new ArrayList<>(); // {tag, rest}, LDR included
        Set<String> tagsFromModel = new LinkedHashSet<>();
        String leaderOverride = null;
        for (String line : breakerText.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty()) continue;
            Matcher m = FIELD_LINE.matcher(stripped);
            if (!m.matches()) {
                errors.add("malformed line (expected '=TAG  ...'): " + stripped);
                continue;
            }
            String tag = m.group(1);
            if (tag.equalsIgnoreCase(LEADER_TAG)) {
                leaderOverride = m.group(2);
            } else {
                tagsFromModel.add(tag);
                modelLines.add(new String[]{tag, m.group(2)});
            }
        }

        Record record;
        if (leaderOverride != null) {
            if (leaderOverride.length() != LEADER_LENGTH) {
                errors.add("LDR override must be exactly " + LEADER_LENGTH + " characters, got "
                        + leaderOverride.length() + ": " + leaderOverride);
                return new Result(null, errors);
            }
            try {
                record = factory.newRecord(factory.newLeader(leaderOverride));
            } catch (RuntimeException e) {
                errors.add("malformed LDR override: " + e.getMessage());
                return new Result(null, errors);
            }
        } else {
            record = factory.newRecord(factory.newLeader(golden.leader()));
        }

        boolean sawKeyField = false;
        for (String[] tagAndRest : modelLines) {
            boolean added = addField(record, factory, tagAndRest[0], tagAndRest[1], errors);
            if (added && isKeyField(kind, tagAndRest[0])) sawKeyField = true;
        }
        for (Map.Entry<String, List<String>> entry : golden.fieldsByTag().entrySet()) {
            if (tagsFromModel.contains(entry.getKey())) continue;
            for (String rest : entry.getValue()) {
                boolean added = addField(record, factory, entry.getKey(), rest, errors);
                if (added && isKeyField(kind, entry.getKey())) sawKeyField = true;
            }
        }

        if (!sawKeyField) {
            errors.add(missingKeyFieldMessage(kind));
        }
        if (!errors.isEmpty()) return new Result(null, errors);

        byte[] bytes = write(record);
        String roundTripError = roundTrip(bytes);
        if (roundTripError != null) {
            errors.add(roundTripError);
            return new Result(null, errors);
        }
        return new Result(bytes, errors);
    }

    /**
     * Renders every golden reference record in full, one section per kind, for the
     * generation prompt's few-shot block. Real content read straight from the checked-in
     * files — nothing here is hand-transcribed, so it can never drift from what
     * {@link #build} actually merges against.
     */
    public static String renderGoldenExamples() {
        StringBuilder sb = new StringBuilder();
        for (AttachmentKind kind : List.of(AttachmentKind.MARC_BIB, AttachmentKind.MARC_AUTHORITY,
                AttachmentKind.MARC_HOLDINGS)) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("### ").append(kind.spec()).append("\n\n");
            sb.append(renderAsBreakerText(GOLDEN.get(kind)));
        }
        return sb.toString();
    }

    private static String renderAsBreakerText(GoldenSkeleton golden) {
        StringBuilder sb = new StringBuilder("=").append(LEADER_TAG).append("  ").append(golden.leader());
        for (Map.Entry<String, List<String>> entry : golden.fieldsByTag().entrySet()) {
            for (String rest : entry.getValue()) {
                sb.append('\n').append('=').append(entry.getKey()).append("  ").append(rest);
            }
        }
        return sb.toString();
    }

    private static boolean addField(Record record, MarcFactory factory, String tag, String rest,
                                     List<String> errors) {
        if (!tag.matches("\\d{3}")) {
            errors.add("tag is not 3 digits: " + tag);
            return false;
        }

        if (Integer.parseInt(tag) < 10) {
            if (rest.isBlank()) {
                errors.add("control field " + tag + " has no data");
                return false;
            }
            record.addVariableField(factory.newControlField(tag, rest));
            return true;
        }

        if (rest.length() < 2) {
            errors.add("data field " + tag + " is missing its two indicators");
            return false;
        }
        char ind1 = normalizeIndicator(rest.charAt(0));
        char ind2 = normalizeIndicator(rest.charAt(1));
        String subfieldsPart = rest.substring(2);
        if (!subfieldsPart.startsWith("$")) {
            errors.add("data field " + tag + " has no subfields (expected '$a...' after the indicators)");
            return false;
        }

        DataField field = factory.newDataField(tag, ind1, ind2);
        int before = errors.size();
        for (String chunk : SUBFIELD_SPLIT.split(subfieldsPart)) {
            if (chunk.isEmpty()) continue;
            if (chunk.length() < 2) {
                errors.add("empty subfield code in field " + tag);
                continue;
            }
            char code = chunk.charAt(1);
            String value = chunk.substring(2).strip();
            if (value.isBlank()) {
                errors.add("empty subfield $" + code + " in field " + tag);
                continue;
            }
            field.addSubfield(factory.newSubfield(code, value));
        }
        if (field.getSubfields().isEmpty()) {
            if (errors.size() == before) {
                errors.add("data field " + tag + " has no usable subfields");
            }
            return false;
        }
        record.addVariableField(field);
        return true;
    }

    private static byte[] write(Record record) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MarcStreamWriter writer = new MarcStreamWriter(out, StandardCharsets.UTF_8.name());
        writer.write(record);
        writer.close();
        return out.toByteArray();
    }

    /** Free safety net per the design: a record that doesn't re-parse never reaches TestRail. */
    private static String roundTrip(byte[] bytes) {
        try {
            MarcStreamReader reader = new MarcStreamReader(new ByteArrayInputStream(bytes));
            if (!reader.hasNext() || reader.next() == null) {
                return "round-trip re-parse produced no record";
            }
        } catch (RuntimeException e) {
            return "round-trip re-parse failed: " + e.getMessage();
        }
        return null;
    }

    private static char normalizeIndicator(char c) {
        return c == '#' ? ' ' : c;
    }

    private static Map<AttachmentKind, GoldenSkeleton> loadGoldenSkeletons() {
        Map<AttachmentKind, GoldenSkeleton> map = new EnumMap<>(AttachmentKind.class);
        map.put(AttachmentKind.MARC_BIB, loadGolden("marcBibFileForC1332477.mrc"));
        map.put(AttachmentKind.MARC_AUTHORITY, loadGolden("marcAuthFileForC407009.mrc"));
        map.put(AttachmentKind.MARC_HOLDINGS, loadGolden("marcHoldingsFileForC397991.mrc"));
        return Map.copyOf(map);
    }

    /**
     * Fails at class-init, not at first fixture request - a missing reference file should
     * surface at app startup, the same way {@code SkillWorkspaceService} refuses to start
     * without SKILL.md, rather than as a confusing failure deep in a generation run.
     */
    private static GoldenSkeleton loadGolden(String resourceName) {
        String path = "/references/marc-examples/" + resourceName;
        try (InputStream in = MarcFixtureBuilder.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Golden MARC reference missing from classpath: " + path);
            }
            MarcStreamReader reader = new MarcStreamReader(in);
            if (!reader.hasNext()) {
                throw new IllegalStateException("Golden MARC reference has no record: " + path);
            }
            Record golden = reader.next();
            return new GoldenSkeleton(golden.getLeader().marshal(), groupByTag(golden));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read golden MARC reference: " + path, e);
        }
    }

    private static Map<String, List<String>> groupByTag(Record record) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (VariableField vf : record.getVariableFields()) {
            map.computeIfAbsent(vf.getTag(), t -> new ArrayList<>()).add(toRestString(vf));
        }
        return map;
    }

    /** Inverse of {@link #addField}: a real field's tag+rest, for merge-default and few-shot use. */
    private static String toRestString(VariableField vf) {
        if (vf instanceof ControlField cf) {
            return cf.getData();
        }
        DataField df = (DataField) vf;
        StringBuilder sb = new StringBuilder().append(df.getIndicator1()).append(df.getIndicator2());
        for (Subfield sf : df.getSubfields()) {
            sb.append('$').append(sf.getCode()).append(sf.getData());
        }
        return sb.toString();
    }

    private static boolean isKeyField(AttachmentKind kind, String tag) {
        return switch (kind) {
            case MARC_BIB -> tag.equals("245");
            case MARC_AUTHORITY -> tag.equals("100") || tag.equals("110") || tag.equals("111") || tag.equals("130");
            case MARC_HOLDINGS -> tag.equals("852");
            default -> false;
        };
    }

    private static String missingKeyFieldMessage(AttachmentKind kind) {
        return switch (kind) {
            case MARC_BIB -> "no 245 (title) field - a bib record needs a title";
            case MARC_AUTHORITY -> "no 1XX (main entry - 100/110/111/130) field - an authority record needs one";
            case MARC_HOLDINGS -> "no 852 (location) field - a holdings record needs one";
            default -> "missing required field";
        };
    }
}
