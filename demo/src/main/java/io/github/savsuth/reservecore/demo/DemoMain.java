package io.github.savsuth.reservecore.demo;

import java.net.URI;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo client for a running ReserveCore (by default the docker compose stack on :8081 and :8082 with the
 * dev profile's merchant keys). Every scenario prints PASS/FAIL per check; the exit code is 0 only when
 * all checks pass.
 *
 * <pre>
 * java -jar demo/target/reservecore-demo.jar all
 * java -jar demo/target/reservecore-demo.jar contention --concurrency=1000
 * java -jar demo/target/reservecore-demo.jar bench --concurrencies=50,200,1000 --runs=3
 * </pre>
 */
public final class DemoMain {

    private static final String USAGE = """
            usage: reservecore-demo <command> [--option=value ...]

            commands:
              health          readiness of every instance (no API key needed)
              lifecycle       reserve, confirm, cancel, history, isolation and auth checks
              contention      20 units vs 1,000 one-unit requests with distinct keys
              expiration      5 s holds expire and are reclaimed by the worker
              duplicates      concurrent duplicate and retried requests, key reuse
              worker-restart  kill every instance, let holds expire, restart, check reclamation
              all             lifecycle, contention, expiration, duplicates, worker-restart
              bench           contended (20 units) and ample (1,000 units) load, measured

            options (defaults in brackets):
              --urls          instance base URLs [http://localhost:8081,http://localhost:8082]
              --key           merchant API key [dev key of Dev Merchant Alice]
              --other-key     second merchant's key for isolation checks [dev key of Dev Merchant Bob]
              --concurrency   client concurrency for contention [200]
              --concurrencies client concurrencies for bench [50,200,1000]
              --runs          runs per bench cell [3]
              --compose       command used by worker-restart [docker compose]
              --services      services worker-restart kills and starts [app1,app2]
            """;

    /** Development-only keys seeded by the dev profile (README.md, "Development credentials"). */
    private static final String DEV_ALICE_KEY = "rcsk_devalice_DEVELOPMENT-ONLY-not-a-secret-alice-0000001";
    private static final String DEV_BOB_KEY = "rcsk_devbob00_DEVELOPMENT-ONLY-not-a-secret-bob-000000001";

    private DemoMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].startsWith("-")) {
            System.err.print(USAGE);
            System.exit(2);
        }
        Map<String, String> options = parseOptions(Arrays.copyOfRange(args, 1, args.length));
        List<URI> urls = Arrays.stream(options.getOrDefault("urls", "http://localhost:8081,http://localhost:8082")
                .split(",")).map(URI::create).toList();
        Api api = new Api(urls, options.getOrDefault("key", DEV_ALICE_KEY));
        Api other = api.withKey(options.getOrDefault("other-key", DEV_BOB_KEY));
        Checks checks = new Checks();
        String runId = Long.toString(System.currentTimeMillis(), 36).toUpperCase();
        Scenarios scenarios = new Scenarios(api, other, checks, runId,
                List.of(options.getOrDefault("compose", "docker compose").split(" ")),
                List.of(options.getOrDefault("services", "app1,app2").split(",")));
        int concurrency = Integer.parseInt(options.getOrDefault("concurrency", "200"));

        switch (args[0]) {
            case "health" -> {
                for (int instance = 0; instance < api.instances(); instance++) {
                    checks.check(api.base(instance) + " ready", api.ready(instance), "not ready or unreachable");
                }
            }
            case "lifecycle" -> scenarios.lifecycle();
            case "contention" -> scenarios.contention(concurrency);
            case "expiration" -> scenarios.expiration();
            case "duplicates" -> scenarios.duplicates();
            case "worker-restart" -> scenarios.workerRestart();
            case "all" -> {
                scenarios.lifecycle();
                scenarios.contention(concurrency);
                scenarios.expiration();
                scenarios.duplicates();
                scenarios.workerRestart();
            }
            case "bench" -> Bench.run(scenarios, api, checks, runId,
                    Arrays.stream(options.getOrDefault("concurrencies", "50,200,1000").split(","))
                            .map(Integer::parseInt).toList(),
                    Integer.parseInt(options.getOrDefault("runs", "3")));
            default -> {
                System.err.print("unknown command: " + args[0] + "\n" + USAGE);
                System.exit(2);
            }
        }
        System.out.println("run " + runId + ": " + checks.totals());
        System.exit(checks.allPassed() ? 0 : 1);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--") || !arg.contains("=")) {
                System.err.print("options look like --name=value, got: " + arg + "\n" + USAGE);
                System.exit(2);
            }
            int eq = arg.indexOf('=');
            options.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        return options;
    }
}
