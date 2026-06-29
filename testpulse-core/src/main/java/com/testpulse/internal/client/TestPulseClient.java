package com.testpulse.internal.client;

import com.testpulse.TestPulseConfig;
import com.testpulse.internal.dto.Dtos.RunRequest;
import com.testpulse.internal.dto.Dtos.RunResponse;
import com.testpulse.internal.dto.Dtos.RunSummary;
import com.testpulse.internal.dto.Dtos.ScenarioFinish;
import com.testpulse.internal.dto.Dtos.ScenarioRequest;
import com.testpulse.internal.dto.Dtos.ScenarioResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Synchronous HTTP client for operations that must return a server-assigned
 * identifier before the caller can proceed: create run, start scenario,
 * finish scenario, finalize run.
 *
 * <p>Each instance is bound to one {@link TestPulseConfig}, so a JVM running
 * tests against two TestPulse servers (e.g. staging + prod) would hold two
 * separate clients. The previous singleton design is gone — this is library
 * code now, not a static helper.
 */
public final class TestPulseClient {

    private static final Logger LOG = Logger.getLogger(TestPulseClient.class.getName());
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final TestPulseConfig config;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public TestPulseClient(TestPulseConfig config) {
        this.config = config;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(config.connectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(config.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(config.readTimeoutMs(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public String createRun(RunRequest req) {
        try {
            String body = postSync("/api/runs", mapper.writeValueAsString(req));
            RunResponse resp = mapper.readValue(body, RunResponse.class);
            LOG.info("TestPulse run created: " + resp.runId);
            return resp.runId;
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "createRun failed: " + e.getMessage());
            return null;
        }
    }

    public String startScenario(String runId, ScenarioRequest req) {
        if (runId == null) return null;
        try {
            String body = postSync("/api/runs/" + runId + "/scenarios",
                    mapper.writeValueAsString(req));
            ScenarioResponse resp = mapper.readValue(body, ScenarioResponse.class);
            return resp.scenarioId;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "startScenario failed: " + e.getMessage());
            return null;
        }
    }

    public void finishScenario(String runId, String scenarioId, ScenarioFinish finish) {
        if (runId == null || scenarioId == null) return;
        try {
            patchSync("/api/runs/" + runId + "/scenarios/" + scenarioId + "/finish",
                    mapper.writeValueAsString(finish));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "finishScenario failed: " + e.getMessage());
        }
    }

    public void finalizeRun(String runId, RunSummary summary) {
        if (runId == null) return;
        try {
            patchSync("/api/runs/" + runId + "/finalize",
                    mapper.writeValueAsString(summary));
            LOG.info("TestPulse run finalized: " + runId);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "finalizeRun failed: " + e.getMessage());
        }
    }

    String postSync(String path, String jsonBody) throws IOException {
        return execute(new Request.Builder()
                .url(config.url() + path)
                .header("X-Api-Key", config.apiKey())
                .post(RequestBody.create(JSON, jsonBody))
                .build());
    }

    String patchSync(String path, String jsonBody) throws IOException {
        return execute(new Request.Builder()
                .url(config.url() + path)
                .header("X-Api-Key", config.apiKey())
                .patch(RequestBody.create(JSON, jsonBody))
                .build());
    }

    private String execute(Request req) throws IOException {
        Response resp = null;
        try {
            resp = http.newCall(req).execute();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " " + resp.message()
                        + " from " + req.url());
            }
            return resp.body() != null ? resp.body().string() : "";
        } finally {
            if (resp != null) resp.close();
        }
    }

    public OkHttpClient httpClient() {
        return http;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public TestPulseConfig config() {
        return config;
    }
}
