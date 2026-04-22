-- Per-tenant branding override. Fields absent from this JSON fall back to the
-- domain-level defaults served by BrandingController.
ALTER TABLE public_tenants
    ADD COLUMN branding JSON NULL;
