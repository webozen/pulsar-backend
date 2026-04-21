package com.pulsar.ainotes.plaud;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class PlaudClient {

    private static final Map<String, String> BROWSER_HEADERS = Map.ofEntries(
        Map.entry("Accept", "*/*"),
        Map.entry("Accept-Language", "en-GB,en-US;q=0.9,en;q=0.8"),
        Map.entry("Origin", "https://web.plaud.ai"),
        Map.entry("Referer", "https://web.plaud.ai/"),
        Map.entry("User-Agent",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.6 Safari/605.1.15"),
        Map.entry("Sec-Fetch-Site", "same-site"),
        Map.entry("Sec-Fetch-Mode", "cors"),
        Map.entry("Sec-Fetch-Dest", "empty"),
        Map.entry("app-platform", "web"),
        Map.entry("edit-from", "web"),
        Map.entry("Priority", "u=3, i")
    );

    private final String baseUrl;
    private final String bearerToken;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public PlaudClient(String baseUrl, String bearerToken, RestClient restClient, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.bearerToken = bearerToken;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> listRecordings(int limit, int skip) {
        String uri = baseUrl + "/file/simple/web"
            + "?skip=" + skip + "&limit=" + limit
            + "&is_trash=0&sort_by=start_time&is_desc=true"
            + "&r=" + ThreadLocalRandom.current().nextDouble();
        Map<String, Object> resp = exchange("GET", URI.create(uri), null);
        Object list = resp.get("data_file_list");
        if (!(list instanceof List<?> raw)) return List.of();
        return raw.stream()
            .filter(o -> o instanceof Map)
            .map(o -> objectMapper.convertValue(o, new TypeReference<Map<String, Object>>() {}))
            .toList();
    }

    public Map<String, Object> getFileRaw(String fileId) {
        Map<String, Object> resp = exchange("POST", URI.create(baseUrl + "/file/list"), List.of(fileId));
        Object list = resp.get("data_file_list");
        if (!(list instanceof List<?> files) || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recording not found: " + fileId);
        }
        return objectMapper.convertValue(files.get(0), new TypeReference<Map<String, Object>>() {});
    }

    private Map<String, Object> exchange(String method, URI uri, Object body) {
        try {
            RestClient.RequestBodySpec spec = restClient
                .method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(uri)
                .headers(h -> {
                    BROWSER_HEADERS.forEach(h::set);
                    h.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
                    h.setContentType(MediaType.APPLICATION_JSON);
                });
            if (body != null) spec.body(body);
            return spec.retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    if (res.getStatusCode().value() == 404)
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "plaud: not found");
                    if (res.getStatusCode().value() == 401)
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                            "Plaud token expired — reconnect in Settings");
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "plaud client error: " + res.getStatusCode());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "plaud server error: " + res.getStatusCode());
                })
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "plaud call failed: " + e.getMessage(), e);
        }
    }
}
