package com.pulsar.translate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/translate/languages")
public class TranslateLanguagesController {

    private final List<Map<String, Object>> languages;

    public TranslateLanguagesController(ObjectMapper mapper) throws Exception {
        var resource = new ClassPathResource("languages.json");
        this.languages = mapper.readValue(resource.getInputStream(),
            new TypeReference<List<Map<String, Object>>>() {});
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
            .body(languages);
    }
}
