/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.votingonly;

import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.TransportInterceptor;

import java.util.Collections;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;

public class VotingOnlyNodePluginTests extends OpenSearchTestCase {

    private static final NamedWriteableRegistry REGISTRY = new NamedWriteableRegistry(Collections.emptyList());

    private static Settings votingOnlySettings() {
        return Settings.builder()
            .putList("node.roles", "cluster_manager", "voting_only")
            .put("cluster.election.strategy", "voting_only")
            .build();
    }

    public void testRegistersRoleAndStrategy() {
        final VotingOnlyNodePlugin plugin = new VotingOnlyNodePlugin(Settings.EMPTY);
        assertThat(plugin.getRoles(), contains(VotingOnlyNodeRole.INSTANCE));
        assertThat(plugin.getElectionStrategies().get("voting_only"), instanceOf(VotingOnlyElectionStrategy.class));
    }

    public void testNoInterceptorOnFullNodes() {
        final Settings settings = randomFrom(
            Settings.EMPTY,
            Settings.builder().putList("node.roles", "cluster_manager", "data").put("cluster.election.strategy", "voting_only").build(),
            Settings.builder().putList("node.roles", "data").build()
        );
        final VotingOnlyNodePlugin plugin = new VotingOnlyNodePlugin(settings);
        assertFalse(plugin.isVotingOnly());
        assertThat(plugin.getTransportInterceptors(REGISTRY, new ThreadContext(Settings.EMPTY)), empty());
    }

    public void testInterceptorOnVotingOnlyNodes() {
        final VotingOnlyNodePlugin plugin = new VotingOnlyNodePlugin(votingOnlySettings());
        assertTrue(plugin.isVotingOnly());
        final List<TransportInterceptor> interceptors = plugin.getTransportInterceptors(REGISTRY, new ThreadContext(Settings.EMPTY));
        assertEquals(1, interceptors.size());
        assertThat(interceptors.get(0), instanceOf(VotingOnlyPublicationInterceptor.class));
    }

    public void testVotingOnlyNodeRequiresStrategy() {
        final Settings.Builder settings = Settings.builder().putList("node.roles", "cluster_manager", "voting_only");
        if (randomBoolean()) {
            settings.put("cluster.election.strategy", "default");
        }
        final IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> new VotingOnlyNodePlugin(settings.build()));
        assertThat(e.getMessage(), containsString("requires [cluster.election.strategy: voting_only]"));
    }

    public void testHasVotingOnlyRoleIsCaseInsensitive() {
        assertTrue(VotingOnlyNodePlugin.hasVotingOnlyRole(Settings.builder().putList("node.roles", "cluster_manager", "Voting_Only").build()));
        assertFalse(VotingOnlyNodePlugin.hasVotingOnlyRole(Settings.EMPTY));
    }
}
