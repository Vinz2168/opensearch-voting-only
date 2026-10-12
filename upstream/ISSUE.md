# [Feature Request] Voting-only cluster-manager nodes (`voting_only` role)

<!-- Draft for opensearch-project/OpenSearch, "Feature request" template.
     Related component: Cluster Manager. Suggested labels: enhancement, Cluster Manager.
     The owner opens it; it is not opened automatically. -->

## Is your feature request related to a problem? Please describe

A common resilient layout is **two full cluster-manager nodes plus a small tiebreaker** in a third
location (2 + 1). Today the tiebreaker can only be a regular cluster-manager-eligible node, so it can
be elected cluster manager. A small VM, possibly on a high-latency link, then ends up coordinating the
whole cluster. This tends to happen during rolling restarts of the two real cluster-manager nodes,
which is exactly when it hurts the most.

OpenSearch has no way to make a node *vote* without being *electable*. Users have asked for this on
the forum:

- 2023, OpenSearch 2.11: [How to make a "Voting-only" server?](https://forum.opensearch.org/t/how-to-make-a-voting-only-server/16348).
  `node.roles: [ master, voting_only ]` is suggested, but the node is then seen with that role and
  does not behave as a voting-only node. No resolution.
- 2024, OpenSearch 2.13.0: [Voting only master/cluster_manager](https://forum.opensearch.org/t/voting-only-master-cluster-manager/19248).
  The 2 + 1 case: after the physical servers are restarted, the VM meant as tiebreaker becomes
  cluster manager. The poster points at `RepositoriesService` and concludes that `voting_only` is
  not implemented. No resolution.

Why OpenSearch lacks it: in Elasticsearch this capability exists as a voting-only role, but it ships
as an X-Pack module under the Elastic License. It was therefore never part of the Apache-2.0
Elasticsearch 7.10.2 code that OpenSearch was forked from. Only the name-based check in
`RepositoriesService` came along, and it always returns false today. We found no existing OpenSearch issue,
RFC or third-party plugin for it.

## Describe the solution you'd like

A new opt-in node role, **`voting_only`** (abbreviation `v` in `_cat/nodes`), valid only together with
`cluster_manager`:

```yaml
node.roles: [ cluster_manager, voting_only ]
```

A voting-only node:

- is part of the voting configuration, and its vote counts exactly like any other (no change to
  quorum safety: every decision still needs a majority);
- stores the cluster state like any cluster-manager-eligible node;
- **never becomes the stable elected cluster manager**;
- is skipped by repository verification when dedicated. `RepositoriesService` already contains
  this name-based check (`isDedicatedVotingOnlyNode`), which currently always returns false.

### Reference implementation (plugin, no core changes)

We built and tested this as a standalone plugin, `voting-only`, that uses only existing extension
points: https://github.com/Vinz2168/opensearch-voting-only

| Need | Extension point |
|---|---|
| Role | `Plugin#getRoles()` |
| Quorum rule | `DiscoveryPlugin#getElectionStrategies()` + `ElectionStrategy#satisfiesAdditionalQuorumConstraints`, selected with `cluster.election.strategy: voting_only` |
| Never commit as leader | `NetworkPlugin#getTransportInterceptors()` on voting-only nodes: publish requests are delivered, publish responses are turned into failures, commit requests are never sent |
| HA warning | `Plugin#createComponents()` → `ClusterStateListener`: the elected cluster manager warns when fewer than two voters are not voting-only |
| Repository verification | existing `RepositoriesService#isDedicatedVotingOnlyNode` (matches on the role name, no change) |

All of these are identical on `3.9.0` and on `main` (checked at d016718).

The hard part, described publicly in elastic/elasticsearch#80331, is that a voting-only node
sometimes **must** win an election: when it is the only node with the latest cluster state (for
example, the old leader died while the other full node was still catching up). The plugin handles it
in two parts:

- **Election rule.** A voting-only candidate only reaches a quorum if at least one *full*
  cluster-manager node among its voters has a strictly older accepted state. There is also a
  bootstrap special case, when nothing has ever been published. The rule is monotone in the votes,
  as `CoordinationState` requires. It also gates pre-voting, so a voting-only node with nothing to
  propagate never even bumps the term.
- **Failed publication.** In such an election the voting-only node publishes, and the full nodes
  *accept* the newer state. The publication then fails before any commit (it is never committed),
  the voting-only node steps down, and an up-to-date full node wins the next round. Turning a
  response into a failure is equivalent to losing it on the network, so Zen2 safety is unchanged.

Design document with the invariants, failure scenarios and invariant-to-test mapping:
https://github.com/Vinz2168/opensearch-voting-only/blob/main/DESIGN.md

### Test results (OpenSearch 3.9.0 and `main`, JDK 21)

CI: https://github.com/Vinz2168/opensearch-voting-only/actions

- `./gradlew spotlessApply precommit check` passes on 3.9.0, and `./gradlew precommit check` passes
  against `main` (3.10.0-SNAPSHOT at d016718). 58 unit and coordination tests, 8 integration tests.
  No OpenSearch core classes are modified.
- Deterministic coordination tests (`AbstractCoordinatorTestCase`) cover:
  - 2 + 1 election, and loss of each node;
  - every 2-vs-1 partition, the 3-way split and one-way partitions;
  - stale-state recovery, rolling and random restarts;
  - several voting-only nodes, random topologies under random disruptions.

  Every node checks on the receiving side that no commit ever comes from a voting-only node. The
  suite passed 30 consecutive random-seed iterations (450 test runs).
- `InternalTestCluster` integration tests: 2 + 1 election, fail-over of either full node, rolling
  restarts, restart of the voting-only node, startup validation, repository verification, HA
  warning with one full node and two voting-only nodes.
- Docker Compose 2 + 1 scenario on the official 3.9.0 image: a rolling restart of both full nodes,
  plus a "fast" rolling restart where the tiebreaker is the only holder of the latest state.
  `_cat/cluster_manager` was polled on all nodes twice a second, and the tiebreaker was never
  reported as cluster manager. In the stale-state case the restarted full node took over with the
  data it had missed.

### Known limitations

- During the rare temporary elections, core logs a WARN (`failing [... elected-as-cluster-manager ...]`)
  on the voting-only node, plus an INFO on the full node. The plugin adds one INFO line explaining
  that this is intended. Cleaner logs would need small core changes.
- If voting-only nodes form a majority of the voting configuration and the full nodes are lost, no
  leader can be elected. That is inherent to the role: high availability needs at least two
  cluster-manager-eligible nodes that are not voting-only. The plugin logs a warning when the
  voting configuration violates this, but does not block startup.

## Describe alternatives you've considered

- **Keep the tiebreaker as a normal cluster-manager-eligible node.** It can then be elected, which
  is the problem described above.
- **Voting configuration exclusions.** These are meant for decommissioning. They remove the vote,
  which is the opposite of what a tiebreaker needs.
- **Ship it as an external plugin only.** That works (the reference implementation above), but every
  cluster-manager-eligible node needs the plugin installed. The role is a natural core concept: core
  already refers to it in `RepositoriesService`.

### Open question: optional plugin or bundled module?

The code needs no core changes, so it can land in either form. We would like the maintainers'
preference before opening a PR:

| | Optional plugin (`plugins/voting-only`) | Bundled module (`modules/voting-only`) |
|---|---|---|
| Installation | `opensearch-plugin install voting-only` on every cluster-manager-eligible node | always present, nothing to install |
| Mixed-cluster risk | a node without the plugin fails to start with `cluster.election.strategy: voting_only` (safe, but an operational step) | none |
| Default footprint | none unless installed | role and strategy registered on every node, inactive unless configured |
| Opt-in | install + `node.roles` + `cluster.election.strategy` | `node.roles` + `cluster.election.strategy` |

A third option is moving the rule into the default `ElectionStrategy` in `server/`, which would
remove the need for the setting. That is a bigger change to core coordination code, so we suggest
starting with one of the two forms above.

## Compatibility impact

- **New, opt-in role and election strategy**, with no change for clusters that do not use them.
- **No REST or transport API changes.** The role travels in `DiscoveryNode` like any other role.
  Nodes that do not know it see it as a dynamic role with the same name.
- As a plugin or module, the feature is enabled with `cluster.election.strategy: voting_only`,
  which must be identical on all cluster-manager-eligible nodes. If the rule later moves into the
  default strategy, that setting would no longer be needed.
- The deterministic coordination test framework would benefit from one small fix:
  `AbstractCoordinatorTestCase.Cluster.ClusterNode#restartedNode()` always recreates the node with
  `DiscoveryNodeRole.BUILT_IN_ROLES`, so custom roles are lost on reboot. Using
  `localNode.getRoles()` would fix it. The plugin's tests currently work around this.

## Clean-room statement

The reference implementation was written without opening, fetching, searching or paraphrasing
Elasticsearch X-Pack sources or any code under the Elastic License, SSPL or AGPL. The only inputs
were OpenSearch source code (Apache 2.0), public behavioural documentation, the text of public issues
(notably elastic/elasticsearch#80331), and general knowledge of the Zen2 coordination algorithm.

## Additional context

If maintainers agree with the direction, we are happy to open a PR that moves the role and the rule
into core (`server/`), with the tests ported to `CoordinatorTests`.
