package com.pulsar.host.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CommsSuiteMigrationRunnerTest {

    @Test
    void migrate_replaces_call_intel_members_with_call_handling() {
        var result = CommsSuiteMigrationRunner.migrate(
            Set.of("caller-match", "call-intel", "copilot", "scheduling"));
        assertThat(result).containsExactlyInAnyOrder("call-handling", "scheduling");
    }

    @Test
    void migrate_replaces_text_members_with_text_support() {
        var result = CommsSuiteMigrationRunner.migrate(Set.of("text-intel", "text-copilot", "hr"));
        assertThat(result).containsExactlyInAnyOrder("text-support", "hr");
    }

    @Test
    void migrate_handles_partial_call_set() {
        var result = CommsSuiteMigrationRunner.migrate(Set.of("caller-match", "scheduling"));
        assertThat(result).containsExactlyInAnyOrder("call-handling", "scheduling");
    }

    @Test
    void migrate_is_noop_when_suite_already_present() {
        var input = Set.of("call-handling", "text-support", "scheduling");
        assertThat(CommsSuiteMigrationRunner.migrate(input)).isEqualTo(input);
    }

    @Test
    void migrate_is_noop_for_unrelated_modules() {
        var input = Set.of("scheduling", "hr", "invoicing");
        assertThat(CommsSuiteMigrationRunner.migrate(input)).isEqualTo(input);
    }

    @Test
    void migrate_handles_empty_set() {
        assertThat(CommsSuiteMigrationRunner.migrate(Set.of())).isEmpty();
    }
}
