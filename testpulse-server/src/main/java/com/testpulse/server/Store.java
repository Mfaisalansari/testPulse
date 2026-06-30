package com.testpulse.server;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory data store with append-only JSONL persistence.
 *
 * <p><b>Persistence model.</b> Three JSONL files under the data directory:
 * {@code runs.jsonl}, {@code scenarios.jsonl}, {@code steps.jsonl}. Each
 * mutation appends a new line containing the full record state. On startup
 * the files are replayed; the last record for each ID wins. This is a
 * deliberately simple model — no schema, no migrations, last-write-wins is
 * exactly the semantics we want.
 *
 * <p>A malformed last line (interrupted write, partial flush) is skipped
 * during replay, so a process crash mid-write doesn't corrupt the store.
 *
 * <p><b>Concurrency.</b> The in-memory maps are concurrent. Writes to the
 * JSONL files are synchronized per-stream so two threads appending the same
 * record never interleave bytes mid-line.
 */
public final class Store {

    private static final Logger LOG = Logger.getLogger(Store.class.getName());
    private static final SecureRandom RAND = new SecureRandom();

    private final Path dataDir;
    private final Path runsFile;
    private final Path scenariosFile;
    private final Path stepsFile;

    private final Map<String, RunRecord> runs = new ConcurrentHashMap<String, RunRecord>();
    private final Map<String, ScenarioRecord> scenarios = new ConcurrentHashMap<String, ScenarioRecord>();
    // steps keyed by scenarioId → ordered list
    private final Map<String, List<StepRecord>> stepsByScenario = new ConcurrentHashMap<String, List<StepRecord>>();

    private final Object runsLock = new Object();
    private final Object scenariosLock = new Object();
    private final Object stepsLock = new Object();

    public Store(Path dataDir) throws IOException {
        this.dataDir = dataDir;
        Files.createDirectories(dataDir);
        this.runsFile = dataDir.resolve("runs.jsonl");
        this.scenariosFile = dataDir.resolve("scenarios.jsonl");
        this.stepsFile = dataDir.resolve("steps.jsonl");

        replay();
    }

    private void replay() {
        replayInto(runsFile, "run", new LineHandler() {
            @Override public void handle(Map<String, Object> m) {
                RunRecord r = RunRecord.fromMap(m);
                runs.put(r.id, r);
            }
        });
        replayInto(scenariosFile, "scenario", new LineHandler() {
            @Override public void handle(Map<String, Object> m) {
                ScenarioRecord s = ScenarioRecord.fromMap(m);
                scenarios.put(s.id, s);
            }
        });
        replayInto(stepsFile, "step", new LineHandler() {
            @Override public void handle(Map<String, Object> m) {
                StepRecord st = StepRecord.fromMap(m);
                List<StepRecord> list = stepsByScenario.get(st.scenarioId);
                if (list == null) {
                    list = Collections.synchronizedList(new ArrayList<StepRecord>());
                    stepsByScenario.put(st.scenarioId, list);
                }
                list.add(st);
            }
        });

        LOG.info("Replayed " + runs.size() + " run(s), "
                + scenarios.size() + " scenario(s), "
                + countSteps() + " step(s)");
    }

    private int countSteps() {
        int total = 0;
        for (List<StepRecord> list : stepsByScenario.values()) total += list.size();
        return total;
    }

    private interface LineHandler {
        void handle(Map<String, Object> m);
    }

    private void replayInto(Path file, String label, LineHandler handler) {
        if (!Files.exists(file)) return;
        int n = 0, bad = 0;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                try {
                    Map<String, Object> m = Json.parseObject(line);
                    handler.handle(m);
                    n++;
                } catch (Exception e) {
                    bad++;
                    // Last-line corruption from interrupted writes is expected;
                    // log at FINE, not WARNING.
                    LOG.log(Level.FINE, "Skipping malformed " + label + " line: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warning("Replay failed for " + file + ": " + e.getMessage());
        }
        if (bad > 0) {
            LOG.info("Replayed " + label + ": " + n + " good, " + bad + " skipped");
        }
    }

    /* ----------------------- Runs ----------------------- */

    public RunRecord createRun(RunRecord r) throws IOException {
        if (r.id == null) r.id = "r_" + randId();
        if (r.createdAt == null) r.createdAt = Instant.now();
        if (r.status == null) r.status = "running";
        runs.put(r.id, r);
        appendLine(runsFile, runsLock, r.toMap());
        return r;
    }

    public RunRecord getRun(String id) {
        return runs.get(id);
    }

    public List<RunRecord> listRuns(Map<String, String> filters, int limit) {
        List<RunRecord> all = new ArrayList<RunRecord>(runs.values());
        Collections.sort(all, new Comparator<RunRecord>() {
            @Override public int compare(RunRecord a, RunRecord b) {
                Instant ai = a.createdAt == null ? Instant.EPOCH : a.createdAt;
                Instant bi = b.createdAt == null ? Instant.EPOCH : b.createdAt;
                return bi.compareTo(ai); // newest first
            }
        });

        List<RunRecord> out = new ArrayList<RunRecord>();
        for (RunRecord r : all) {
            if (!matches(r, filters)) continue;
            out.add(r);
            if (limit > 0 && out.size() >= limit) break;
        }
        return out;
    }

    private boolean matches(RunRecord r, Map<String, String> filters) {
        if (filters == null) return true;
        for (Map.Entry<String, String> f : filters.entrySet()) {
            String key = f.getKey();
            String want = f.getValue();
            if (want == null || want.isEmpty()) continue;
            String have = runFieldValue(r, key);
            if (have == null || !have.equalsIgnoreCase(want)) return false;
        }
        return true;
    }

    private String runFieldValue(RunRecord r, String field) {
        if ("division".equals(field)) return r.division;
        if ("release".equals(field)) return r.release;
        if ("environment".equals(field)) return r.environment;
        if ("user".equals(field)) return r.user;
        if ("status".equals(field)) return r.status;
        return null;
    }

    public RunRecord finalizeRun(String id, Instant finishedAt, long durationMs,
                                  int total, int passed, int failed, int skipped) throws IOException {
        RunRecord r = runs.get(id);
        if (r == null) return null;
        r.finishedAt = finishedAt;
        r.durationMs = durationMs;
        r.scenariosTotal = total;
        r.scenariosPassed = passed;
        r.scenariosFailed = failed;
        r.scenariosSkipped = skipped;
        r.status = failed > 0 ? "failed" : (total == 0 ? "empty" : "passed");
        appendLine(runsFile, runsLock, r.toMap());
        return r;
    }

    /* ----------------------- Scenarios ----------------------- */

    public ScenarioRecord createScenario(ScenarioRecord s) throws IOException {
        if (s.id == null) s.id = "s_" + randId();
        if (s.createdAt == null) s.createdAt = Instant.now();
        if (s.status == null) s.status = "running";
        scenarios.put(s.id, s);
        appendLine(scenariosFile, scenariosLock, s.toMap());

        // Bump run's scenario count
        RunRecord r = runs.get(s.runId);
        if (r != null) {
            r.scenariosTotal++;
            appendLine(runsFile, runsLock, r.toMap());
        }

        return s;
    }

    public ScenarioRecord finishScenario(String id, String status,
                                          String screenshotBase64, Instant finishedAt) throws IOException {
        ScenarioRecord s = scenarios.get(id);
        if (s == null) return null;
        s.status = status == null ? "unknown" : status.toLowerCase();
        s.screenshotBase64 = screenshotBase64;
        s.finishedAt = finishedAt;
        if (s.createdAt != null && s.finishedAt != null) {
            s.durationMs = finishedAt.toEpochMilli() - s.createdAt.toEpochMilli();
        }
        appendLine(scenariosFile, scenariosLock, s.toMap());

        // Update run counters
        RunRecord r = runs.get(s.runId);
        if (r != null) {
            if ("passed".equalsIgnoreCase(s.status)) r.scenariosPassed++;
            else if ("failed".equalsIgnoreCase(s.status)) r.scenariosFailed++;
            else if ("skipped".equalsIgnoreCase(s.status)) r.scenariosSkipped++;
            appendLine(runsFile, runsLock, r.toMap());
        }
        return s;
    }

    public ScenarioRecord getScenario(String id) {
        return scenarios.get(id);
    }

    public List<ScenarioRecord> listScenariosForRun(String runId) {
        List<ScenarioRecord> out = new ArrayList<ScenarioRecord>();
        for (ScenarioRecord s : scenarios.values()) {
            if (runId.equals(s.runId)) out.add(s);
        }
        Collections.sort(out, new Comparator<ScenarioRecord>() {
            @Override public int compare(ScenarioRecord a, ScenarioRecord b) {
                Instant ai = a.createdAt == null ? Instant.EPOCH : a.createdAt;
                Instant bi = b.createdAt == null ? Instant.EPOCH : b.createdAt;
                return ai.compareTo(bi);
            }
        });
        return out;
    }

    /* ----------------------- Steps ----------------------- */

    public StepRecord addStep(StepRecord st) throws IOException {
        if (st.id == null) st.id = "st_" + randId();
        if (st.loggedAt == null) st.loggedAt = Instant.now();
        List<StepRecord> list = stepsByScenario.get(st.scenarioId);
        if (list == null) {
            synchronized (stepsLock) {
                list = stepsByScenario.get(st.scenarioId);
                if (list == null) {
                    list = Collections.synchronizedList(new ArrayList<StepRecord>());
                    stepsByScenario.put(st.scenarioId, list);
                }
            }
        }
        list.add(st);
        appendLine(stepsFile, stepsLock, st.toMap());
        return st;
    }

    public List<StepRecord> getStepsForScenario(String scenarioId) {
        List<StepRecord> list = stepsByScenario.get(scenarioId);
        if (list == null) return Collections.emptyList();
        synchronized (list) {
            return new ArrayList<StepRecord>(list);
        }
    }

    /* ----------------------- File append ----------------------- */

    private void appendLine(Path file, Object lock, Map<String, Object> record) throws IOException {
        String line = Json.write(record) + "\n";
        synchronized (lock) {
            BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            try {
                w.write(line);
            } finally {
                w.close();
            }
        }
    }

    private static String randId() {
        byte[] b = new byte[6];
        RAND.nextBytes(b);
        StringBuilder sb = new StringBuilder(12);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /* ----------------------- Record types ----------------------- */

    public static final class RunRecord {
        public String id;
        public Instant createdAt;
        public Instant finishedAt;
        public long durationMs;
        public String division;
        public String release;
        public String user;
        public String environment;
        public String branch;
        public String triggerSource;
        public String status;          // running | passed | failed | empty
        public int scenariosTotal;
        public int scenariosPassed;
        public int scenariosFailed;
        public int scenariosSkipped;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("createdAt", createdAt == null ? null : createdAt.toString());
            m.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            m.put("durationMs", durationMs);
            m.put("division", division);
            m.put("release", release);
            m.put("user", user);
            m.put("environment", environment);
            m.put("branch", branch);
            m.put("triggerSource", triggerSource);
            m.put("status", status);
            m.put("scenariosTotal", scenariosTotal);
            m.put("scenariosPassed", scenariosPassed);
            m.put("scenariosFailed", scenariosFailed);
            m.put("scenariosSkipped", scenariosSkipped);
            return m;
        }

        public static RunRecord fromMap(Map<String, Object> m) {
            RunRecord r = new RunRecord();
            r.id = Json.str(m, "id");
            r.createdAt = parseInstant(Json.str(m, "createdAt"));
            r.finishedAt = parseInstant(Json.str(m, "finishedAt"));
            r.durationMs = Json.lng(m, "durationMs", 0);
            r.division = Json.str(m, "division");
            r.release = Json.str(m, "release");
            r.user = Json.str(m, "user");
            r.environment = Json.str(m, "environment");
            r.branch = Json.str(m, "branch");
            r.triggerSource = Json.str(m, "triggerSource");
            r.status = Json.str(m, "status");
            r.scenariosTotal = (int) Json.lng(m, "scenariosTotal", 0);
            r.scenariosPassed = (int) Json.lng(m, "scenariosPassed", 0);
            r.scenariosFailed = (int) Json.lng(m, "scenariosFailed", 0);
            r.scenariosSkipped = (int) Json.lng(m, "scenariosSkipped", 0);
            return r;
        }
    }

    public static final class ScenarioRecord {
        public String id;
        public String runId;
        public String name;
        public String cucumberId;
        public String featurePath;
        public List<String> tags;
        public String lob;
        public String threadName;
        public Instant createdAt;
        public Instant finishedAt;
        public long durationMs;
        public String status;          // running | passed | failed | skipped | pending | undefined
        public String screenshotBase64;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("runId", runId);
            m.put("name", name);
            m.put("cucumberId", cucumberId);
            m.put("featurePath", featurePath);
            m.put("tags", tags);
            m.put("lob", lob);
            m.put("threadName", threadName);
            m.put("createdAt", createdAt == null ? null : createdAt.toString());
            m.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            m.put("durationMs", durationMs);
            m.put("status", status);
            m.put("screenshotBase64", screenshotBase64);
            return m;
        }

        public static ScenarioRecord fromMap(Map<String, Object> m) {
            ScenarioRecord s = new ScenarioRecord();
            s.id = Json.str(m, "id");
            s.runId = Json.str(m, "runId");
            s.name = Json.str(m, "name");
            s.cucumberId = Json.str(m, "cucumberId");
            s.featurePath = Json.str(m, "featurePath");
            s.tags = Json.strList(m, "tags");
            s.lob = Json.str(m, "lob");
            s.threadName = Json.str(m, "threadName");
            s.createdAt = parseInstant(Json.str(m, "createdAt"));
            s.finishedAt = parseInstant(Json.str(m, "finishedAt"));
            s.durationMs = Json.lng(m, "durationMs", 0);
            s.status = Json.str(m, "status");
            s.screenshotBase64 = Json.str(m, "screenshotBase64");
            return s;
        }
    }

    public static final class StepRecord {
        public String id;
        public String scenarioId;
        public String runId;
        public String stepText;
        public String status;
        public long durationMs;
        public String errorStackTrace;
        public Instant loggedAt;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("scenarioId", scenarioId);
            m.put("runId", runId);
            m.put("stepText", stepText);
            m.put("status", status);
            m.put("durationMs", durationMs);
            m.put("errorStackTrace", errorStackTrace);
            m.put("loggedAt", loggedAt == null ? null : loggedAt.toString());
            return m;
        }

        public static StepRecord fromMap(Map<String, Object> m) {
            StepRecord st = new StepRecord();
            st.id = Json.str(m, "id");
            st.scenarioId = Json.str(m, "scenarioId");
            st.runId = Json.str(m, "runId");
            st.stepText = Json.str(m, "stepText");
            st.status = Json.str(m, "status");
            st.durationMs = Json.lng(m, "durationMs", 0);
            st.errorStackTrace = Json.str(m, "errorStackTrace");
            st.loggedAt = parseInstant(Json.str(m, "loggedAt"));
            return st;
        }
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Instant.parse(s); } catch (Exception e) { return null; }
    }
}
