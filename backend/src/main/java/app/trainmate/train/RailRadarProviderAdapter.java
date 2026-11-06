package app.trainmate.train;

import app.trainmate.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Provider adapter integrating RailRadar API (https://api.railradar.in/v1/trains/{trainNo}/live).
 * Provides live running train status, current station, and delay minutes for Indian Railways.
 */
public final class RailRadarProviderAdapter implements ProviderAdapter {
    private static final String DEFAULT_BASE_URL = "https://api.railradar.in/v1/trains/";

    private final String apiKey;
    private final String baseUrl;
    private HttpClient client;

    public RailRadarProviderAdapter(String apiKey) {
        this(apiKey, DEFAULT_BASE_URL);
    }

    public RailRadarProviderAdapter(String apiKey, String baseUrl) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : DEFAULT_BASE_URL;
    }

    private synchronized HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .build();
        }
        return client;
    }

    @Override
    public String name() {
        return "railradar";
    }

    @Override
    public ProviderStatus fetch(String trainNo, LocalDate journeyDate) throws ProviderException {
        if (apiKey.isEmpty()) {
            throw new ProviderException("RailRadar API key is not configured; live fetch skipped");
        }

        try {
            String url = baseUrl + trainNo + "/live";
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ProviderException("RailRadar returned HTTP " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode root = Json.tree(resp.body());
            if (!root.path("success").asBoolean(false)) {
                String errMsg = root.path("error").asText("Unknown RailRadar error");
                throw new ProviderException("RailRadar error for train " + trainNo + ": " + errMsg);
            }

            JsonNode data = root.path("data");
            String stationCode = data.path("currentLocation").path("stationCode").asText("");
            if (stationCode.isEmpty()) {
                stationCode = data.path("previousHalt").path("stationCode").asText("");
            }
            if (stationCode.isEmpty()) {
                stationCode = data.path("nextHalt").path("stationCode").asText("");
            }
            if (stationCode.isEmpty()) {
                throw new ProviderException("No station code available in live status for train " + trainNo);
            }

            int delay = data.path("delayMinutes").asInt(
                data.path("currentLocation").path("delayMinutes").asInt(0)
            );

            Instant observedAt = Instant.now();
            String lastUpdated = data.path("lastUpdatedAt").asText("");
            if (!lastUpdated.isEmpty()) {
                try {
                    observedAt = OffsetDateTime.parse(lastUpdated).toInstant();
                } catch (Exception ignored) {}
            }

            Map<String, Object> raw = new HashMap<>();
            raw.put("source", name());
            raw.put("station", stationCode);
            raw.put("delay", delay);
            raw.put("trainName", data.path("trainName").asText(""));
            raw.put("status", data.path("status").asText(""));
            raw.put("observed", observedAt.toString());

            return new ProviderStatus(trainNo, journeyDate, stationCode.toUpperCase(), delay, observedAt, raw);
        } catch (IOException | InterruptedException e) {
            throw new ProviderException("Network error contacting RailRadar for train " + trainNo + ": " + e.getMessage());
        } catch (Exception e) {
            if (e instanceof ProviderException pe) throw pe;
            throw new ProviderException("Failed to parse RailRadar response: " + e.getMessage());
        }
    }
}
