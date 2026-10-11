/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.cluster.coordination;

import org.opensearch.Version;
import org.opensearch.cluster.coordination.AbstractCoordinatorTestCase.Cluster.ClusterNode;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.common.UUIDs;
import org.opensearch.core.common.transport.TransportAddress;
import org.opensearch.monitor.StatusInfo;
import org.opensearch.plugin.votingonly.VotingOnlyElectionStrategy;
import org.opensearch.plugin.votingonly.VotingOnlyNodeRole;
import org.opensearch.plugin.votingonly.VotingOnlyPublicationInterceptor;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportInterceptor;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportRequestHandler;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.opensearch.monitor.StatusInfo.Status.HEALTHY;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Deterministic coordination tests with voting-only nodes, built on {@link AbstractCoordinatorTestCase}. They live in the
 * {@code org.opensearch.cluster.coordination} package because the test framework's cluster helpers are package-private.
 * <p>
 * Every node gets a transport interceptor that records commit requests received from voting-only nodes, so I1 is checked on the
 * receiving side for every test, including random disruptions; see {@link #assertNoCommitFromVotingOnlyNode()}.
 */
public class VotingOnlyCoordinatorTests extends AbstractCoordinatorTestCase {

    /**
     * Extra time for one more election round: a voting-only node may first need to win an election to bring full nodes up to date (I5).
     */
    private static final long VOTING_ONLY_EXTRA_TIME = 2 * DEFAULT_ELECTION_DELAY + 2 * DEFAULT_CLUSTER_STATE_UPDATE_DELAY;
    private static final long STABILISATION_TIME = DEFAULT_STABILISATION_TIME + VOTING_ONLY_EXTRA_TIME;

    private Set<Integer> votingOnlyNodeIndices = Collections.emptySet();
    private final List<String> commitsFromVotingOnlyNodes = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong commitsFromFullNodes = new AtomicLong();
    private final List<VotingOnlyPublicationInterceptor> votingOnlyInterceptors = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void registerRole() {
        DiscoveryNode.setAdditionalRoles(Set.of(VotingOnlyNodeRole.INSTANCE));
    }

    @After
    public void assertNoCommitFromVotingOnlyNode() {
        assertThat("I1: no node may ever receive a commit from a voting-only node", commitsFromVotingOnlyNodes, empty());
    }

    @After
    public void unregisterRole() {
        DiscoveryNode.setAdditionalRoles(Collections.emptySet());
    }

    @Override
    protected ElectionStrategy getElectionStrategy() {
        return new VotingOnlyElectionStrategy();
    }

    @Override
    protected DiscoveryNode createDiscoveryNode(int nodeIndex, boolean clusterManagerEligible) {
        final TransportAddress address = buildNewFakeTransportAddress();
        final Set<DiscoveryNodeRole> roles;
        if (clusterManagerEligible == false) {
            roles = Collections.emptySet();
        } else if (votingOnlyNodeIndices.contains(nodeIndex)) {
            roles = Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, VotingOnlyNodeRole.INSTANCE);
        } else {
            roles = DiscoveryNodeRole.BUILT_IN_ROLES;
        }
        return new DiscoveryNode(
            "",
            "node" + nodeIndex,
            UUIDs.randomBase64UUID(random()),
            address.address().getHostString(),
            address.getAddress(),
            address,
            Collections.emptyMap(),
            roles,
            Version.CURRENT
        );
    }

    @Override
    protected TransportInterceptor getTransportInterceptor(DiscoveryNode localNode, ThreadPool threadPool) {
        final VotingOnlyPublicationInterceptor votingOnlyInterceptor;
        if (VotingOnlyNodeRole.isVotingOnlyNode(localNode)) {
            votingOnlyInterceptor = new VotingOnlyPublicationInterceptor(localNode.getId());
            votingOnlyInterceptors.add(votingOnlyInterceptor);
        } else {
            votingOnlyInterceptor = null;
        }
        return new TransportInterceptor() {
            @Override
            public <T extends TransportRequest> TransportRequestHandler<T> interceptHandler(
                String action,
                String executor,
                boolean forceExecution,
                TransportRequestHandler<T> actualHandler
            ) {
                if (PublicationTransportHandler.COMMIT_STATE_ACTION_NAME.equals(action) == false) {
                    return actualHandler;
                }
                return (request, channel, task) -> {
                    final DiscoveryNode source = ((ApplyCommitRequest) request).getSourceNode();
                    if (VotingOnlyNodeRole.isVotingOnlyNode(source)) {
                        commitsFromVotingOnlyNodes.add(source.getId() + " -> " + localNode.getId() + ": " + request);
                    } else {
                        commitsFromFullNodes.incrementAndGet();
                    }
                    actualHandler.messageReceived(request, channel, task);
                };
            }

            @Override
            public AsyncSender interceptSender(AsyncSender sender) {
                return votingOnlyInterceptor == null ? sender : votingOnlyInterceptor.interceptSender(sender);
            }
        };
    }

    private long failedVotingOnlyPublishResponses() {
        synchronized (votingOnlyInterceptors) {
            return votingOnlyInterceptors.stream().mapToLong(VotingOnlyPublicationInterceptor::getFailedPublishResponses).sum();
        }
    }

    private Cluster newCluster(int fullNodes, int votingOnlyNodes) {
        final int size = fullNodes + votingOnlyNodes;
        // node indices start from 0 in every test, the voting-only nodes are the last ones
        votingOnlyNodeIndices = IntStream.range(fullNodes, size).boxed().collect(Collectors.toSet());
        return new Cluster(size);
    }

    private static boolean isVotingOnly(ClusterNode clusterNode) {
        return VotingOnlyNodeRole.isVotingOnlyNode(clusterNode.getLocalNode());
    }

    private static List<ClusterNode> fullNodes(Cluster cluster) {
        return cluster.clusterNodes.stream().filter(cn -> isVotingOnly(cn) == false).collect(Collectors.toList());
    }

    private static List<ClusterNode> votingOnlyNodes(Cluster cluster) {
        return cluster.clusterNodes.stream().filter(VotingOnlyCoordinatorTests::isVotingOnly).collect(Collectors.toList());
    }

    private static ClusterNode node(Cluster cluster, String nodeId) {
        return cluster.clusterNodes.stream().filter(cn -> cn.getId().equals(nodeId)).findFirst().orElseThrow();
    }

    /**
     * Stabilises and checks that the elected cluster manager is a full node, as seen by every node (I1).
     */
    private ClusterNode stabiliseWithFullLeader(Cluster cluster) {
        cluster.stabilise(STABILISATION_TIME);
        final ClusterNode leader = cluster.getAnyLeader();
        assertFalse("I1: voting-only node " + leader.getId() + " must not be the stable leader", isVotingOnly(leader));
        assertNoVotingOnlyClusterManagerApplied(cluster);
        return leader;
    }

    private static void assertNoVotingOnlyClusterManagerApplied(Cluster cluster) {
        for (ClusterNode clusterNode : cluster.clusterNodes) {
            assertFalse("I1: voting-only node " + clusterNode.getId() + " is leader", isVotingOnly(clusterNode) && clusterNode.isLeader());
            final DiscoveryNode clusterManager = clusterNode.getLastAppliedClusterState().nodes().getClusterManagerNode();
            if (clusterManager != null) {
                assertFalse(
                    "I1: " + clusterNode.getId() + " applied a state with voting-only cluster manager " + clusterManager,
                    VotingOnlyNodeRole.isVotingOnlyNode(clusterManager)
                );
            }
        }
    }

    /**
     * Restarts a node, keeping its roles. {@code ClusterNode#restartedNode()} in the test framework always gives the restarted node the
     * built-in roles, which would silently turn a voting-only node into a full cluster-manager node, so it is not used here.
     */
    private void restart(Cluster cluster, ClusterNode clusterNode) {
        clusterNode.close();
        cluster.clusterNodes.forEach(
            cn -> cluster.deterministicTaskQueue.scheduleNow(cn.onNode(() -> cn.transportService.disconnectFromNode(clusterNode.getLocalNode())))
        );
        final DiscoveryNode oldLocalNode = clusterNode.getLocalNode();
        final TransportAddress address = randomBoolean() ? buildNewFakeTransportAddress() : oldLocalNode.getAddress();
        final DiscoveryNode newLocalNode = new DiscoveryNode(
            oldLocalNode.getName(),
            oldLocalNode.getId(),
            UUIDs.randomBase64UUID(random()),
            address.address().getHostString(),
            address.getAddress(),
            address,
            Collections.emptyMap(),
            oldLocalNode.getRoles(),
            Version.CURRENT
        );
        final int nodeIndex = Integer.parseInt(oldLocalNode.getId().substring("node".length()));
        final ClusterNode restarted = cluster.new ClusterNode(
            nodeIndex,
            newLocalNode,
            node -> cluster.new MockPersistedState(
                newLocalNode, clusterNode.persistedStateRegistry, Function.identity(), Function.identity()
            ),
            clusterNode.nodeSettings,
            () -> new StatusInfo(HEALTHY, "healthy-info")
        );
        cluster.clusterNodes.replaceAll(cn -> cn == clusterNode ? restarted : cn);
    }

    /**
     * Like {@code Cluster#runRandomly()}, but the reboots are done by {@link #restart} so that voting-only nodes stay voting-only.
     */
    private void runRandomly(Cluster cluster) {
        // a single round: the framework's linearizability checker keeps the whole history in memory
        cluster.runRandomly(false, true, Cluster.EXTREME_DELAY_VARIABILITY);
        if (randomBoolean()) {
            restart(cluster, randomFrom(cluster.clusterNodes));
        }
    }

    public void testTwoPlusOneElectsFullNode() {
        try (Cluster cluster = newCluster(2, 1)) {
            runRandomly(cluster);
            final ClusterNode leader = stabiliseWithFullLeader(cluster);

            // I2: the voting-only node is a full member of the voting configuration
            final ClusterNode votingOnly = votingOnlyNodes(cluster).get(0);
            assertThat(
                leader.coordinator.getLastAcceptedState().getLastCommittedConfiguration().getNodeIds(),
                hasItem(votingOnly.getId())
            );
            assertThat("sanity check: commits from full nodes are observed", commitsFromFullNodes.get(), greaterThan(0L));
        }
    }

    public void testTwoPlusOneSurvivesLossOfEitherFullNode() {
        try (Cluster cluster = newCluster(2, 1)) {
            runRandomly(cluster);
            stabiliseWithFullLeader(cluster);

            for (ClusterNode lost : fullNodes(cluster)) {
                logger.info("--> disconnecting full node {}", lost);
                lost.disconnect();
                final ClusterNode leader = stabiliseWithFullLeader(cluster);
                // I4: the surviving full node is elected
                assertThat(leader.getId(), not(equalTo(lost.getId())));
                lost.heal();
                stabiliseWithFullLeader(cluster);
            }
        }
    }

    public void testTwoPlusOneSurvivesLossOfVotingOnlyNode() {
        try (Cluster cluster = newCluster(2, 1)) {
            runRandomly(cluster);
            stabiliseWithFullLeader(cluster);
            final ClusterNode votingOnly = votingOnlyNodes(cluster).get(0);
            if (randomBoolean()) {
                votingOnly.disconnect();
            } else {
                votingOnly.blackhole();
            }
            stabiliseWithFullLeader(cluster);
            votingOnly.heal();
            stabiliseWithFullLeader(cluster);
        }
    }

    public void testEveryTwoAgainstOnePartition() {
        try (Cluster cluster = newCluster(2, 1)) {
            runRandomly(cluster);
            stabiliseWithFullLeader(cluster);
            for (ClusterNode isolated : new ArrayList<>(cluster.clusterNodes)) {
                logger.info("--> isolating {}", isolated);
                if (randomBoolean()) {
                    isolated.disconnect();
                } else {
                    isolated.blackhole();
                }
                final ClusterNode leader = stabiliseWithFullLeader(cluster);
                assertThat(leader.getId(), not(equalTo(isolated.getId())));
                isolated.heal();
                stabiliseWithFullLeader(cluster);
            }
        }
    }

    public void testThreeWaySplitElectsNoLeader() {
        try (Cluster cluster = newCluster(2, 1)) {
            runRandomly(cluster);
            stabiliseWithFullLeader(cluster);
            cluster.clusterNodes.forEach(ClusterNode::disconnect);
            cluster.runFor(STABILISATION_TIME, "running with every node isolated");
            for (ClusterNode clusterNode : cluster.clusterNodes) {
                assertFalse(clusterNode.getId() + " must not be leader without a quorum", clusterNode.isLeader());
            }
            assertNoVotingOnlyClusterManagerApplied(cluster);
            cluster.clusterNodes.forEach(ClusterNode::heal);
            stabiliseWithFullLeader(cluster);
        }
    }

    public void testOneWayPartitions() {
        try (Cluster cluster = newCluster(2, 1)) {
            runRandomly(cluster);
            stabiliseWithFullLeader(cluster);
            for (int round = 0; round < 5; round++) {
                final List<ClusterNode> nodes = cluster.clusterNodes;
                final int blackholed = between(1, 3);
                for (int i = 0; i < blackholed; i++) {
                    final ClusterNode sender = randomFrom(nodes);
                    final ClusterNode destination = randomValueOtherThan(sender, () -> randomFrom(nodes));
                    logger.info("--> blackholing requests from {} to {}", sender, destination);
                    cluster.blackholeConnectionsFrom(sender, destination);
                }
                cluster.runFor(STABILISATION_TIME, "running with one-way partitions");
                assertNoVotingOnlyClusterManagerApplied(cluster);
                cluster.clearBlackholedConnections();
                stabiliseWithFullLeader(cluster);
            }
        }
    }

    /**
     * I5: the only surviving full node misses the latest cluster state, which the voting-only node has. The voting-only node must win a
     * temporary election to push the state, fail its own publication and let the full node take over with the up-to-date state.
     */
    public void testStaleFullNodeRecoversStateFromVotingOnlyNode() {
        try (Cluster cluster = newCluster(2, 1)) {
            runRandomly(cluster);
            final ClusterNode oldLeader = stabiliseWithFullLeader(cluster);
            final ClusterNode staleNode = fullNodes(cluster).stream().filter(cn -> cn != oldLeader).findFirst().orElseThrow();
            final ClusterNode votingOnly = votingOnlyNodes(cluster).get(0);

            logger.info("--> making {} miss the next publications", staleNode);
            staleNode.blackhole();
            final long value = randomLong();
            oldLeader.submitValue(value);
            cluster.runFor(DEFAULT_CLUSTER_STATE_UPDATE_DELAY, "committing value with the voting-only node only");
            assertThat(value(votingOnly.coordinator.getLastAcceptedState()), equalTo(value));
            assertThat(value(staleNode.coordinator.getLastAcceptedState()), not(equalTo(value)));

            logger.info("--> losing leader {} and reconnecting stale node {}", oldLeader, staleNode);
            final long failedBefore = failedVotingOnlyPublishResponses();
            oldLeader.disconnect();
            staleNode.heal();
            final ClusterNode newLeader = stabiliseWithFullLeader(cluster);

            assertThat(newLeader.getId(), equalTo(staleNode.getId()));
            assertThat("the full node recovered the latest state", value(newLeader.getLastAppliedClusterState()), equalTo(value));
            assertThat(
                "the voting-only node had to win a temporary election to push its state",
                failedVotingOnlyPublishResponses(),
                greaterThan(failedBefore)
            );

            oldLeader.heal();
            stabiliseWithFullLeader(cluster);
        }
    }

    /**
     * The forum case: rolling restart of both full nodes, while the voting-only node never becomes the cluster manager.
     */
    public void testRollingRestartsOfFullNodes() {
        try (Cluster cluster = newCluster(2, 1)) {
            runRandomly(cluster);
            stabiliseWithFullLeader(cluster);
            for (int round = 0; round < 3; round++) {
                for (String nodeId : fullNodes(cluster).stream().map(ClusterNode::getId).collect(Collectors.toList())) {
                    final ClusterNode stopped = node(cluster, nodeId);
                    logger.info("--> [round {}] stopping {}", round, stopped);
                    stopped.disconnect();
                    final ClusterNode leader = stabiliseWithFullLeader(cluster);
                    assertThat(leader.getId(), not(equalTo(nodeId)));
                    leader.submitValue(randomLong());
                    cluster.runFor(DEFAULT_CLUSTER_STATE_UPDATE_DELAY, "committing a value while " + nodeId + " is down");

                    logger.info("--> [round {}] restarting {}", round, stopped);
                    restart(cluster, stopped);
                    node(cluster, nodeId).heal();
                    stabiliseWithFullLeader(cluster);
                }
            }
        }
    }

    public void testRepeatedRestartsOfRandomNodes() {
        try (Cluster cluster = newCluster(2, 1)) {
            runRandomly(cluster);
            stabiliseWithFullLeader(cluster);
            for (int i = 0; i < 10; i++) {
                if (randomBoolean()) {
                    cluster.getAnyLeader().submitValue(randomLong());
                    cluster.runFor(randomLongBetween(0, DEFAULT_CLUSTER_STATE_UPDATE_DELAY), "maybe committing a value");
                }
                final ClusterNode restarted = randomFrom(cluster.clusterNodes);
                logger.info("--> restarting {}", restarted);
                restart(cluster, restarted);
                stabiliseWithFullLeader(cluster);
            }
        }
    }

    public void testSeveralVotingOnlyNodes() {
        try (Cluster cluster = newCluster(3, 2)) {
            runRandomly(cluster);
            stabiliseWithFullLeader(cluster);

            // losing a minority of the full nodes, one after the other
            for (int i = 0; i < 2; i++) {
                final ClusterNode leader = cluster.getAnyLeader();
                leader.disconnect();
                stabiliseWithFullLeader(cluster);
                leader.heal();
                stabiliseWithFullLeader(cluster);
            }
        }
    }

    public void testOnlyVotingOnlyNodesLeftElectsNoLeader() {
        try (Cluster cluster = newCluster(1, 2)) {
            runRandomly(cluster);
            final ClusterNode fullNode = stabiliseWithFullLeader(cluster);
            fullNode.disconnect();
            cluster.runFor(STABILISATION_TIME, "running with only voting-only nodes connected");
            for (ClusterNode votingOnly : votingOnlyNodes(cluster)) {
                assertFalse("voting-only nodes alone never elect a leader", votingOnly.isLeader());
            }
            assertNoVotingOnlyClusterManagerApplied(cluster);
            fullNode.heal();
            stabiliseWithFullLeader(cluster);
        }
    }

    public void testRandomTopologiesWithRandomDisruptions() {
        final int size = between(3, 7);
        final int votingOnlyCount = between(1, size - 1);
        try (Cluster cluster = newCluster(size - votingOnlyCount, votingOnlyCount)) {
            for (int i = 0; i < 3; i++) {
                runRandomly(cluster);
                stabiliseWithFullLeader(cluster);
            }
        }
    }

    public void testVotingOnlyNodesNeverCommitUnderRandomDisruptions() {
        // safety (I3) is checked by the framework's linearizability checker inside stabilise(), I1 by the commit recorder
        final Set<Integer> indices = new HashSet<>();
        final int size = between(3, 5);
        for (int i = 1; i < size; i++) {
            if (randomBoolean()) {
                indices.add(i);
            }
        }
        votingOnlyNodeIndices = indices;
        try (Cluster cluster = new Cluster(size)) {
            runRandomly(cluster);
            stabiliseWithFullLeader(cluster);
        }
    }
}
