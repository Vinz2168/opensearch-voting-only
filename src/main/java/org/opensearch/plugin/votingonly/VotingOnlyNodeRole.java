/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.votingonly;

import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.common.settings.Setting;

import java.util.List;

/**
 * The {@code voting_only} node role. A node with this role (and the cluster-manager role) takes part in elections and in the voting
 * configuration like any other cluster-manager-eligible node, but never becomes the stable elected cluster manager.
 */
public final class VotingOnlyNodeRole extends DiscoveryNodeRole {

    /**
     * The role name. It must be exactly {@code voting_only}: {@code RepositoriesService} looks the role up by this name.
     */
    public static final String ROLE_NAME = "voting_only";

    /**
     * The abbreviation shown in {@code _cat/nodes}.
     */
    public static final String ROLE_NAME_ABBREVIATION = "v";

    public static final VotingOnlyNodeRole INSTANCE = new VotingOnlyNodeRole();

    private VotingOnlyNodeRole() {
        super(ROLE_NAME, ROLE_NAME_ABBREVIATION);
    }

    @Override
    public Setting<Boolean> legacySetting() {
        // only configurable through node.roles
        return null;
    }

    @Override
    @SuppressWarnings("deprecation") // the legacy master role is still accepted
    public void validateRole(List<DiscoveryNodeRole> roles) {
        final boolean clusterManagerEligible = roles.contains(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE)
            || roles.contains(DiscoveryNodeRole.MASTER_ROLE);
        if (clusterManagerEligible == false) {
            throw new IllegalArgumentException(
                "node role ["
                    + ROLE_NAME
                    + "] requires the ["
                    + DiscoveryNodeRole.CLUSTER_MANAGER_ROLE.roleName()
                    + "] role (or the legacy ["
                    + DiscoveryNodeRole.MASTER_ROLE.roleName()
                    + "] role) to be set; "
                    + ROLE_NAME
                    + " alone is not a valid configuration"
            );
        }
    }

    /**
     * Whether the given node has the {@code voting_only} role. The check is by name so that it also works on nodes that see the role as a
     * dynamic role because they do not have this plugin installed.
     */
    public static boolean isVotingOnlyNode(DiscoveryNode node) {
        return node.getRoles().stream().anyMatch(role -> ROLE_NAME.equals(role.roleName()));
    }

    /**
     * Whether the given node is cluster-manager-eligible and not voting-only, i.e. it may become the stable elected cluster manager.
     */
    public static boolean isFullClusterManagerNode(DiscoveryNode node) {
        return node.isClusterManagerNode() && isVotingOnlyNode(node) == false;
    }
}
