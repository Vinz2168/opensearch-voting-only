/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.votingonly;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.coordination.CoordinationMetadata;
import org.opensearch.cluster.coordination.CoordinationMetadata.VotingConfiguration;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.test.MockLogAppender;
import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.opensearch.plugin.votingonly.VotingOnlyNodeRoleTests.node;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;

public class VotingOnlyHaCheckerTests extends OpenSearchTestCase {

    private static final Set<DiscoveryNodeRole> FULL_ROLES = Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, DiscoveryNodeRole.DATA_ROLE);
    private static final Set<DiscoveryNodeRole> VOTING_ONLY_ROLES = Set.of(
        DiscoveryNodeRole.CLUSTER_MANAGER_ROLE,
        VotingOnlyNodeRole.INSTANCE
    );

    private final DiscoveryNode full1 = node("full1", FULL_ROLES);
    private final DiscoveryNode full2 = node("full2", FULL_ROLES);
    private final DiscoveryNode votingOnly1 = node("vo1", VOTING_ONLY_ROLES);
    private final DiscoveryNode votingOnly2 = node("vo2", VOTING_ONLY_ROLES);
    private final DiscoveryNode dataOnly = node("data", Set.of(DiscoveryNodeRole.DATA_ROLE));

    private static VotingConfiguration config(String... nodeIds) {
        return new VotingConfiguration(Set.of(nodeIds));
    }

    private static DiscoveryNodes nodes(DiscoveryNode localNode, boolean localIsLeader, DiscoveryNode... others) {
        final DiscoveryNodes.Builder builder = DiscoveryNodes.builder().add(localNode).localNodeId(localNode.getId());
        Arrays.stream(others).forEach(builder::add);
        if (localIsLeader) {
            builder.clusterManagerNodeId(localNode.getId());
        }
        return builder.build();
    }

    private static ClusterState state(DiscoveryNodes nodes, VotingConfiguration configuration) {
        return ClusterState.builder(ClusterName.DEFAULT)
            .nodes(nodes)
            .metadata(
                Metadata.builder()
                    .coordinationMetadata(
                        CoordinationMetadata.builder()
                            .lastCommittedConfiguration(configuration)
                            .lastAcceptedConfiguration(configuration)
                            .build()
                    )
            )
            .build();
    }

    public void testTwoPlusOneIsHighlyAvailable() {
        final VotingOnlyHaChecker.Evaluation evaluation = VotingOnlyHaChecker.evaluate(
            config("full1", "full2", "vo1"),
            nodes(full1, true, full2, votingOnly1, dataOnly)
        );
        assertFalse(evaluation.violated());
        assertThat(evaluation.fullNodeNames(), containsInAnyOrder("full1", "full2"));
        assertEquals(1, evaluation.votingOnlyNodes());
        assertEquals(0, evaluation.unknownNodes());
    }

    public void testOneFullNodeAndVotingOnlyNodesViolatesTheRule() {
        final VotingConfiguration configuration = config("full1", "vo1", "vo2");
        final VotingOnlyHaChecker.Evaluation evaluation = VotingOnlyHaChecker.evaluate(
            configuration,
            nodes(full1, true, votingOnly1, votingOnly2)
        );
        assertTrue(evaluation.violated());
        final String message = evaluation.message(configuration);
        assertThat(message, containsString("contains [1] cluster-manager-eligible node(s) that are not voting-only [full1]"));
        assertThat(message, containsString("[2] voting-only node(s)"));
        assertThat(message, containsString("if [full1] fails, no cluster manager can be elected"));
    }

    public void testFullNodeAndVotingOnlyNodeViolatesTheRule() {
        final VotingOnlyHaChecker.Evaluation evaluation = VotingOnlyHaChecker.evaluate(
            config("full1", "vo1"),
            nodes(full1, true, votingOnly1)
        );
        assertTrue(evaluation.violated());
    }

    public void testUnknownVoterCountsAsPossiblyFull() {
        // full2 is down: a lost node, not a bad configuration
        final VotingOnlyHaChecker.Evaluation evaluation = VotingOnlyHaChecker.evaluate(
            config("full1", "full2", "vo1"),
            nodes(full1, true, votingOnly1)
        );
        assertFalse(evaluation.violated());
        assertEquals(1, evaluation.unknownNodes());
    }

    public void testUnknownVoterIsNotEnoughWithoutAnotherFullNode() {
        final VotingOnlyHaChecker.Evaluation evaluation = VotingOnlyHaChecker.evaluate(
            config("vo1", "vo2", "gone"),
            nodes(full1, true, votingOnly1, votingOnly2)
        );
        assertTrue(evaluation.violated());
        assertThat(evaluation.fullNodeNames(), empty());
        assertThat(evaluation.message(config("vo1", "vo2", "gone")), containsString("at least [2] are required"));
    }

    public void testNoVotingOnlyVotersNeverViolates() {
        assertFalse(VotingOnlyHaChecker.evaluate(config("full1"), nodes(full1, true)).violated());
        assertFalse(VotingOnlyHaChecker.evaluate(config("full1", "full2"), nodes(full1, true, full2, votingOnly1)).violated());
    }

    public void testWarnsOncePerChangeAndOnlyOnTheLeader() throws Exception {
        final VotingOnlyHaChecker checker = new VotingOnlyHaChecker();
        final ClusterState empty = ClusterState.builder(ClusterName.DEFAULT).build();
        final ClusterState badLeader = state(nodes(full1, true, votingOnly1, votingOnly2), config("full1", "vo1", "vo2"));
        final ClusterState badOtherConfig = state(nodes(full1, true, votingOnly1, votingOnly2), config("full1", "vo1"));
        final ClusterState badFollower = state(nodes(full1, false, votingOnly1, votingOnly2), config("full1", "vo1", "vo2"));
        final ClusterState good = state(nodes(full1, true, full2, votingOnly1), config("full1", "full2", "vo1"));

        try (CountingAppender appender = new CountingAppender()) {
            checker.clusterChanged(new ClusterChangedEvent("test", badFollower, empty));
            assertThat(appender.messages(Level.WARN), empty());

            checker.clusterChanged(new ClusterChangedEvent("test", badLeader, badFollower));
            checker.clusterChanged(new ClusterChangedEvent("test", badLeader, badLeader));
            assertEquals(1, appender.messages(Level.WARN).size());

            // the configuration changes while the rule is still violated
            checker.clusterChanged(new ClusterChangedEvent("test", badOtherConfig, badLeader));
            assertEquals(2, appender.messages(Level.WARN).size());

            checker.clusterChanged(new ClusterChangedEvent("test", good, badOtherConfig));
            checker.clusterChanged(new ClusterChangedEvent("test", good, good));
            assertEquals(2, appender.messages(Level.WARN).size());
            assertEquals(1, appender.messages(Level.INFO).size());
            assertThat(appender.messages(Level.INFO).get(0), containsString("now contains at least [2]"));

            // losing and regaining leadership reports the condition again
            checker.clusterChanged(new ClusterChangedEvent("test", badLeader, good));
            assertEquals(3, appender.messages(Level.WARN).size());
            checker.clusterChanged(new ClusterChangedEvent("test", badFollower, badLeader));
            checker.clusterChanged(new ClusterChangedEvent("test", badLeader, badFollower));
            assertEquals(4, appender.messages(Level.WARN).size());
        }
    }

    public void testNoInfoOnFirstHealthyCheck() throws Exception {
        final VotingOnlyHaChecker checker = new VotingOnlyHaChecker();
        final ClusterState good = state(nodes(full1, true, full2, votingOnly1), config("full1", "full2", "vo1"));
        try (CountingAppender appender = new CountingAppender()) {
            checker.clusterChanged(new ClusterChangedEvent("test", good, ClusterState.builder(ClusterName.DEFAULT).build()));
            assertThat(appender.messages(Level.WARN), empty());
            assertThat(appender.messages(Level.INFO), empty());
        }
    }

    public void testNeverThrows() {
        final VotingOnlyHaChecker checker = new VotingOnlyHaChecker();
        final ClusterState leader = state(nodes(full1, true), config("full1"));
        // a state without coordination metadata must not break cluster state application
        final ClusterState noMetadata = ClusterState.builder(ClusterName.DEFAULT).nodes(nodes(full1, true)).build();
        checker.clusterChanged(new ClusterChangedEvent("test", noMetadata, leader));
        checker.clusterChanged(new ClusterChangedEvent("test", leader, noMetadata));
    }

    /**
     * Collects the formatted messages of the checker's logger, so that the tests can count them.
     */
    private static final class CountingAppender implements AutoCloseable {

        private final List<LogEvent> events = new ArrayList<>();
        private final MockLogAppender appender;

        CountingAppender() throws IllegalAccessException {
            appender = MockLogAppender.createForLoggers(LogManager.getLogger(VotingOnlyHaChecker.class));
            appender.addExpectation(new MockLogAppender.LoggingExpectation() {
                @Override
                public void match(LogEvent event) {
                    events.add(event.toImmutable());
                }

                @Override
                public void assertMatched() {}
            });
        }

        List<String> messages(Level level) {
            return events.stream()
                .filter(event -> event.getLevel().equals(level))
                .map(event -> event.getMessage().getFormattedMessage())
                .collect(Collectors.toList());
        }

        @Override
        public void close() {
            appender.close();
        }
    }
}
