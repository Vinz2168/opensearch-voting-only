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
import org.opensearch.action.admin.cluster.repositories.verify.VerifyRepositoryResponse;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.settings.Settings;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.InternalTestCluster;
import org.opensearch.test.MockLogAppender;
import org.opensearch.test.OpenSearchIntegTestCase;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST, numDataNodes = 0, numClientNodes = 0, supportsDedicatedMasters = false)
public class VotingOnlyNodeIT extends OpenSearchIntegTestCase {

    private Path repoPath;

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        final List<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(VotingOnlyNodePlugin.class);
        return plugins;
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        if (repoPath == null) {
            repoPath = randomRepoPath();
        }
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put("cluster.election.strategy", VotingOnlyElectionStrategy.NAME)
            .put("path.repo", repoPath)
            .build();
    }

    private String startFullNode() {
        return internalCluster().startNode(fullNodeSettings());
    }

    private static Settings fullNodeSettings() {
        return Settings.builder().putList("node.roles", "cluster_manager", "data").build();
    }

    private static Settings votingOnlyNodeSettings() {
        return Settings.builder().putList("node.roles", "cluster_manager", "voting_only").build();
    }

    /**
     * Starts two full cluster-manager nodes and one voting-only node together, in random order (a voting-only node alone cannot form
     * a cluster, so they cannot be started one by one). Returns the voting-only node's name.
     */
    private String startTwoPlusOne(List<String> fullNodes) {
        final int votingOnlyPosition = between(0, 2);
        final Settings[] settings = new Settings[3];
        for (int i = 0; i < 3; i++) {
            settings[i] = i == votingOnlyPosition ? votingOnlyNodeSettings() : fullNodeSettings();
        }
        final List<String> names = internalCluster().startNodes(settings);
        ensureStableCluster(3);
        for (int i = 0; i < 3; i++) {
            if (i != votingOnlyPosition) {
                fullNodes.add(names.get(i));
            }
        }
        return names.get(votingOnlyPosition);
    }

    /**
     * Checks, from every node's local cluster state, that the elected cluster manager is never the voting-only node (I1).
     */
    private void assertClusterManagerIsNot(String votingOnlyNode) {
        assertThat(internalCluster().getClusterManagerName(), not(equalTo(votingOnlyNode)));
        for (String nodeName : internalCluster().getNodeNames()) {
            final ClusterState state = internalCluster().client(nodeName).admin().cluster().prepareState().setLocal(true).get().getState();
            final DiscoveryNode clusterManager = state.nodes().getClusterManagerNode();
            if (clusterManager != null) {
                assertThat("as seen by " + nodeName, clusterManager.getName(), not(equalTo(votingOnlyNode)));
                assertFalse(VotingOnlyNodeRole.isVotingOnlyNode(clusterManager));
            }
        }
    }

    public void testTwoPlusOneElectsFullNode() {
        final List<String> fullNodes = new ArrayList<>();
        final String votingOnly = startTwoPlusOne(fullNodes);
        assertClusterManagerIsNot(votingOnly);

        final ClusterState state = client().admin().cluster().prepareState().get().getState();
        final DiscoveryNode votingOnlyNode = state.nodes().resolveNode(votingOnly);
        assertTrue(VotingOnlyNodeRole.isVotingOnlyNode(votingOnlyNode));
        assertThat(
            votingOnlyNode.getRoles().stream().map(r -> r.roleNameAbbreviation()).collect(Collectors.toList()),
            hasItem(VotingOnlyNodeRole.ROLE_NAME_ABBREVIATION)
        );
        // I2: the voting-only node is part of the voting configuration
        assertThat(state.coordinationMetadata().getLastCommittedConfiguration().getNodeIds(), hasItem(votingOnlyNode.getId()));
    }

    public void testLossOfEitherFullNode() throws Exception {
        final List<String> fullNodes = new ArrayList<>();
        final String votingOnly = startTwoPlusOne(fullNodes);
        for (int i = 0; i < 2; i++) {
            final String previousClusterManager = internalCluster().getClusterManagerName();
            logger.info("--> stopping cluster manager {}", previousClusterManager);
            internalCluster().stopCurrentClusterManagerNode();
            fullNodes.remove(previousClusterManager);
            // I4: the surviving full node is elected
            ensureStableCluster(2, votingOnly);
            assertThat(internalCluster().getClusterManagerName(votingOnly), equalTo(fullNodes.get(0)));
            assertClusterManagerIsNot(votingOnly);
            fullNodes.add(startFullNode());
            ensureStableCluster(3);
            assertClusterManagerIsNot(votingOnly);
        }
    }

    public void testRollingRestartOfFullNodes() throws Exception {
        final List<String> fullNodes = new ArrayList<>();
        final String votingOnly = startTwoPlusOne(fullNodes);
        for (int round = 0; round < 2; round++) {
            for (String fullNode : fullNodes) {
                logger.info("--> [round {}] restarting {}", round, fullNode);
                internalCluster().restartNode(fullNode, new InternalTestCluster.RestartCallback() {
                    @Override
                    public Settings onNodeStopped(String nodeName) {
                        ensureStableCluster(2, votingOnly);
                        assertClusterManagerIsNot(votingOnly);
                        return Settings.EMPTY;
                    }
                });
                ensureStableCluster(3);
                assertClusterManagerIsNot(votingOnly);
            }
        }
    }

    public void testRestartOfVotingOnlyNode() throws Exception {
        final List<String> fullNodes = new ArrayList<>();
        final String votingOnly = startTwoPlusOne(fullNodes);
        final String clusterManager = internalCluster().getClusterManagerName();
        internalCluster().restartNode(votingOnly);
        ensureStableCluster(3);
        assertThat(internalCluster().getClusterManagerName(), equalTo(clusterManager));
        assertClusterManagerIsNot(votingOnly);
    }

    public void testRepositoryVerificationSkipsDedicatedVotingOnlyNode() {
        final List<String> fullNodes = new ArrayList<>();
        final String votingOnly = startTwoPlusOne(fullNodes);
        assertAcked(
            client().admin()
                .cluster()
                .preparePutRepository("repo")
                .setType("fs")
                .setSettings(Settings.builder().put("location", repoPath.resolve("repo")))
                .setVerify(true)
        );
        final VerifyRepositoryResponse response = client().admin().cluster().prepareVerifyRepository("repo").get();
        final List<String> verifiedNodes = response.getNodes()
            .stream()
            .map(VerifyRepositoryResponse.NodeView::getName)
            .collect(Collectors.toList());
        assertThat(verifiedNodes, not(hasItem(votingOnly)));
        for (String fullNode : fullNodes) {
            assertThat(verifiedNodes, hasItem(fullNode));
        }
    }

    public void testHaWarningForSingleFullNode() throws Exception {
        try (MockLogAppender appender = MockLogAppender.createForLoggers(LogManager.getLogger(VotingOnlyHaChecker.class))) {
            appender.addExpectation(
                new MockLogAppender.SeenEventExpectation(
                    "HA rule warning",
                    VotingOnlyHaChecker.class.getCanonicalName(),
                    Level.WARN,
                    "*contains [1] cluster-manager-eligible node(s) that are not voting-only*[2] voting-only node(s)*"
                )
            );
            // the warning never blocks startup: one full node and two voting-only nodes still form a cluster
            final List<String> names = internalCluster().startNodes(fullNodeSettings(), votingOnlyNodeSettings(), votingOnlyNodeSettings());
            ensureStableCluster(3);
            assertThat(internalCluster().getClusterManagerName(), equalTo(names.get(0)));
            assertBusy(appender::assertAllExpectationsMatched);
        }
    }

    public void testVotingOnlyRoleRequiresClusterManagerRole() {
        final Exception e = expectThrows(
            Exception.class,
            () -> internalCluster().startNode(
                Settings.builder().putList("node.roles", randomFrom(List.of("voting_only"), List.of("data", "voting_only")))
            )
        );
        assertThat(rootCauseMessage(e), containsString("node role [voting_only] requires the [cluster_manager] role"));
    }

    public void testVotingOnlyRoleRequiresElectionStrategy() {
        final Exception e = expectThrows(
            Exception.class,
            () -> internalCluster().startNode(
                Settings.builder().putList("node.roles", "cluster_manager", "voting_only").put("cluster.election.strategy", "default")
            )
        );
        assertThat(rootCauseMessage(e), containsString("requires [cluster.election.strategy: voting_only]"));
    }

    private static String rootCauseMessage(Throwable e) {
        final StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        assertThat(messages.toString(), notNullValue());
        return messages.toString();
    }
}
