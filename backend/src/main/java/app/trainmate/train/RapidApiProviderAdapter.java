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
import java.util.HashMap;
import java.util.Map;

/**
 * Live train status provider adapter querying Indian Railways live running status via RapidAPI.
 * Includes timeout protection, error parsing, and safe failure delegation.
 * When the key is absent or the endpoint fails, the caller falls back to timetable snapshots.
 */
public final class RapidApiProviderAdapter implements ProviderAdapter {
    private static final String DEFAULT_HOST = "irctc1.p.rapidapi.com";
    private static final String DEFAULT_URL = "https://irctc1.p.rapidapi.com/api/v1/liveTrainStatus";

    private final String apiKey;
    private final String host;
    private final String endpointUrl;
    private HttpClient client;

    public RapidApiProviderAdapter(String apiKey) {
        this(apiKey, DEFAULT_HOST, DEFAULT_URL);
    }

    public RapidApiProviderAdapter(String apiKey, String host, String endpointUrl) {
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.host = host != null && !host.isBlank() ? host.trim() : DEFAULT_HOST;
        this.endpointUrl = endpointUrl != null && !endpointUrl.isBlank() ? endpointUrl.trim() : DEFAULT_URL;
    }

    private synchronized HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        }
        return client;
    }

    @Override
    public String name() {
        return "rapidapi";
    }

    @Override
    public ProviderStatus fetch(String trainNo, LocalDate journeyDate) throws ProviderException {
        if (apiKey.isEmpty()) {
            throw new ProviderException("RapidAPI key is not configured; live fetch skipped");
        }

        try {
            String url = endpointUrl + "?trainNo=" + trainNo;
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("x-rapidapi-key", apiKey)
                .header("x-rapidapi-host", host)
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new ProviderException("RapidAPI returned HTTP " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode root = Json.tree(resp.body());
            JsonNode data = root.has("data") ? root.get("data") : root;

            String stationCode = null;
            if (data.hasNonNull("current_station_code")) {
                stationCode = data.get("current_station_code").asText().toUpperCase();
            } else if (data.hasNonNull("last_station_code")) {
                stationCode = data.get("last_station_code").asText().toUpperCase();
            }

            if (stationCode == null || stationCode.isBlank()) {
                throw new ProviderException("No current station in provider response for " + trainNo);
            }

            int delayMin = 0;
            if (data.hasNonNull("delay")) {
                delayMin = data.get("delay").asInt(0);
            } else if (data.hasNonNull("delay_in_minutes")) {
                delayMin = data.get("delay_in_minutes").asInt(0);
            }

            Instant observedAt = Instant.now();
            if (data.hasNonNull("updated_time")) {
                try {
                    observedAt = Instant.parse(data.get("updated_time").asText());
                } catch (Exception ignored) {}
            }

            Map<String, Object> raw = new HashMap<>();
            raw.put("source", name());
            raw.put("station", stationCode);
            raw.put("delay", delayMin);
            raw.put("observed", observedAt.toString());

            return new ProviderStatus(trainNo, journeyDate, stationCode, delayMin, observedAt, raw);
        } catch (IOException | InterruptedException e) {
            throw new ProviderException("Network error contacting RapidAPI for " + trainNo + ": " + e.getMessage());
        } catch (Exception e) {
            if (e instanceof ProviderException pe) throw pe;
            throw new ProviderException("Failed to parse provider response: " + e.getMessage());
        }
    }
}
