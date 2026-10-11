/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.votingonly;

import org.opensearch.cluster.coordination.CoordinationMetadata.VotingConfiguration;
import org.opensearch.cluster.coordination.CoordinationState.VoteCollection;
import org.opensearch.cluster.coordination.ElectionStrategy;
import org.opensearch.cluster.coordination.Join;
import org.opensearch.cluster.node.DiscoveryNode;

/**
 * Election strategy that is aware of {@code voting_only} nodes. Selected with {@code cluster.election.strategy: voting_only}.
 * <p>
 * Candidates that are not voting-only are unaffected. A voting-only candidate only satisfies the quorum if at least one full
 * cluster-manager node among its voters has a strictly older last-accepted state (or, when no join information is available or the
 * candidate has never accepted a state from a leader, if at least one full cluster-manager node is among the voters). Such an election
 * is only useful to propagate the candidate's newer state (or its bootstrap configuration) to
 * full nodes: the publication is then failed on purpose by {@link VotingOnlyPublicationInterceptor}. See DESIGN.md, section 4.
 * <p>
 * The constraint is monotone in the set of votes, as required by {@code CoordinationState#handleJoin}.
 */
public class VotingOnlyElectionStrategy extends ElectionStrategy {

    public static final String NAME = "voting_only";

    @Override
    protected boolean satisfiesAdditionalQuorumConstraints(
        DiscoveryNode localNode,
        long localCurrentTerm,
        long localAcceptedTerm,
        long localAcceptedVersion,
        VotingConfiguration lastCommittedConfiguration,
        VotingConfiguration lastAcceptedConfiguration,
        VoteCollection joinVotes
    ) {
        if (VotingOnlyNodeRole.isVotingOnlyNode(localNode) == false) {
            return true;
        }
        for (DiscoveryNode voter : joinVotes.nodes()) {
            if (VotingOnlyNodeRole.isFullClusterManagerNode(voter) == false) {
                continue;
            }
            if (localAcceptedTerm == 0) {
                // nothing has ever been published by a leader, so this node holds at most the bootstrap configuration; the full
                // voters may not have it (they may not be bootstrapped) and cannot be told apart by term and version, so allow one
                // election to propagate it
                return true;
            }
            final Join join = findJoin(joinVotes, voter);
            if (join == null) {
                // a vote without join details: the caller only asks whether an election could be won
                return true;
            }
            if (isStrictlyOlder(join.getLastAcceptedTerm(), join.getLastAcceptedVersion(), localAcceptedTerm, localAcceptedVersion)) {
                return true;
            }
        }
        return false;
    }

    private static Join findJoin(VoteCollection joinVotes, DiscoveryNode voter) {
        for (Join join : joinVotes.getJoins()) {
            if (join.getSourceNode().getId().equals(voter.getId())) {
                return join;
            }
        }
        return null;
    }

    static boolean isStrictlyOlder(long term, long version, long otherTerm, long otherVersion) {
        return term < otherTerm || (term == otherTerm && version < otherVersion);
    }
}
