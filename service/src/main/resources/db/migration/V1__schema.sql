-- ReserveCore schema. Design and rationale: docs/PLAN.md sections 3, 5, 6.
-- Every child table carries merchant_id and references its parent through a composite key that
-- includes merchant_id, so a row can never point at another merchant's data.

CREATE TABLE merchant (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE api_key (
    id          uuid        PRIMARY KEY DEFAULT uuidv7(),
    merchant_id uuid        NOT NULL REFERENCES merchant (id),
    key_prefix  text        NOT NULL UNIQUE,
    key_hash    bytea       NOT NULL CHECK (octet_length(key_hash) = 32),
    created_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz
);

CREATE TABLE inventory (
    id               uuid        PRIMARY KEY DEFAULT uuidv7(),
    merchant_id      uuid        NOT NULL REFERENCES merchant (id),
    sku              text        NOT NULL CHECK (sku ~ '^[A-Za-z0-9._-]{1,64}$'),
    initial_quantity integer     NOT NULL CHECK (initial_quantity >= 1),
    available        integer     NOT NULL CHECK (available >= 0),
    held             integer     NOT NULL CHECK (held >= 0),
    sold             integer     NOT NULL CHECK (sold >= 0),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT inventory_merchant_sku_key UNIQUE (merchant_id, sku),
    CONSTRAINT inventory_merchant_id_key UNIQUE (merchant_id, id),
    CONSTRAINT inventory_balance_check CHECK (initial_quantity = available + held + sold)
);

CREATE TABLE reservation (
    id           uuid        PRIMARY KEY DEFAULT uuidv7(),
    merchant_id  uuid        NOT NULL,
    inventory_id uuid        NOT NULL,
    quantity     integer     NOT NULL CHECK (quantity >= 1),
    status       text        NOT NULL CHECK (status IN ('ACTIVE', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    expires_at   timestamptz NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    resolved_at  timestamptz,
    CONSTRAINT reservation_merchant_id_key UNIQUE (merchant_id, id),
    CONSTRAINT reservation_inventory_fk FOREIGN KEY (merchant_id, inventory_id)
        REFERENCES inventory (merchant_id, id),
    CONSTRAINT reservation_resolved_check CHECK ((status = 'ACTIVE') = (resolved_at IS NULL))
);

-- Expiration worker scan: ACTIVE reservations ordered by expiry.
CREATE INDEX reservation_active_expires_idx ON reservation (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE inventory_movement (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    merchant_id     uuid        NOT NULL,
    inventory_id    uuid        NOT NULL,
    reservation_id  uuid,
    kind            text        NOT NULL
        CHECK (kind IN ('STOCK_CREATED', 'RESERVED', 'CONFIRMED', 'CANCELLED', 'EXPIRED')),
    quantity        integer     NOT NULL CHECK (quantity >= 1),
    available_after integer     NOT NULL,
    held_after      integer     NOT NULL,
    sold_after      integer     NOT NULL,
    request_id      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT movement_inventory_fk FOREIGN KEY (merchant_id, inventory_id)
        REFERENCES inventory (merchant_id, id),
    CONSTRAINT movement_reservation_fk FOREIGN KEY (merchant_id, reservation_id)
        REFERENCES reservation (merchant_id, id),
    CONSTRAINT movement_reservation_required_check
        CHECK ((kind = 'STOCK_CREATED') = (reservation_id IS NULL))
);

-- Held units of a reservation are sold or released at most once, whatever the code path.
CREATE UNIQUE INDEX movement_one_resolution_idx ON inventory_movement (reservation_id)
    WHERE kind IN ('CONFIRMED', 'CANCELLED', 'EXPIRED');

-- Movement history paging per SKU, newest first.
CREATE INDEX movement_inventory_id_idx ON inventory_movement (inventory_id, id DESC);

CREATE TABLE idempotency_record (
    merchant_id     uuid        NOT NULL REFERENCES merchant (id),
    operation       text        NOT NULL
        CHECK (operation IN ('CREATE_RESERVATION', 'CONFIRM_RESERVATION', 'CANCEL_RESERVATION')),
    idempotency_key text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 255),
    request_hash    bytea       NOT NULL CHECK (octet_length(request_hash) = 32),
    response_status integer,
    response_body   jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (merchant_id, operation, idempotency_key)
);

-- Retention purge scans by age.
CREATE INDEX idempotency_record_created_at_idx ON idempotency_record (created_at);
