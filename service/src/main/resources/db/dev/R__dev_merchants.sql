-- DEVELOPMENT ONLY. Loaded only with the `dev` Spring profile (see application-dev.yml).
-- Seeds two demo merchants. Only SHA-256 hashes of their API keys are stored; the plaintext dev keys
-- are documented in README.md ("Development credentials") and must never be used anywhere else.
-- Repeatable migration: safe to re-run, existing rows are left unchanged.

INSERT INTO merchant (id, name) VALUES
    ('0199d000-0000-7000-8000-000000000a11', 'Dev Merchant Alice'),
    ('0199d000-0000-7000-8000-000000000b0b', 'Dev Merchant Bob')
ON CONFLICT (id) DO NOTHING;

INSERT INTO api_key (merchant_id, key_prefix, key_hash) VALUES
    ('0199d000-0000-7000-8000-000000000a11', 'devalice',
     decode('63a23bf0f73332404d89c374461bd8d1bf6b10e18d6395e53400e813134cc4d6', 'hex')),
    ('0199d000-0000-7000-8000-000000000b0b', 'devbob00',
     decode('a9c516e9be55893ce078a7604f14175f52cc5d9971b31a4539ba31aa09b618ed', 'hex'))
ON CONFLICT (key_prefix) DO NOTHING;
