package com.pulsar.kernel.security;

import com.pulsar.kernel.tenant.TenantContext;
import com.pulsar.kernel.tenant.TenantInfo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RequireModuleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod hm)) return true;

        RequireModule ann = hm.getMethodAnnotation(RequireModule.class);
        if (ann == null) ann = hm.getBeanType().getAnnotation(RequireModule.class);
        if (ann == null) return true;

        TenantInfo t = TenantContext.get();
        if (t == null) { res.sendError(HttpServletResponse.SC_UNAUTHORIZED, "no_tenant"); return false; }
        if (t.suspended()) { res.sendError(HttpServletResponse.SC_FORBIDDEN, "tenant_suspended"); return false; }
        if (!t.activeModules().contains(ann.value())) {
            res.sendError(HttpServletResponse.SC_FORBIDDEN, "module_not_active");
            return false;
        }
        return true;
    }
}
