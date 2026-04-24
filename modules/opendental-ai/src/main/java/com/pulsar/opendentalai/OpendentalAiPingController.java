package com.pulsar.opendentalai;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/opendental-ai")
@RequireModule("opendental-ai")
public class OpendentalAiPingController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        var t = TenantContext.require();
        return Map.of(
            "module", "opendental-ai",
            "domain", "dental",
            "tenant", t.slug(),
            "ok", true
        );
    }
}
