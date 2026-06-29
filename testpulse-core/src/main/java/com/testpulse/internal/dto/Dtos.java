package com.testpulse.internal.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Wire-format DTOs for the TestPulse server API. Kept as immutable Java 8
 * compatible classes nested inside {@link Dtos} to keep the type namespace
 * tight — public API types live in the parent package.
 */
public final class Dtos {

    private Dtos() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class RunRequest {
        public final String division;
        public final String release;
        public final String user;
        public final String environment;
        public final Instant startedAt;
        public final String branch;
        public final String triggerSource;

        public RunRequest(String division, String release, String user,
                          String environment, Instant startedAt,
                          String branch, String triggerSource) {
            this.division = division;
            this.release = release;
            this.user = user;
            this.environment = environment;
            this.startedAt = startedAt;
            this.branch = branch;
            this.triggerSource = triggerSource;
        }
    }

    public static final class RunResponse {
        public String runId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ScenarioRequest {
        public final String name;
        public final String cucumberId;
        public final String featurePath;
        public final List<String> tags;
        public final String lob;
        public final String threadName;
        public final Instant startedAt;

        public ScenarioRequest(String name, String cucumberId, String featurePath,
                               List<String> tags, String lob, String threadName,
                               Instant startedAt) {
            this.name = name;
            this.cucumberId = cucumberId;
            this.featurePath = featurePath;
            this.tags = tags != null ? tags : Collections.<String>emptyList();
            this.lob = lob;
            this.threadName = threadName;
            this.startedAt = startedAt;
        }
    }

    public static final class ScenarioResponse {
        public String scenarioId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ScenarioFinish {
        public final String status;
        public final String screenshotBase64;
        public final Instant finishedAt;

        public ScenarioFinish(String status, String screenshotBase64, Instant finishedAt) {
            this.status = status;
            this.screenshotBase64 = screenshotBase64;
            this.finishedAt = finishedAt;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class StepEvent {
        public final String stepText;
        public final String status;
        public final long durationMs;
        public final String errorStackTrace;
        public final Instant occurredAt;

        public StepEvent(String stepText, String status, long durationMs,
                         String errorStackTrace, Instant occurredAt) {
            this.stepText = stepText;
            this.status = status;
            this.durationMs = durationMs;
            this.errorStackTrace = errorStackTrace;
            this.occurredAt = occurredAt;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class RunSummary {
        public final Instant finishedAt;
        public final long durationMs;
        public final int scenariosTotal;
        public final int scenariosPassed;
        public final int scenariosFailed;
        public final int scenariosSkipped;

        public RunSummary(Instant finishedAt, long durationMs,
                          int scenariosTotal, int scenariosPassed,
                          int scenariosFailed, int scenariosSkipped) {
            this.finishedAt = finishedAt;
            this.durationMs = durationMs;
            this.scenariosTotal = scenariosTotal;
            this.scenariosPassed = scenariosPassed;
            this.scenariosFailed = scenariosFailed;
            this.scenariosSkipped = scenariosSkipped;
        }
    }
}
