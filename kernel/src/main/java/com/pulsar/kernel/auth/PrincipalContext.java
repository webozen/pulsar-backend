package com.pulsar.kernel.auth;

public final class PrincipalContext {
    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();
    private PrincipalContext() {}

    public static void set(Principal p) { CURRENT.set(p); }
    public static Principal get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
