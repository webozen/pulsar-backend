package com.pulsar.kernel.tenant;

public final class TenantContext {
    private static final ThreadLocal<TenantInfo> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(TenantInfo info) { CURRENT.set(info); }
    public static TenantInfo get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    public static TenantInfo require() {
        TenantInfo info = CURRENT.get();
        if (info == null) throw new IllegalStateException("No tenant bound to current request");
        return info;
    }
}
