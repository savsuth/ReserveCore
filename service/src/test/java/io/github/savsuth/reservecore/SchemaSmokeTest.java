package io.github.savsuth.reservecore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = "reservecore.workers.enabled=false")
@Import(TestcontainersConfiguration.class)
class SchemaSmokeTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void runsOnPostgres18WithV1Applied() {
        String version = jdbc.sql("SHOW server_version").query(String.class).single();
        assertThat(version).startsWith("18.");

        Integer applied = jdbc.sql("""
                SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success
                """).query(Integer.class).single();
        assertThat(applied).isEqualTo(1);
    }

    @Test
    void inventoryBalanceInvariantIsEnforcedByTheDatabase() {
        UUID merchant = insertMerchant();
        jdbc.sql("""
                INSERT INTO inventory (merchant_id, sku, initial_quantity, available, held, sold)
                VALUES (:m, 'BALANCED', 20, 15, 3, 2)
                """).param("m", merchant).update();

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE inventory SET available = available - 1 WHERE merchant_id = :m AND sku = 'BALANCED'
                """).param("m", merchant).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inventory_balance_check");

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE inventory SET available = available - 16, held = held + 16
                WHERE merchant_id = :m AND sku = 'BALANCED'
                """).param("m", merchant).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("inventory_available_check");
    }

    @Test
    void reservationCannotReferenceAnotherMerchantsInventory() {
        UUID owner = insertMerchant();
        UUID other = insertMerchant();
        UUID inventory = jdbc.sql("""
                INSERT INTO inventory (merchant_id, sku, initial_quantity, available, held, sold)
                VALUES (:m, 'OWNED', 5, 5, 0, 0) RETURNING id
                """).param("m", owner).query(UUID.class).single();

        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO reservation (merchant_id, inventory_id, quantity, status, expires_at)
                VALUES (:m, :i, 1, 'ACTIVE', now() + interval '10 minutes')
                """).param("m", other).param("i", inventory).update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("reservation_inventory_fk");
    }

    @Test
    void reservationCanBeResolvedAtMostOnce() {
        UUID merchant = insertMerchant();
        UUID inventory = jdbc.sql("""
                INSERT INTO inventory (merchant_id, sku, initial_quantity, available, held, sold)
                VALUES (:m, 'ONCE', 5, 4, 0, 1) RETURNING id
                """).param("m", merchant).query(UUID.class).single();
        UUID reservation = jdbc.sql("""
                INSERT INTO reservation (merchant_id, inventory_id, quantity, status, expires_at, resolved_at)
                VALUES (:m, :i, 1, 'CONFIRMED', now() + interval '10 minutes', now()) RETURNING id
                """).param("m", merchant).param("i", inventory).query(UUID.class).single();
        String movement = """
                INSERT INTO inventory_movement
                    (merchant_id, inventory_id, reservation_id, kind, quantity, available_after, held_after, sold_after)
                VALUES (:m, :i, :r, :kind, 1, 4, 0, 1)
                """;
        jdbc.sql(movement).param("m", merchant).param("i", inventory).param("r", reservation)
                .param("kind", "CONFIRMED").update();

        assertThatThrownBy(() -> jdbc.sql(movement).param("m", merchant).param("i", inventory)
                .param("r", reservation).param("kind", "EXPIRED").update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("movement_one_resolution_idx");
    }

    private UUID insertMerchant() {
        return jdbc.sql("INSERT INTO merchant (name) VALUES ('smoke') RETURNING id")
                .query(UUID.class).single();
    }
}
