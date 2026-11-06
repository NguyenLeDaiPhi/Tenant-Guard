CREATE TABLE public.tenants (
    id UUID PRIMARY KEY,
    slug VARCHAR(128) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL
);

INSERT INTO public.tenants (id, slug, status)
VALUES
    ('11111111-1111-4111-8111-111111111111', 'tenant-one', 'ACTIVE'),
    ('22222222-2222-4222-8222-222222222222', 'tenant-two', 'ACTIVE');
