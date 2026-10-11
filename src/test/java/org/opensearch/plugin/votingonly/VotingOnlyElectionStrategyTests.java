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
import org.opensearch.cluster.coordination.Join;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.opensearch.plugin.votingonly.VotingOnlyNodeRoleTests.node;

public class VotingOnlyElectionStrategyTests extends OpenSearchTestCase {

    private static final long TERM = 10;
    private static final long ACCEPTED_TERM = 5;
    private static final long ACCEPTED_VERSION = 50;

    private final VotingOnlyElectionStrategy strategy = new VotingOnlyElectionStrategy();

    private final DiscoveryNode full1 = node("full1", Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, DiscoveryNodeRole.DATA_ROLE));
    private final DiscoveryNode full2 = node("full2", Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE));
    private final DiscoveryNode votingOnly1 = node("vo1", Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, VotingOnlyNodeRole.INSTANCE));
    private final DiscoveryNode votingOnly2 = node("vo2", Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, VotingOnlyNodeRole.INSTANCE));

    private static VotingConfiguration config(DiscoveryNode... nodes) {
        return new VotingConfiguration(List.of(nodes).stream().map(DiscoveryNode::getId).collect(Collectors.toSet()));
    }

    private static Join join(DiscoveryNode source, DiscoveryNode target, long lastAcceptedTerm, long lastAcceptedVersion) {
        return new Join(source, target, TERM, lastAcceptedTerm, lastAcceptedVersion);
    }

    private boolean isQuorum(DiscoveryNode candidate, VotingConfiguration configuration, VoteCollection votes) {
        return strategy.isElectionQuorum(candidate, TERM, ACCEPTED_TERM, ACCEPTED_VERSION, configuration, configuration, votes);
    }

    private VoteCollection votes(Join... joins) {
        final VoteCollection votes = new VoteCollection();
        for (Join join : joins) {
            votes.addJoinVote(join);
        }
        return votes;
    }

    public void testFullCandidateIsUnaffected() {
        final VotingConfiguration configuration = config(full1, full2, votingOnly1);
        // I2: the vote of a voting-only node counts like any other vote
        final VoteCollection votes = votes(
            join(full1, full1, ACCEPTED_TERM, ACCEPTED_VERSION),
            join(votingOnly1, full1, ACCEPTED_TERM, ACCEPTED_VERSION)
        );
        assertTrue(isQuorum(full1, configuration, votes));
    }

    public void testFullCandidateStillNeedsAMajority() {
        final VotingConfiguration configuration = config(full1, full2, votingOnly1);
        assertFalse(isQuorum(full1, configuration, votes(join(full1, full1, ACCEPTED_TERM, ACCEPTED_VERSION))));
    }

    public void testVotingOnlyCandidateWinsWithStalerFullVoterByVersion() {
        final VotingConfiguration configuration = config(full1, full2, votingOnly1);
        final VoteCollection votes = votes(
            join(votingOnly1, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION),
            join(full1, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION - 1)
        );
        assertTrue(isQuorum(votingOnly1, configuration, votes));
    }

    public void testVotingOnlyCandidateWinsWithStalerFullVoterByTerm() {
        final VotingConfiguration configuration = config(full1, full2, votingOnly1);
        // an older term wins even with a higher version: freshness is ordered by term first
        final VoteCollection votes = votes(
            join(votingOnly1, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION),
            join(full1, votingOnly1, ACCEPTED_TERM - 1, ACCEPTED_VERSION + 100)
        );
        assertTrue(isQuorum(votingOnly1, configuration, votes));
    }

    public void testVotingOnlyCandidateLosesWhenFullVotersAreAsFresh() {
        final VotingConfiguration configuration = config(full1, full2, votingOnly1);
        final VoteCollection votes = votes(
            join(votingOnly1, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION),
            join(full1, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION),
            join(full2, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION)
        );
        assertFalse(isQuorum(votingOnly1, configuration, votes));
    }

    public void testVotingOnlyCandidateLosesWithOnlyVotingOnlyVoters() {
        final VotingConfiguration configuration = config(votingOnly1, votingOnly2, full1);
        final VoteCollection votes = votes(
            join(votingOnly1, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION),
            join(votingOnly2, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION - 1)
        );
        assertFalse(isQuorum(votingOnly1, configuration, votes));
    }

    public void testVotingOnlyCandidateLosesWithSingleNodeConfiguration() {
        final VotingConfiguration configuration = config(votingOnly1);
        assertFalse(isQuorum(votingOnly1, configuration, votes(join(votingOnly1, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION))));
    }

    public void testStalerFullVoterDoesNotOverrideTheMajorityRule() {
        // I3: the extra constraint only restricts, it never replaces the majority requirement
        final VotingConfiguration configuration = config(full1, full2, votingOnly1, votingOnly2, node("other", Set.of()));
        final VoteCollection votes = votes(
            join(votingOnly1, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION),
            join(full1, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION - 1)
        );
        assertFalse(isQuorum(votingOnly1, configuration, votes));
    }

    public void testVotesWithoutJoinDetails() {
        // Coordinator#onFoundPeersUpdated only adds votes, without joins
        final VotingConfiguration configuration = config(full1, full2, votingOnly1);
        final VoteCollection withFullPeer = new VoteCollection();
        withFullPeer.addVote(votingOnly1);
        withFullPeer.addVote(full1);
        assertTrue(isQuorum(votingOnly1, configuration, withFullPeer));

        final VotingConfiguration votingOnlyHeavy = config(votingOnly1, votingOnly2, full1);
        final VoteCollection onlyVotingOnlyPeers = new VoteCollection();
        onlyVotingOnlyPeers.addVote(votingOnly1);
        onlyVotingOnlyPeers.addVote(votingOnly2);
        assertFalse(isQuorum(votingOnly1, votingOnlyHeavy, onlyVotingOnlyPeers));
    }

    public void testAbdicationNeverTargetsVotingOnlyNode() {
        // Coordinator abdication builds joins that all carry the leader's current state
        final VotingConfiguration configuration = config(full1, full2, votingOnly1);
        final VoteCollection votes = new VoteCollection();
        for (DiscoveryNode voter : List.of(full1, full2, votingOnly1)) {
            votes.addJoinVote(join(voter, votingOnly1, ACCEPTED_TERM, ACCEPTED_VERSION));
        }
        assertFalse(isQuorum(votingOnly1, configuration, votes));
        assertTrue(isQuorum(full2, configuration, votes));
    }

    public void testMonotoneInVotes() {
        final List<DiscoveryNode> nodes = new ArrayList<>();
        nodes.add(votingOnly1);
        nodes.add(votingOnly2);
        nodes.add(full1);
        nodes.add(full2);
        for (int i = 0; i < 5; i++) {
            final boolean votingOnly = randomBoolean();
            nodes.add(
                node(
                    "n" + i,
                    votingOnly
                        ? Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, VotingOnlyNodeRole.INSTANCE)
                        : Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE)
                )
            );
        }
        final VotingConfiguration configuration = config(randomSubsetOf(between(1, nodes.size()), nodes).toArray(new DiscoveryNode[0]));
        final DiscoveryNode candidate = randomFrom(nodes);
        for (int iteration = 0; iteration < 100; iteration++) {
            final VoteCollection votes = new VoteCollection();
            boolean won = false;
            for (DiscoveryNode voter : randomSubsetOf(nodes)) {
                final long term = randomLongBetween(ACCEPTED_TERM - 1, ACCEPTED_TERM);
                final long version = term < ACCEPTED_TERM ? randomLongBetween(0, 1000) : randomLongBetween(0, ACCEPTED_VERSION);
                votes.addJoinVote(join(voter, candidate, term, version));
                final boolean nowWon = isQuorum(candidate, configuration, votes);
                assertFalse("an election cannot go from won to not won", won && nowWon == false);
                won = nowWon;
            }
        }
    }

    public void testIsStrictlyOlder() {
        assertTrue(VotingOnlyElectionStrategy.isStrictlyOlder(1, 100, 2, 0));
        assertTrue(VotingOnlyElectionStrategy.isStrictlyOlder(2, 0, 2, 1));
        assertFalse(VotingOnlyElectionStrategy.isStrictlyOlder(2, 1, 2, 1));
        assertFalse(VotingOnlyElectionStrategy.isStrictlyOlder(3, 0, 2, 100));
    }
}
