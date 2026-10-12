<!-- Draft PR description for opensearch-project/OpenSearch, following .github/pull_request_template.md.
     Open it only after the feature request has been discussed. Replace #NNNN with the issue number,
     and plugins/voting-only with modules/voting-only if the maintainers prefer a bundled module.
     Suggested title (it becomes the release-notes line): Add voting_only cluster-manager node role -->

### Description

Adds an opt-in **`voting_only` node role** (abbreviation `v`) through a new `voting-only` plugin
in `plugins/voting-only`. A voting-only node is cluster-manager-eligible: it votes in elections, is
part of the voting configuration and stores the cluster state, but it **never becomes the stable
elected cluster manager**. The typical use is a small tiebreaker in a 2 + 1 topology, so that a
rolling restart of the two full cluster-manager nodes never leaves the cluster coordinated by the
tiebreaker.

There are **no changes to `server/`**. The plugin uses only existing extension points, and core
already looks for a role with this exact name in `RepositoriesService`.

#### Configuration

```yaml
# voting-only (tiebreaker) node
node.roles: [ cluster_manager, voting_only ]
cluster.election.strategy: voting_only

# every other cluster-manager-eligible node
node.roles: [ cluster_manager, data ]
cluster.election.strategy: voting_only
```

- `voting_only` is a modifier: without `cluster_manager` (or the legacy `master`) the node fails to
  start with a clear message. It can be combined with other roles, such as `data`, and it is not
  part of any default role set.
- The strategy is opt-in and must be identical on all cluster-manager-eligible nodes. A node with
  the `voting_only` role refuses to start without it.
- High availability needs at least two cluster-manager-eligible nodes that are not voting-only. The
  elected cluster manager logs a WARN when the voting configuration violates this. It never blocks.

#### Extension points

| Need | Extension point | Change in core |
|---|---|---|
| Role `voting_only` / `v` | `Plugin#getRoles()`, `DiscoveryNodeRole#validateRole` | none |
| Quorum rule | `DiscoveryPlugin#getElectionStrategies()` + `ElectionStrategy#satisfiesAdditionalQuorumConstraints`, selected by `cluster.election.strategy: voting_only` | none |
| Never commit as leader | `NetworkPlugin#getTransportInterceptors()`, installed only on voting-only nodes | none |
| HA warning | `Plugin#createComponents()` → `ClusterStateListener` | none |
| Repository verification skipped on dedicated voting-only nodes | `RepositoriesService#isDedicatedVotingOnlyNode` (already matches on the role name) | none |

#### How it works

1. **Quorum rule.** Full candidates are unaffected. A voting-only candidate reaches a quorum only
   if at least one *full* cluster-manager node among its voters has a strictly older accepted state
   (plus a bootstrap case, when nothing has been published yet). The rule is ANDed with the
   majority checks, so it only restricts. It is also monotone in the votes, as
   `CoordinationState#handleJoin` asserts. It gates pre-voting as well, so a voting-only node with
   nothing to propagate never bumps the term.
2. **Interceptor.** On a voting-only node, publish requests (`publish_state`,
   `publish_remote_state`) are delivered normally, so the targets accept the state. Every
   successful response, including the node's response to itself, is then turned into a failure,
   and `commit_state` is never sent. The publication cannot reach a quorum, the node steps down,
   and nothing is committed. Turning a response into a failure is indistinguishable from losing it
   on the network, so Zen2 safety is unchanged.

#### Edge case: the pseudo-election

As discussed publicly in elastic/elasticsearch#80331, a voting-only node sometimes **must** win an
election. This happens when it is the only node holding the latest cluster state, for example when
the leader dies while the other full node is still catching up. Full nodes cannot win, because the
voting-only node refuses to vote for a staler candidate, which is the core rule.

In that case the voting-only node wins a short-lived election, publishes, and the full nodes accept
the newer state. The publication fails on purpose and the node steps down. A full node, now up to
date, wins the next round. The cost is one extra election round. The voting-only node logs one INFO
line per such term, saying the election was only used to propagate cluster state version V. The
failure carries a message stating that it is intended. Core still logs its usual WARN for a failed
publication; changing that is out of scope here.

#### Tests

- Unit tests for the role, the strategy (including monotonicity and the bootstrap case), the
  interceptor, the startup checks and the HA warning.
- Deterministic coordination tests (`AbstractCoordinatorTestCase`):
  - 2 + 1 elections and loss of each node;
  - every partition, including one-way partitions;
  - stale-state recovery;
  - rolling and random restarts;
  - several voting-only nodes and random disruptions.

  Every node asserts on the receiving side that it never gets a commit from a voting-only node.
- `InternalTestCluster` tests:
  - 2 + 1 fail-over of either full node;
  - rolling restarts;
  - restart of the voting-only node;
  - startup validation;
  - repository verification;
  - the HA warning.
- Outside this PR: a Docker Compose 2 + 1 rolling-restart scenario on the official image, in the
  prototype repository https://github.com/Vinz2168/opensearch-voting-only.

#### Clean-room note

This code was written without opening, fetching, searching or paraphrasing Elasticsearch X-Pack
sources or any code under the Elastic License, SSPL or AGPL. The only inputs were OpenSearch source
code (Apache 2.0), public behavioural documentation, the text of public issues (notably
elastic/elasticsearch#80331), and general knowledge of the Zen2 coordination algorithm. See
`DESIGN.md` in the prototype repository.

### Related Issues
Resolves #NNNN

Related to the forum requests:
- https://forum.opensearch.org/t/how-to-make-a-voting-only-server/16348
- https://forum.opensearch.org/t/voting-only-master-cluster-manager/19248

### Check List
- [x] Functionality includes testing.
- [ ] API changes companion pull request [created](https://github.com/opensearch-project/opensearch-api-specification/blob/main/DEVELOPER_GUIDE.md), if applicable.
  <!-- Not applicable: no REST API changes. -->
- [ ] Public documentation issue/PR [created](https://github.com/opensearch-project/documentation-website/issues/new/choose), if applicable.
  <!-- Draft in upstream/DOCS.md. Tick this once the documentation-website issue exists. -->

By submitting this pull request, I confirm that my contribution is made under the terms of the Apache 2.0 license.
For more information on following Developer Certificate of Origin and signing off your commits, please check [here](https://github.com/opensearch-project/OpenSearch/blob/main/CONTRIBUTING.md#developer-certificate-of-origin).
