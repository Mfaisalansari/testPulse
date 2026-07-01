package com.testpulse.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Routes every request under {@code /api/*}. Library ingest endpoints
 * authenticate via {@code X-Api-Key}; dashboard read endpoints accept the
 * same header OR an {@code apiKey} query parameter (browsers can't set
 * headers on {@code EventSource}).
 *
 * <p>Routing table:
 * <pre>
 * Library ingest:
 *   POST   /api/runs
 *   POST   /api/runs/{runId}/scenarios
 *   POST   /api/runs/{runId}/scenarios/{scenarioId}/steps
 *   PATCH  /api/runs/{runId}/scenarios/{scenarioId}/finish
 *   PATCH  /api/runs/{runId}/finalize
 *
 * Dashboard reads:
 *   GET    /api/runs
 *   GET    /api/runs/{runId}
 *   GET    /api/runs/{runId}/scenarios
 *   GET    /api/scenarios/{scenarioId}/steps
 *   GET    /api/runs/{runId}/stream      (SSE)
 *   GET    /api/stream                   (SSE, global)
 *   GET    /api/analytics/heatmap
 *   GET    /api/analytics/trend
 * </pre>
 */
public final class ApiHandler implements HttpHandler {

    private static final Logger LOG = Logger.getLogger(ApiHandler.class.getName());

    private final Store store;
    private final Sse sse;
    private final ApiKeys apiKeys;

    public ApiHandler(Store store, Sse sse, ApiKeys apiKeys) {
        this.store = store;
        this.sse = sse;
        this.apiKeys = apiKeys;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        try {
            // CORS / preflight (dashboard might be served from a different origin in dev)
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Access-Control-Allow-Headers", "X-Api-Key, Content-Type");
            ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PATCH, OPTIONS");
            if ("OPTIONS".equals(method)) {
                ex.sendResponseHeaders(204, -1);
                return;
            }

            // Authenticate
            ApiKeys.Principal who = authenticate(ex);
            if (who == null) {
                sendJson(ex, 401, errorJson("missing or invalid X-Api-Key"));
                return;
            }

            // Route
            String[] seg = pathSegments(path);

            // /api/stream — global SSE
            if (seg.length == 2 && "stream".equals(seg[1]) && "GET".equals(method)) {
                sse.register(Sse.GLOBAL, ex);
                return;
            }

            // /api/runs — list or create
            if (seg.length == 2 && "runs".equals(seg[1])) {
                if ("GET".equals(method)) { listRuns(ex); return; }
                if ("POST".equals(method)) { createRun(ex, who); return; }
            }

            // /api/runs/{runId} — get one
            if (seg.length == 3 && "runs".equals(seg[1])) {
                String runId = seg[2];
                if ("GET".equals(method)) { getRun(ex, runId); return; }
            }

            // /api/runs/{runId}/stream — SSE for one run
            if (seg.length == 4 && "runs".equals(seg[1]) && "stream".equals(seg[3])) {
                if ("GET".equals(method)) { sse.register(seg[2], ex); return; }
            }

            // /api/runs/{runId}/export — self-contained HTML report download
            if (seg.length == 4 && "runs".equals(seg[1]) && "export".equals(seg[3])) {
                if ("GET".equals(method)) { exportRun(ex, seg[2]); return; }
            }

            // /api/runs/{runId}/scenarios — list or create
            if (seg.length == 4 && "runs".equals(seg[1]) && "scenarios".equals(seg[3])) {
                String runId = seg[2];
                if ("GET".equals(method)) { listScenariosForRun(ex, runId); return; }
                if ("POST".equals(method)) { createScenario(ex, runId); return; }
            }

            // /api/runs/{runId}/finalize  (accept POST too — async dispatchers often only POST)
            if (seg.length == 4 && "runs".equals(seg[1]) && "finalize".equals(seg[3])) {
                if ("PATCH".equals(method) || "POST".equals(method)) { finalizeRun(ex, seg[2]); return; }
            }

            // /api/runs/{runId}/scenarios/{scenarioId}/steps
            if (seg.length == 6 && "runs".equals(seg[1]) && "scenarios".equals(seg[3]) && "steps".equals(seg[5])) {
                if ("POST".equals(method)) { logStep(ex, seg[2], seg[4]); return; }
                if ("GET".equals(method))  { listSteps(ex, seg[4]);      return; }
            }

            // /api/runs/{runId}/scenarios/{scenarioId}/finish  (POST or PATCH)
            if (seg.length == 6 && "runs".equals(seg[1]) && "scenarios".equals(seg[3]) && "finish".equals(seg[5])) {
                if ("PATCH".equals(method) || "POST".equals(method)) { finishScenario(ex, seg[4]); return; }
            }

            // /api/scenarios/{scenarioId}/steps  (convenience read path)
            if (seg.length == 4 && "scenarios".equals(seg[1]) && "steps".equals(seg[3])) {
                if ("GET".equals(method)) { listSteps(ex, seg[2]); return; }
            }

            // /api/analytics/heatmap
            if (seg.length == 3 && "analytics".equals(seg[1]) && "heatmap".equals(seg[2])) {
                if ("GET".equals(method)) { analyticsHeatmap(ex); return; }
            }

            // /api/analytics/trend
            if (seg.length == 3 && "analytics".equals(seg[1]) && "trend".equals(seg[2])) {
                if ("GET".equals(method)) { analyticsTrend(ex); return; }
            }

            sendJson(ex, 404, errorJson("not found: " + method + " " + path));

        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Handler error for " + method + " " + path, t);
            try { sendJson(ex, 500, errorJson("internal error: " + t.getMessage())); }
            catch (Throwable ignored) {}
        } finally {
            // SSE handlers leave the exchange open; we close everything else.
            if (!isSseRequest(path, method)) {
                ex.close();
            }
        }
    }

    private boolean isSseRequest(String path, String method) {
        if (!"GET".equals(method)) return false;
        return path.endsWith("/stream");
    }

    /* --------------------- Library ingest endpoints --------------------- */

    private void createRun(HttpExchange ex, ApiKeys.Principal who) throws IOException {
        Map<String, Object> body = readJsonBody(ex);
        Store.RunRecord r = new Store.RunRecord();
        r.division = Json.str(body, "division");
        r.release = Json.str(body, "release");
        r.user = pickUser(Json.str(body, "user"), who);
        r.environment = Json.str(body, "environment");
        r.branch = Json.str(body, "branch");
        r.triggerSource = Json.str(body, "triggerSource");
        String startedStr = Json.str(body, "startedAt");
        if (startedStr != null) try { r.createdAt = Instant.parse(startedStr); } catch (Exception ignored) {}

        Store.RunRecord created = store.createRun(r);
        sse.broadcast(Sse.GLOBAL, "run.created", created.toMap());

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("runId", created.id);
        sendJson(ex, 201, Json.write(resp));
    }

    private void createScenario(HttpExchange ex, String runId) throws IOException {
        if (store.getRun(runId) == null) {
            sendJson(ex, 404, errorJson("run not found: " + runId));
            return;
        }
        Map<String, Object> body = readJsonBody(ex);
        Store.ScenarioRecord s = new Store.ScenarioRecord();
        s.runId = runId;
        s.name = Json.str(body, "name");
        s.cucumberId = Json.str(body, "cucumberId");
        s.featurePath = Json.str(body, "featurePath");
        s.tags = Json.strList(body, "tags");
        s.lob = Json.str(body, "lob");
        s.threadName = Json.str(body, "threadName");
        String startedStr = Json.str(body, "startedAt");
        if (startedStr != null) try { s.createdAt = Instant.parse(startedStr); } catch (Exception ignored) {}

        Store.ScenarioRecord created = store.createScenario(s);
        sse.broadcast(runId, "scenario.started", created.toMap());

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("scenarioId", created.id);
        sendJson(ex, 201, Json.write(resp));
    }

    private void logStep(HttpExchange ex, String runId, String scenarioId) throws IOException {
        if (store.getScenario(scenarioId) == null) {
            sendJson(ex, 404, errorJson("scenario not found: " + scenarioId));
            return;
        }
        Map<String, Object> body = readJsonBody(ex);
        Store.StepRecord st = new Store.StepRecord();
        st.runId = runId;
        st.scenarioId = scenarioId;
        st.stepText = Json.str(body, "stepText");
        st.status = Json.str(body, "status");
        st.durationMs = Json.lng(body, "durationMs", 0);
        st.errorStackTrace = Json.str(body, "errorStackTrace");
        String at = Json.str(body, "occurredAt");
        if (at != null) try { st.loggedAt = Instant.parse(at); } catch (Exception ignored) {}

        Store.StepRecord added = store.addStep(st);
        sse.broadcast(runId, "step.logged", added.toMap());

        sendJson(ex, 204, "");
    }

    private void finishScenario(HttpExchange ex, String scenarioId) throws IOException {
        if (store.getScenario(scenarioId) == null) {
            sendJson(ex, 404, errorJson("scenario not found: " + scenarioId));
            return;
        }
        Map<String, Object> body = readJsonBody(ex);
        String status = Json.str(body, "status");
        String screenshot = Json.str(body, "screenshotBase64");
        Instant finishedAt = null;
        String at = Json.str(body, "finishedAt");
        if (at != null) try { finishedAt = Instant.parse(at); } catch (Exception ignored) {}
        if (finishedAt == null) finishedAt = Instant.now();

        Store.ScenarioRecord updated = store.finishScenario(scenarioId, status, screenshot, finishedAt);
        if (updated != null) {
            sse.broadcast(updated.runId, "scenario.finished", updated.toMap());
        }
        sendJson(ex, 204, "");
    }

    private void finalizeRun(HttpExchange ex, String runId) throws IOException {
        if (store.getRun(runId) == null) {
            sendJson(ex, 404, errorJson("run not found: " + runId));
            return;
        }
        Map<String, Object> body = readJsonBody(ex);
        Instant finishedAt = null;
        String at = Json.str(body, "finishedAt");
        if (at != null) try { finishedAt = Instant.parse(at); } catch (Exception ignored) {}
        if (finishedAt == null) finishedAt = Instant.now();

        long durationMs = Json.lng(body, "durationMs", 0);
        int total = (int) Json.lng(body, "scenariosTotal", 0);
        int passed = (int) Json.lng(body, "scenariosPassed", 0);
        int failed = (int) Json.lng(body, "scenariosFailed", 0);
        int skipped = (int) Json.lng(body, "scenariosSkipped", 0);

        Store.RunRecord r = store.finalizeRun(runId, finishedAt, durationMs, total, passed, failed, skipped);
        if (r != null) {
            sse.broadcast(runId, "run.finished", r.toMap());
            sse.broadcast(Sse.GLOBAL, "run.finished", r.toMap());
        }
        sendJson(ex, 204, "");
    }

    /* --------------------- Dashboard read endpoints --------------------- */

    private void listRuns(HttpExchange ex) throws IOException {
        Map<String, String> q = parseQuery(ex);
        int limit = (int) parseLong(q.get("limit"), 50);

        Map<String, String> filters = new HashMap<String, String>();
        for (String f : new String[] { "division", "release", "environment", "user", "status" }) {
            if (q.get(f) != null) filters.put(f, q.get(f));
        }
        List<Store.RunRecord> runs = store.listRuns(filters, limit);

        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Store.RunRecord r : runs) out.add(r.toMap());
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("runs", out);
        resp.put("count", out.size());
        sendJson(ex, 200, Json.write(resp));
    }

    private void getRun(HttpExchange ex, String runId) throws IOException {
        Store.RunRecord r = store.getRun(runId);
        if (r == null) { sendJson(ex, 404, errorJson("not found")); return; }

        List<Store.ScenarioRecord> scenarios = store.listScenariosForRun(runId);
        List<Map<String, Object>> scenList = new ArrayList<Map<String, Object>>();
        for (Store.ScenarioRecord s : scenarios) scenList.add(s.toMap());

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("run", r.toMap());
        resp.put("scenarios", scenList);
        sendJson(ex, 200, Json.write(resp));
    }

    /**
     * Generate a self-contained HTML report for a single run. All data
     * (run metadata, scenarios, steps, screenshots) is embedded inline —
     * the file opens in any browser without a server or network.
     */
    private void exportRun(HttpExchange ex, String runId) throws IOException {
        Store.RunRecord r = store.getRun(runId);
        if (r == null) { sendJson(ex, 404, errorJson("run not found")); return; }

        List<Store.ScenarioRecord> scenarios = store.listScenariosForRun(runId);
        Map<String, List<Store.StepRecord>> stepsByScen = new LinkedHashMap<String, List<Store.StepRecord>>();
        for (Store.ScenarioRecord s : scenarios) {
            stepsByScen.put(s.id, store.getStepsForScenario(s.id));
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("run", r.toMap());
        List<Map<String, Object>> scenList = new ArrayList<Map<String, Object>>();
        for (Store.ScenarioRecord s : scenarios) {
            Map<String, Object> m = s.toMap();
            List<Map<String, Object>> stepList = new ArrayList<Map<String, Object>>();
            for (Store.StepRecord st : stepsByScen.get(s.id)) stepList.add(st.toMap());
            m.put("steps", stepList);
            scenList.add(m);
        }
        payload.put("scenarios", scenList);
        String dataJson = Json.write(payload);

        String css = readClasspath("static/app.css");
        String template = readClasspath("static/export-template.html");
        if (template == null) {
            sendJson(ex, 500, errorJson("export template missing from server jar"));
            return;
        }
        String html = template
                .replace("/*__CSS__*/", css == null ? "" : css)
                .replace("/*__DATA__*/", dataJson);

        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().add("Content-Disposition",
                "attachment; filename=\"testpulse-" + runId + ".html\"");
        ex.sendResponseHeaders(200, bytes.length);
        OutputStream out = ex.getResponseBody();
        out.write(bytes);
        out.close();
    }

    private String readClasspath(String path) throws IOException {
        InputStream in = ApiHandler.class.getClassLoader().getResourceAsStream(path);
        if (in == null) return null;
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private void listScenariosForRun(HttpExchange ex, String runId) throws IOException {
        if (store.getRun(runId) == null) { sendJson(ex, 404, errorJson("run not found")); return; }
        List<Store.ScenarioRecord> scenarios = store.listScenariosForRun(runId);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Store.ScenarioRecord s : scenarios) out.add(s.toMap());
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("scenarios", out);
        sendJson(ex, 200, Json.write(resp));
    }

    private void listSteps(HttpExchange ex, String scenarioId) throws IOException {
        List<Store.StepRecord> steps = store.getStepsForScenario(scenarioId);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Store.StepRecord s : steps) out.add(s.toMap());
        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("steps", out);
        sendJson(ex, 200, Json.write(resp));
    }

    /* --------------------- Analytics --------------------- */

    private void analyticsHeatmap(HttpExchange ex) throws IOException {
        // Cells: (lob, date) -> pass rate
        // For each Run that has scenariosTotal > 0, contributes one cell
        Map<String, String> q = parseQuery(ex);
        int days = (int) parseLong(q.get("days"), 14);
        long since = Instant.now().minusSeconds(days * 86400L).toEpochMilli();

        Map<String, Map<String, int[]>> grid = new LinkedHashMap<String, Map<String, int[]>>();
        // grid: lob -> dateBucket -> [passed, total]

        for (Store.RunRecord r : store.listRuns(null, 0)) {
            if (r.createdAt == null) continue;
            if (r.createdAt.toEpochMilli() < since) continue;
            if (r.scenariosTotal == 0) continue;

            String dateBucket = r.createdAt
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .toString();

            // Sum per-LOB by walking scenarios
            for (Store.ScenarioRecord s : store.listScenariosForRun(r.id)) {
                String lob = s.lob == null ? "(unspecified)" : s.lob;
                Map<String, int[]> byDate = grid.get(lob);
                if (byDate == null) { byDate = new LinkedHashMap<String, int[]>(); grid.put(lob, byDate); }
                int[] cell = byDate.get(dateBucket);
                if (cell == null) { cell = new int[2]; byDate.put(dateBucket, cell); }
                cell[1]++;
                if ("passed".equalsIgnoreCase(s.status)) cell[0]++;
            }
        }

        List<Map<String, Object>> cells = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Map<String, int[]>> lobEntry : grid.entrySet()) {
            for (Map.Entry<String, int[]> dateEntry : lobEntry.getValue().entrySet()) {
                Map<String, Object> cell = new LinkedHashMap<String, Object>();
                cell.put("lob", lobEntry.getKey());
                cell.put("date", dateEntry.getKey());
                cell.put("passed", dateEntry.getValue()[0]);
                cell.put("total", dateEntry.getValue()[1]);
                double rate = dateEntry.getValue()[1] == 0 ? 0.0
                        : (100.0 * dateEntry.getValue()[0] / dateEntry.getValue()[1]);
                cell.put("passRate", Math.round(rate * 10.0) / 10.0);
                cells.add(cell);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("cells", cells);
        resp.put("days", days);
        sendJson(ex, 200, Json.write(resp));
    }

    private void analyticsTrend(HttpExchange ex) throws IOException {
        // Per-LOB pass-rate over runs in time order
        Map<String, String> q = parseQuery(ex);
        int days = (int) parseLong(q.get("days"), 30);
        long since = Instant.now().minusSeconds(days * 86400L).toEpochMilli();

        Map<String, List<Map<String, Object>>> series = new LinkedHashMap<String, List<Map<String, Object>>>();

        for (Store.RunRecord r : store.listRuns(null, 0)) {
            if (r.createdAt == null) continue;
            if (r.createdAt.toEpochMilli() < since) continue;
            if (r.scenariosTotal == 0) continue;

            // Bucket scenarios by LOB within this run
            Map<String, int[]> perLob = new LinkedHashMap<String, int[]>();
            for (Store.ScenarioRecord s : store.listScenariosForRun(r.id)) {
                String lob = s.lob == null ? "(unspecified)" : s.lob;
                int[] cell = perLob.get(lob);
                if (cell == null) { cell = new int[2]; perLob.put(lob, cell); }
                cell[1]++;
                if ("passed".equalsIgnoreCase(s.status)) cell[0]++;
            }
            for (Map.Entry<String, int[]> e : perLob.entrySet()) {
                List<Map<String, Object>> list = series.get(e.getKey());
                if (list == null) { list = new ArrayList<Map<String, Object>>(); series.put(e.getKey(), list); }
                double rate = e.getValue()[1] == 0 ? 0.0 : (100.0 * e.getValue()[0] / e.getValue()[1]);
                Map<String, Object> point = new LinkedHashMap<String, Object>();
                point.put("runId", r.id);
                point.put("at", r.createdAt.toString());
                point.put("passRate", Math.round(rate * 10.0) / 10.0);
                point.put("total", e.getValue()[1]);
                list.add(point);
            }
        }

        Map<String, Object> resp = new LinkedHashMap<String, Object>();
        resp.put("series", series);
        resp.put("days", days);
        sendJson(ex, 200, Json.write(resp));
    }

    /* --------------------- Helpers --------------------- */

    private ApiKeys.Principal authenticate(HttpExchange ex) {
        String key = ex.getRequestHeaders().getFirst("X-Api-Key");
        if (key == null) {
            // Query param fallback for SSE (EventSource can't set headers)
            Map<String, String> q = parseQuery(ex);
            key = q.get("apiKey");
        }
        return apiKeys.lookup(key);
    }

    private String pickUser(String fromBody, ApiKeys.Principal who) {
        if (fromBody != null && !fromBody.isEmpty()) return fromBody;
        return who == null ? null : who.userId;
    }

    private Map<String, Object> readJsonBody(HttpExchange ex) throws IOException {
        InputStream in = ex.getRequestBody();
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
        String s = baos.toString("UTF-8").trim();
        if (s.isEmpty()) return new LinkedHashMap<String, Object>();
        return Json.parseObject(s);
    }

    private void sendJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        if (bytes.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, bytes.length);
            OutputStream out = ex.getResponseBody();
            out.write(bytes);
            out.close();
        }
    }

    private String errorJson(String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", message);
        return Json.write(m);
    }

    private String[] pathSegments(String path) {
        if (path == null || path.isEmpty()) return new String[0];
        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) return new String[0];
        return path.split("/");
    }

    private Map<String, String> parseQuery(HttpExchange ex) {
        Map<String, String> out = new HashMap<String, String>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null || q.isEmpty()) return out;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) { out.put(decode(pair), ""); continue; }
            out.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
        }
        return out;
    }

    private String decode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return def; }
    }
}
