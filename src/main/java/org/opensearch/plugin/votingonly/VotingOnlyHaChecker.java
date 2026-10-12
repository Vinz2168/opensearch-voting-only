/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.votingonly;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.ClusterChangedEvent;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.ClusterStateListener;
import org.opensearch.cluster.coordination.CoordinationMetadata.VotingConfiguration;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodes;

import java.util.ArrayList;
import java.util.List;

/**
 * Warns when the voting configuration has fewer than two cluster-manager-eligible nodes that are not voting-only, because losing one of
 * them then leaves no node that can become the elected cluster manager. Only the elected cluster manager reports, once per change of the
 * condition. It never blocks anything. See DESIGN.md, section 6.
 */
public class VotingOnlyHaChecker implements ClusterStateListener {

    private static final Logger logger = LogManager.getLogger(VotingOnlyHaChecker.class);

    /**
     * The number of full cluster-manager-eligible nodes the voting configuration needs for high availability.
     */
    static final int REQUIRED_FULL_NODES = 2;

    // only accessed on the cluster applier thread
    private VotingConfiguration lastConfiguration;
    private boolean lastViolated;

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        try {
            if (event.localNodeClusterManager() == false) {
                lastConfiguration = null;
                lastViolated = false;
                return;
            }
            check(event.state());
        } catch (Exception e) {
            logger.debug("failed to check the voting configuration for voting-only nodes", e);
        }
    }

    private void check(ClusterState state) {
        final VotingConfiguration configuration = state.getLastCommittedConfiguration();
        final Evaluation evaluation = evaluate(configuration, state.nodes());
        final boolean firstCheck = lastConfiguration == null;
        if (evaluation.violated()) {
            if (firstCheck || lastViolated == false || configuration.equals(lastConfiguration) == false) {
                logger.warn(evaluation.message(configuration));
            }
        } else if (firstCheck == false && lastViolated) {
            logger.info(
                "voting configuration {} now contains at least [{}] cluster-manager-eligible nodes that are not voting-only",
                configuration,
                REQUIRED_FULL_NODES
            );
        }
        lastConfiguration = configuration;
        lastViolated = evaluation.violated();
    }

    static Evaluation evaluate(VotingConfiguration configuration, DiscoveryNodes nodes) {
        final List<String> fullNodeNames = new ArrayList<>();
        int votingOnlyNodes = 0;
        int unknownNodes = 0;
        for (String nodeId : configuration.getNodeIds()) {
            final DiscoveryNode node = nodes.get(nodeId);
            if (node == null) {
                unknownNodes++;
            } else if (VotingOnlyNodeRole.isVotingOnlyNode(node)) {
                votingOnlyNodes++;
            } else if (node.isClusterManagerNode()) {
                fullNodeNames.add(node.getName());
            }
        }
        return new Evaluation(fullNodeNames, votingOnlyNodes, unknownNodes);
    }

    /**
     * The voting configuration seen from the elected cluster manager. Voters missing from the cluster state have unknown roles and are
     * counted as possibly full, so a down full node of a 2 + 1 cluster does not trigger the warning.
     */
    record Evaluation(List<String> fullNodeNames, int votingOnlyNodes, int unknownNodes) {

        boolean violated() {
            return votingOnlyNodes > 0 && fullNodeNames.size() + unknownNodes < REQUIRED_FULL_NODES;
        }

        String message(VotingConfiguration configuration) {
            final StringBuilder message = new StringBuilder().append("voting configuration ")
                .append(configuration)
                .append(" contains [")
                .append(fullNodeNames.size())
                .append("] cluster-manager-eligible node(s) that are not voting-only ")
                .append(fullNodeNames)
                .append(" and [")
                .append(votingOnlyNodes)
                .append("] voting-only node(s); at least [")
                .append(REQUIRED_FULL_NODES)
                .append("] are required for high availability");
            if (fullNodeNames.size() == 1) {
                message.append(": if [").append(fullNodeNames.get(0)).append("] fails, no cluster manager can be elected");
            }
            return message.append(". Add full cluster-manager-eligible nodes or remove [")
                .append(VotingOnlyNodeRole.ROLE_NAME)
                .append("] from a node")
                .toString();
        }
    }
}
