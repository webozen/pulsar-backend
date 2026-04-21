package com.pulsar.kernel.auth;

public sealed interface Principal permits Principal.Admin, Principal.TenantUser {
    String role();

    record Admin() implements Principal {
        @Override public String role() { return "super_admin"; }
    }

    record TenantUser(String slug, String email) implements Principal {
        @Override public String role() { return "tenant_user"; }
    }
}
