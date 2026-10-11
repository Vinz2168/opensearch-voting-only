# opensearch-voting-only

An OpenSearch plugin that adds a **`voting_only` node role**: a cluster-manager-eligible node that
votes in cluster-manager elections and stores the cluster state, but **never becomes the elected
cluster manager**.

The typical use is a lightweight **tiebreaker**, such as a small VM in a third site, in a
**2 + 1** topology. Two full cluster-manager nodes do the real work. The tiebreaker keeps a majority
available when either of them is lost, and it never has to carry the load of the cluster manager.

```
   site A                site B                site C (small VM)
┌───────────┐        ┌───────────┐        ┌────────────────────┐
│ os-full-1 │        │ os-full-2 │        │   os-tiebreaker    │
│ cluster_  │◄──────►│ cluster_  │◄──────►│ cluster_manager,   │
│ manager,  │        │ manager,  │        │ voting_only        │
│ data      │        │ data      │        │ (votes, never CM)  │
└───────────┘        └───────────┘        └────────────────────┘
```

OpenSearch has no voting-only role in core. This plugin adds one using only existing extension
points, with no core changes. It is a clean-room implementation (see [DESIGN.md](DESIGN.md)).

## Compatibility

| Plugin version | OpenSearch | JDK (build) |
|---|---|---|
| 3.9.0.0 | 3.9.0 | 21 |

Other 3.x releases should only need a rebuild with `-Dopensearch.version=<version>`. This has
only been tested on 3.9.0. A backport to 2.19 is expected to be small, but it has not been tried:
the interceptor delegates two experimental 3.x streaming methods of `TransportResponseHandler`
(`handleStreamResponse`, `skipsDeserialization`), which would have to be dropped if 2.19 lacks
them.

## Installation

Install the plugin on **every cluster-manager-eligible node**: the full ones and the voting-only
ones. Data-only and coordinating-only nodes do not need it.

```bash
./gradlew bundlePlugin            # with JAVA_HOME pointing to a JDK 21
bin/opensearch-plugin install file:///path/to/build/distributions/opensearch-voting-only-3.9.0.0.zip
```

## Configuration

Voting-only node (`opensearch.yml`):

```yaml
node.roles: [ cluster_manager, voting_only ]
cluster.election.strategy: voting_only
```

Every other cluster-manager-eligible node:

```yaml
node.roles: [ cluster_manager, data ]      # whatever roles it already has
cluster.election.strategy: voting_only
```

Rules:

- `voting_only` must be combined with `cluster_manager` (or the legacy `master`). Otherwise the
  node refuses to start with
  `node role [voting_only] requires the [cluster_manager] role ...`.
- The election strategy is **opt-in**. Set `cluster.election.strategy: voting_only` with the
  **same value on all cluster-manager-eligible nodes**. A node with the `voting_only` role refuses
  to start without it. A node without the plugin refuses to start with it (`Unknown election
  strategy`).
- In `_cat/nodes` the role is shown as **`v`**:

  ```
  name          node.role cluster_manager
  os-full-1     dim       *
  os-tiebreaker mv        -
  os-full-2     dim       -
  ```

- A dedicated voting-only node (`cluster_manager` + `voting_only`, no `data`) is skipped by
  snapshot repository verification. OpenSearch core already does this for any role named
  `voting_only`.

### Topology advice

- Voting-only nodes count towards the quorum like any other node. If they form a **majority** of
  the voting configuration, losing the full nodes leaves a "quorum" that can never elect a
  leader. Keep at least as many full cluster-manager nodes as voting-only ones; 2 + 1 is the
  intended layout.
- A cluster made only of voting-only nodes never elects a cluster manager.

## How it works

The plugin combines three parts. [DESIGN.md](DESIGN.md) has the full reasoning and the
invariant-to-test mapping.

1. **Role** `voting_only` (abbreviation `v`), registered through `Plugin#getRoles()`.
2. **Election strategy** `voting_only`, an `ElectionStrategy` plugged into the core quorum check.
   A full candidate is unaffected. A voting-only candidate can only win if at least one full
   cluster-manager node that voted for it has a *strictly older* cluster state. That election is
   needed when the voting-only node is the only one holding the latest state, for example when
   the full node that was leader dies while the other full node was still catching up.
3. **Transport interceptor**, active on voting-only nodes only. When a voting-only node is leader,
   its publish requests are delivered, so the full nodes accept the newer state. Every successful
   publish response is then turned into a failure, and commit requests are never sent. The
   publication cannot reach a quorum and is never committed. The voting-only node steps down,
   and a full node, now up to date, wins the next election.

Safety is untouched: every decision still needs a majority of the voting configuration. The
plugin only adds restrictions on top of the core rules, and a failed response looks the same as
one lost on the network.

### What you will see in the logs

When the voting-only node has to run such a temporary election (rare: the stale-state case
above, or once at the very first cluster formation), the logs contain lines like these:

```
[INFO ][o.o.p.v.VotingOnlyPublicationInterceptor] [os-tiebreaker] voting-only node [os-tiebreaker] won the election in term [8]
      only to propagate cluster state version [37] to full cluster-manager nodes; its publications are failed on purpose and
      it will not become the elected cluster manager
[WARN ][o.o.c.s.ClusterManagerService] [os-tiebreaker] failing [... elected-as-cluster-manager ...]
[INFO ][o.o.c.c.Coordinator      ] [os-full-1] cluster-manager node [{os-tiebreaker}...] failed, restarting discovery
```

They are expected. A few seconds later a full node is elected. The core WARN cannot be
suppressed without changing OpenSearch itself.

## Testing

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew check
```

`check` runs precommit, the unit tests, the deterministic coordination tests and the
internal-cluster integration tests.

| Suite | What it covers |
|---|---|
| `VotingOnlyNodeRoleTests`, `VotingOnlyRepositoriesServiceTests` | role name and abbreviation, validation (I6), repository check |
| `VotingOnlyElectionStrategyTests` | quorum rule, monotonicity, bootstrap case (I2, I3, I5) |
| `VotingOnlyPublicationInterceptorTests` | publish responses failed, commits never sent (I1) |
| `VotingOnlyNodePluginTests` | opt-in strategy, startup check (I6, I7) |
| `VotingOnlyCoordinatorTests` | `AbstractCoordinatorTestCase`: 2 + 1, loss of each node, all partitions, stale state, restarts, several voting-only nodes, random topologies and disruptions (I1–I5) |
| `VotingOnlyNodeIT` | real nodes: 2 + 1 election, fail-over, rolling restarts, startup validation, repository verification |

### Docker 2 + 1 rolling-restart scenario

This reproduces the forum case: a rolling restart of the two full nodes, during which the
voting-only node must never become cluster manager.

```bash
export JAVA_HOME=/path/to/jdk-21
scripts/docker-rolling-restart.sh            # --rounds N, --keep
```

The script builds the plugin and an image based on `opensearchproject/opensearch:3.9.0`
(security disabled, for testing only), then starts [`docker/docker-compose.yml`](docker/docker-compose.yml)
and runs:

- **A, rolling restart.** Each full node is stopped, the cluster continues on the other full node
  (an index is created meanwhile), and the node is restarted and rejoins.
- **B, fast rolling restart (stale state).** `os-full-1` is restarted and `os-full-2` is stopped
  before `os-full-1` has rejoined. Only the tiebreaker has the latest state, so it propagates it
  and `os-full-1` takes over *with* the index it missed.

In the background, `_cat/cluster_manager?local=true` is polled on every node twice a second, and
the run fails if any node ever reports the tiebreaker as cluster manager. Example result
(OpenSearch 3.9.0, Docker 29.7, macOS, 2 rounds):

```
12:15:56 [B1] os-full-1 is cluster manager and has index b-1
12:16:15 [B2] os-full-1 is cluster manager and has index b-2
name          node.role cluster_manager
os-full-1     dim       *
os-tiebreaker mv        -
os-full-2     dim       -
12:16:23 monitor samples: 212; tiebreaker temporary elections (I5) logged: 4
12:16:23 PASSED: the voting-only tiebreaker never became the cluster manager
```

On Linux, OpenSearch needs `sudo sysctl -w vm.max_map_count=262144` first.

## Clean-room statement

This plugin was written without opening, fetching, searching or paraphrasing Elasticsearch
X-Pack sources or any code under the Elastic License, SSPL or AGPL. See [DESIGN.md](DESIGN.md#0-clean-room-statement).

## Contributing

Commits must be signed off ([DCO](https://developercertificate.org/)): `git commit -s`.

## License

Apache License 2.0, see [LICENSE.txt](LICENSE.txt).
