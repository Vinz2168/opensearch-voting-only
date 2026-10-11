/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.repositories;

import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.plugin.votingonly.VotingOnlyNodeRole;
import org.opensearch.test.OpenSearchTestCase;

import java.util.Set;

/**
 * Checks that the existing name-based check in {@link RepositoriesService} recognises the role provided by this plugin. Lives in the
 * {@code org.opensearch.repositories} package because the method under test is package-private.
 */
public class VotingOnlyRepositoriesServiceTests extends OpenSearchTestCase {

    public void testDedicatedVotingOnlyNodeIsRecognised() {
        assertTrue(
            RepositoriesService.isDedicatedVotingOnlyNode(Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, VotingOnlyNodeRole.INSTANCE))
        );
    }

    public void testVotingOnlyDataNodeIsNotDedicated() {
        assertFalse(
            RepositoriesService.isDedicatedVotingOnlyNode(
                Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, DiscoveryNodeRole.DATA_ROLE, VotingOnlyNodeRole.INSTANCE)
            )
        );
    }

    public void testFullClusterManagerIsNotVotingOnly() {
        assertFalse(RepositoriesService.isDedicatedVotingOnlyNode(Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE)));
    }
}
