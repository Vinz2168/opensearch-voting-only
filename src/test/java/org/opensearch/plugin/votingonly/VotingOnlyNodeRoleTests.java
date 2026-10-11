/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.votingonly;

import org.opensearch.Version;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.common.settings.Settings;
import org.opensearch.node.NodeRoleSettings;
import org.opensearch.test.OpenSearchTestCase;
import org.junit.After;
import org.junit.Before;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;

public class VotingOnlyNodeRoleTests extends OpenSearchTestCase {

    @Before
    public void registerRole() {
        DiscoveryNode.setAdditionalRoles(Set.of(VotingOnlyNodeRole.INSTANCE));
        // same order as Node: the deprecated master role is added after the plugin roles
        DiscoveryNode.setDeprecatedMasterRole();
    }

    @After
    public void unregisterRole() {
        DiscoveryNode.setAdditionalRoles(Collections.emptySet());
    }

    public void testRoleNameAndAbbreviation() {
        assertEquals("voting_only", VotingOnlyNodeRole.INSTANCE.roleName());
        assertEquals("v", VotingOnlyNodeRole.INSTANCE.roleNameAbbreviation());
        assertFalse(VotingOnlyNodeRole.INSTANCE.canContainData());
        assertFalse(VotingOnlyNodeRole.INSTANCE.isEnabledByDefault(Settings.EMPTY));
    }

    public void testAbbreviationDoesNotClashWithBuiltInRoles() {
        for (DiscoveryNodeRole role : DiscoveryNodeRole.BUILT_IN_ROLES) {
            assertNotEquals(role.roleName(), VotingOnlyNodeRole.ROLE_NAME_ABBREVIATION, role.roleNameAbbreviation());
        }
    }

    public void testAcceptedWithClusterManager() {
        final List<DiscoveryNodeRole> roles = NodeRoleSettings.NODE_ROLES_SETTING.get(
            Settings.builder().putList("node.roles", "cluster_manager", "voting_only").build()
        );
        assertThat(roles, hasItem(VotingOnlyNodeRole.INSTANCE));
        assertThat(roles, hasItem(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE));
    }

    public void testAcceptedWithClusterManagerAndOtherRoles() {
        final List<DiscoveryNodeRole> roles = NodeRoleSettings.NODE_ROLES_SETTING.get(
            Settings.builder().putList("node.roles", "cluster_manager", "voting_only", "data", "ingest").build()
        );
        assertThat(roles, hasItem(VotingOnlyNodeRole.INSTANCE));
    }

    public void testAcceptedWithLegacyMaster() {
        final List<DiscoveryNodeRole> roles = NodeRoleSettings.NODE_ROLES_SETTING.get(
            Settings.builder().putList("node.roles", "master", "voting_only").build()
        );
        assertThat(roles, hasItem(VotingOnlyNodeRole.INSTANCE));
        assertWarnings(DiscoveryNodeRole.MASTER_ROLE_DEPRECATION_MESSAGE);
    }

    public void testRejectedAlone() {
        final IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> NodeRoleSettings.NODE_ROLES_SETTING.get(Settings.builder().putList("node.roles", "voting_only").build())
        );
        assertThat(e.getMessage(), containsString("node role [voting_only] requires the [cluster_manager] role"));
    }

    public void testRejectedWithoutClusterManager() {
        final IllegalArgumentException e = expectThrows(
            IllegalArgumentException.class,
            () -> NodeRoleSettings.NODE_ROLES_SETTING.get(Settings.builder().putList("node.roles", "data", "ingest", "voting_only").build())
        );
        assertThat(e.getMessage(), containsString("node role [voting_only] requires the [cluster_manager] role"));
    }

    public void testIsVotingOnlyNode() {
        final DiscoveryNode votingOnly = node("v", Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, VotingOnlyNodeRole.INSTANCE));
        final DiscoveryNode full = node("f", Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, DiscoveryNodeRole.DATA_ROLE));
        final DiscoveryNode data = node("d", Set.of(DiscoveryNodeRole.DATA_ROLE));
        assertTrue(VotingOnlyNodeRole.isVotingOnlyNode(votingOnly));
        assertFalse(VotingOnlyNodeRole.isFullClusterManagerNode(votingOnly));
        assertFalse(VotingOnlyNodeRole.isVotingOnlyNode(full));
        assertTrue(VotingOnlyNodeRole.isFullClusterManagerNode(full));
        assertFalse(VotingOnlyNodeRole.isFullClusterManagerNode(data));
    }

    public void testIsVotingOnlyNodeMatchesUnregisteredRoleByName() {
        // a node without the plugin sees the role as a dynamic role with the same name
        DiscoveryNode.setAdditionalRoles(Collections.emptySet());
        final DiscoveryNodeRole dynamicRole = DiscoveryNode.getRoleFromRoleName("voting_only");
        assertTrue(dynamicRole.isDynamicRole());
        final DiscoveryNode votingOnly = node("v", Set.of(DiscoveryNodeRole.CLUSTER_MANAGER_ROLE, dynamicRole));
        assertTrue(VotingOnlyNodeRole.isVotingOnlyNode(votingOnly));
    }

    static DiscoveryNode node(String id, Set<DiscoveryNodeRole> roles) {
        return new DiscoveryNode(id, id, buildNewFakeTransportAddress(), Collections.emptyMap(), roles, Version.CURRENT);
    }
}
