/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.votingonly;

import org.opensearch.cluster.coordination.ElectionStrategy;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.discovery.DiscoveryModule;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.plugins.DiscoveryPlugin;
import org.opensearch.plugins.NetworkPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.TransportInterceptor;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Adds the {@code voting_only} node role, the {@code voting_only} election strategy and, on voting-only nodes, the transport interceptor
 * that keeps them from committing cluster state publications. On every node it also registers the {@link VotingOnlyHaChecker}, which only
 * reports on the elected cluster manager.
 */
public class VotingOnlyNodePlugin extends Plugin implements DiscoveryPlugin, NetworkPlugin {

    private static final String NODE_ROLES_KEY = "node.roles";
    private static final String NODE_NAME_KEY = "node.name";

    private final boolean votingOnly;
    private final String nodeName;

    public VotingOnlyNodePlugin(Settings settings) {
        this.votingOnly = hasVotingOnlyRole(settings);
        this.nodeName = settings.get(NODE_NAME_KEY, "_local");
        if (votingOnly) {
            final String strategy = DiscoveryModule.ELECTION_STRATEGY_SETTING.get(settings);
            if (VotingOnlyElectionStrategy.NAME.equals(strategy) == false) {
                throw new IllegalArgumentException(
                    "node role ["
                        + VotingOnlyNodeRole.ROLE_NAME
                        + "] requires ["
                        + DiscoveryModule.ELECTION_STRATEGY_SETTING.getKey()
                        + ": "
                        + VotingOnlyElectionStrategy.NAME
                        + "], but it is set to ["
                        + strategy
                        + "]; set it to ["
                        + VotingOnlyElectionStrategy.NAME
                        + "] on every cluster-manager-eligible node"
                );
            }
        }
    }

    /**
     * Reads the raw {@code node.roles} list: the plugin is constructed before its role is registered, so the typed setting cannot be used.
     */
    static boolean hasVotingOnlyRole(Settings settings) {
        return settings.getAsList(NODE_ROLES_KEY, Collections.emptyList())
            .stream()
            .anyMatch(role -> VotingOnlyNodeRole.ROLE_NAME.equals(role.trim().toLowerCase(Locale.ROOT)));
    }

    boolean isVotingOnly() {
        return votingOnly;
    }

    @Override
    public Set<DiscoveryNodeRole> getRoles() {
        return Set.of(VotingOnlyNodeRole.INSTANCE);
    }

    @Override
    public Map<String, ElectionStrategy> getElectionStrategies() {
        return Map.of(VotingOnlyElectionStrategy.NAME, new VotingOnlyElectionStrategy());
    }

    @Override
    public List<TransportInterceptor> getTransportInterceptors(NamedWriteableRegistry namedWriteableRegistry, ThreadContext threadContext) {
        if (votingOnly) {
            return List.of(new VotingOnlyPublicationInterceptor(nodeName));
        }
        return Collections.emptyList();
    }

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier
    ) {
        final VotingOnlyHaChecker haChecker = new VotingOnlyHaChecker();
        clusterService.addListener(haChecker);
        return List.of(haChecker);
    }
}
