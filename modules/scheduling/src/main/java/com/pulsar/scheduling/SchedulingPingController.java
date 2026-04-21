package com.pulsar.scheduling;

import com.pulsar.kernel.security.RequireModule;
import com.pulsar.kernel.tenant.TenantContext;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/scheduling")
@RequireModule("scheduling")
public class SchedulingPingController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        var t = TenantContext.require();
        return Map.of("module", "scheduling", "tenant", t.slug(), "ok", true);
    }
}
