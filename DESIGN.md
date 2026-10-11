# opensearch-voting-only: design

Status: draft for review (step 1 of the work plan). No code has been written yet.

Target: OpenSearch 3.9.0 (latest 3.x release, tag `3.9.0`), JDK 21.
Every extension point below was re-verified against that tag.

## 0. Clean-room statement

This plugin is a clean-room implementation. While designing and writing it, the authors
did **not** open, fetch, search or paraphrase any Elasticsearch X-Pack source code
(in particular `x-pack/plugin/voting-only-node`), nor any code under the Elastic License,
SSPL or AGPL. The only inputs were:

- OpenSearch source code (Apache 2.0), tag `3.9.0`;
- public documentation describing the *behaviour* of a voting-only node (for example the
  `v` abbreviation shown by `_cat/nodes`);
- the text of public issues, notably elastic/elasticsearch#80331;
- general knowledge of the Zen2 coordination algorithm as implemented in OpenSearch's
  `org.opensearch.cluster.coordination` package.

All design reasoning below is derived from the OpenSearch coordination code.

## 1. Names and configuration

| Item | Value |
|---|---|
| Repository | `opensearch-voting-only` |
| Plugin class | `org.opensearch.plugin.votingonly.VotingOnlyNodePlugin` |
| Role name | `voting_only` (exact, so that `RepositoriesService#isDedicatedVotingOnlyNode` matches) |
| Role abbreviation | `v` (free in 3.9.0: built-ins use `d i m r w s`) |
| Election strategy name | `voting_only` (value of `cluster.election.strategy`) |

```yaml
# voting-only (tiebreaker) node
node.roles: [ cluster_manager, voting_only ]
cluster.election.strategy: voting_only

# every other cluster-manager-eligible node
node.roles: [ cluster_manager, ... ]
cluster.election.strategy: voting_only
```

The plugin must be installed on **every cluster-manager-eligible node**, and
`cluster.election.strategy: voting_only` must be set on all of them (see I7 and §6).
Data-only / coordinating-only nodes do not need the plugin. A node that does not have
it deserializes the role as an OpenSearch `DynamicRole` with the same name, which is
harmless.

## 2. Extension points used (verified on 3.9.0)

| Need | Hook | Notes |
|---|---|---|
| Register the role | `Plugin#getRoles()` → `DiscoveryNodeRole` subclass | Protected constructor `DiscoveryNodeRole(String, String)`; `validateRole(List<DiscoveryNodeRole>)` is called from `NodeRoleSettings` when `node.roles` is parsed. |
| Election strategy | `DiscoveryPlugin#getElectionStrategies()` → `Map<String, ElectionStrategy>` | `DiscoveryModule` rejects duplicate names and unknown values of `cluster.election.strategy`. |
| Quorum rule | `ElectionStrategy#satisfiesAdditionalQuorumConstraints(...)` | `isElectionQuorum` = base quorum on committed **and** accepted config **and** this hook. |
| Fail publications | `NetworkPlugin#getTransportInterceptors(NamedWriteableRegistry, ThreadContext)` | `TransportService` wraps `sendRequestInternal` with the interceptor; this path also covers requests the node sends to itself. |
| Repository verification skip | `RepositoriesService#isDedicatedVotingOnlyNode` | Matches on role name `voting_only`, so it works without changes. |

`isElectionQuorum` is called from five places, and the rule must behave correctly in all of them:

1. `CoordinationState#handleJoin`, during a real election. It receives real `Join`s,
   which carry the joiner's last accepted term and version. Monotonicity is asserted
   there: an election cannot go from won to not won.
2. `PreVoteCollector#handlePreVoteResponse` builds synthetic `Join`s from pre-vote
   responses, which carry the responder's last accepted term and version. Responses
   fresher than the local node are dropped before this point.
3. `Coordinator.CoordinatorPeerFinder#onFoundPeersUpdated` adds votes with `addVote`
   only, so **there are no `Join`s**. It decides whether to start the election scheduler.
4. `Coordinator` abdication, when the leader leaves the voting configuration. It
   evaluates a *remote* candidate with synthetic joins that all have the leader's
   current state.
5. `ClusterFormationFailureHelper` only uses it for log messages.

## 3. The role (I6)

`VotingOnlyNodeRole extends DiscoveryNodeRole("voting_only", "v")`, `canContainData = false`,
not enabled by default.

`validateRole(roles)` throws `IllegalArgumentException` unless `roles` contains
`cluster_manager` or the legacy `master`. The message is:

> `node role [voting_only] requires the [cluster_manager] role (or the legacy [master] role) to be set; voting_only alone is not a valid configuration`

Combining it with `data`, `ingest` and other roles is allowed. In that case the node is
voting-only but not *dedicated*, so `RepositoriesService` still verifies repositories
on it, which is the existing core semantics.

There is an extra startup check in the plugin constructor, which receives the node
`Settings`. If the local `node.roles` contains `voting_only` and
`cluster.election.strategy` is not `voting_only`, startup fails with a clear message.
Without this check, a misconfigured voting-only node would still never commit (the
interceptor guarantees I1), but it could keep winning pointless elections and hurt
liveness.

## 4. The quorum rule (ElectionStrategy `voting_only`)

Notation: `L` is the `localNode` argument, meaning the node whose candidacy is being
evaluated. `A_L = (localAcceptedTerm, localAcceptedVersion)`. A node is **full** if it
is cluster-manager-eligible and has no role named `voting_only`. Freshness is ordered
lexicographically on (term, version).

```
satisfiesAdditionalQuorumConstraints(L, ..., joinVotes):
    if L is not voting_only:
        return true                                  # full candidates: unchanged behaviour
    for each voter N in joinVotes.nodes():
        if N is full:
            if localAcceptedTerm == 0:                   # bootstrap: nothing ever published by a leader
                return true
            J = the join in joinVotes.getJoins() whose source is N (may be absent)
            if J is absent:                          # call site 3: no join information
                return true
            if (J.lastAcceptedTerm, J.lastAcceptedVersion) < A_L:
                return true                          # N is strictly staler than L
    return false
```

In words: **a voting-only node may win an election only if at least one full
cluster-manager node that voted for it holds a strictly older accepted state.** The
only reason such an election is allowed is to push that newer state to full nodes (I5).

**Bootstrap exception** (added during implementation, found by the coordination
tests). A node that has never accepted a state published by a leader has
`localAcceptedTerm == 0`, so it holds at most the bootstrap configuration. If the
initial configuration contains only voting-only nodes, those are the only bootstrapped
nodes. Un-bootstrapped full nodes cannot be candidates, and their pre-vote/join
reports `(0, 0)`, the same as the voting-only node's, so the strict rule above would
never let anyone win and the cluster would never form. In that state a single full
voter is therefore enough. The voting-only node wins once, publishes the
configuration (accepted at a term ≥ 1, after which the exception no longer applies),
fails the commit, and a full node wins next. The cost is at most one extra election
round at cluster formation. The condition depends only on local values and on the
presence of a full voter, so it stays monotone.

Properties:

- **Monotone.** Adding votes can only turn `false` into `true`, never the reverse. This
  satisfies the `prevElectionWon ⇒ electionWon` assertion in `CoordinationState#handleJoin`.
  A stricter rule such as "*every* full voter is staler" would not be monotone and was
  rejected for that reason.
- **Only restricts.** It is ANDed with the base majority check on both configurations,
  so it can never let a non-majority win (I3).
- **Call site 3** (no joins): true if and only if at least one full cluster-manager peer
  has been discovered. A group made only of voting-only nodes does not even start its
  election scheduler.
- **Call site 2** (pre-vote): a voting-only node with nothing to push does not start an
  election, so it does not bump terms or disturb full candidates. This is the main
  liveness benefit.
- **Call site 4** (abdication): all synthetic joins have the same state as the
  candidate, so a voting-only node is never chosen as abdication target.

## 5. Transport interceptor (I1, I5)

The interceptor is installed **only on nodes whose own `node.roles` contains
`voting_only`**. On every other node `getTransportInterceptors` returns an empty list.

It wraps `AsyncSender#sendRequest`. Only a leader sends the following actions, so
"I am a voting-only leader" is equivalent to "this voting-only node is sending one of
them":

| Action | Behaviour |
|---|---|
| `internal:cluster/coordination/publish_state` | Request is sent normally, so the target receives and **accepts** the state. The response handler is wrapped: `handleResponse` is replaced with `handleException(new TransportException(new VotingOnlyNodePublicationException(...)))`. Real exceptions pass through unchanged. |
| `internal:cluster/coordination/publish_remote_state` | Same as above (remote-state publication path). |
| `internal:cluster/coordination/commit_state` | Defence in depth, should be unreachable: the request is **not sent** and the handler immediately gets `handleException`. |
| everything else | Untouched. |

Why this gives the intended effect:

- Each `PublicationTarget` is marked failed when its response fails.
  `Publication#onPossibleCommitFailure` sees that the non-failed targets no longer form a
  publish quorum and fails the publication with `FailedToCommitClusterStateException`.
  `Coordinator` then calls `becomeCandidate`. This all happens as soon as the responses
  come back; it does not wait for `cluster.publish.timeout`.
- The publish request to the node itself also goes through the intercepted sender
  (`PublicationTransportHandler#sendPublishRequest` → `sendClusterState` →
  `transportService.sendRequest`), so the leader's own response is failed as well.
  Even a voting configuration of `{V}` can therefore never commit.
- The targets **have accepted** the new state (it is persisted as last-accepted). That is
  exactly what I5 needs: after the failed publication, the full nodes are as fresh as
  the voting-only node, the quorum rule stops it from winning again, and a full node
  wins the next election.
- Turning a successful response into a failure is indistinguishable from losing the
  response on the network, and Zen2 already tolerates that. Safety is unaffected (I3).

To keep the #80331 "confusing logs" problem small, the exception message says plainly
that this is intended:

> `voting-only node [name] does not commit cluster state publications; this election (term T) was only used to propagate cluster state version V to full cluster-manager nodes`

The plugin also logs one INFO line per such term on the voting-only node. Core
`Coordinator`/`Publication` WARN/DEBUG lines about the failed publication cannot be
suppressed without core changes; the README documents them.

## 6. How each invariant is guaranteed

| Invariant | Mechanism | Automated test(s) (planned) |
|---|---|---|
| **I1** never stable leader | Every publish response sent by a voting-only node is failed, including its own response, and `commit_state` is never sent. A publication needs a quorum of successful responses before any commit, so nothing is ever committed. No node ever *applies* a state whose `clusterManagerNodeId` is a voting-only node, because only committed states are applied. | `VotingOnlyInterceptorTests` (unit). `VotingOnlyCoordinatorTests`: after every `stabilise()` and during `runRandomly()`, check that no node ever applied a state with a voting-only cluster manager. Integ: `_cat/nodes` never shows `*` on the voting-only node. |
| **I2** votes count | Role is cluster-manager-eligible, so `VoteCollection#addVote` and `Reconfigurator` treat it like any other node. The strategy never rejects votes, it only adds a condition on *who wins*. | `VotingOnlyElectionStrategyTests` (full candidate wins with the voting-only node's vote). Coordinator 2+1 tests: the voting-only node is in `lastCommittedConfiguration` and the cluster survives the loss of a full node. |
| **I3** safety | The rule is ANDed with the base majority checks. The interceptor only turns successes into failures, which is equivalent to message loss. | `AbstractCoordinatorTestCase` built-in safety checks (linearizability checker, single leader per term) under random disruptions with voting-only nodes. |
| **I4** 2 + 1 liveness | With either full node lost, the survivor F gets the voting-only node V's vote: V's join is accepted by F whenever F is at least as fresh as V. When F is staler, I5 applies first. | Coordinator: 2+1, kill each full node, `stabilise()`, the survivor is leader. Integ: same with `InternalTestCluster`. |
| **I5** stale state | V wins only with a strictly staler full voter. Its publication makes those voters accept V's state, then fails. They are then as fresh as V, V's rule turns false, and a full node wins. | Coordinator: partition so that only V receives the last publication, then kill the old leader. The cluster stabilises with a full leader whose state contains the last publication. Unit test on the rule: stale vs equal vs fresher joins. |
| **I6** configuration | `validateRole` at settings parse time; exact name `voting_only`. | `VotingOnlyNodeRoleTests`: rejects `[voting_only]` and `[data, voting_only]`; accepts `[cluster_manager, voting_only]` and `[master, voting_only]`; `RepositoriesService.isDedicatedVotingOnlyNode` returns true. |
| **I7** opt-in | Strategy is registered but only active when `cluster.election.strategy: voting_only`. A voting-only node without it fails startup. | `VotingOnlyNodePluginTests`: strategy is registered under `voting_only`; plugin construction fails for a voting-only node with the default strategy; no interceptor on full nodes. |

## 7. Failure scenarios considered

Notation: F1, F2 are full cluster-manager nodes, V is a voting-only node, and the voting
configuration is `{F1, F2, V}` unless stated otherwise.

1. **Normal 2+1 start.** All nodes are at an equal state, so V never passes its rule and
   F1 or F2 wins.
2. **Leader F1 dies, F2 up to date.** F2 wins with V's vote. V does not start elections,
   because its pre-vote rule is false when its state equals F2's.
3. **Leader F1 dies, F2 missed the last publication, V has it** (the forum case during
   rolling restarts). F2 cannot win because V refuses to join a staler candidate (core
   rule). V passes its rule thanks to F2's staler pre-vote, wins, publishes, and F2
   accepts. The publication fails and V steps down. F2 then wins. Expected cost: one
   extra election round plus explanatory log lines.
4. **Non-leader F2 dies.** F1 stays leader, since a quorum of `{F1, V}` keeps
   publications committing.
5. **V dies.** F1 and F2 form a quorum. V is just a voter.
6. **Partitions** `{F1}|{F2,V}`, `{F2}|{F1,V}`, `{V}|{F1,F2}`, `{F1}|{F2}|{V}`, and
   asymmetric or one-way partitions. The majority side holds a full node in every
   2-node case, and that full node becomes leader. In the 3-way split nobody wins (no
   quorum), which is correct. V alone never wins because it has no full voter.
7. **Only voting-only nodes reachable**, for example config `{V1, V2, V3}`, or
   `{F1, V1, V2}` with F1 down and no other full node. No leader is elected, by design,
   and the README warns about topologies where voting-only nodes form a majority of the
   configuration. A full node outside the configuration can still win with the
   voting-only nodes' votes.
8. **Several voting-only nodes** (for example 3 full + 2 voting-only). Same rule. A
   voting-only node may occasionally win a push election even though a full node could
   also have won. This is harmless (one extra round) and is the price of a monotone rule.
9. **V temporarily leader while clients send requests to it.** Cluster-manager tasks
   submitted during that window fail with `NotClusterManagerException`/
   `FailedToCommitClusterStateException` and are retried by
   `TransportClusterManagerNodeAction` on the next leader.
10. **Repeated restarts of any node.** Persisted state is the core `PersistedState`. The
    plugin keeps no state of its own.
11. **Mixed configuration.** The plugin is missing on a cluster-manager-eligible node,
    so that node cannot start with `cluster.election.strategy: voting_only` (core
    "Unknown election strategy"). A full node with the default strategy still behaves
    safely, but it may abdicate to V. That is harmless because V's own rule refuses the
    win. This setup is documented as unsupported.
12. **Remote cluster state publication** (`publish_remote_state`) is covered by the
    interceptor exactly like `publish_state`.

## 8. Testing approach and known risks

- **Coordination tests** extend `org.opensearch.cluster.coordination.AbstractCoordinatorTestCase`
  from `org.opensearch.test:framework:3.9.0`. In 3.9.0 it exposes exactly the overrides
  we need: `getElectionStrategy()`, `getTransportInterceptor(DiscoveryNode, ThreadPool)`
  and `createDiscoveryNode(int, boolean)`.
  *Risk:* `Cluster`/`ClusterNode` helpers are package-private, so the test classes will
  live in the same package name (`org.opensearch.cluster.coordination`) inside the
  plugin's test source set. This is to be confirmed in step 6 and reported if it fails.
  *Confirmed:* placing the tests in that package works (the server jar is not sealed).
  *Confirmed:* the stabilisation time gets an explicit allowance of two extra election
  rounds (`VOTING_ONLY_EXTRA_TIME`) for I5 scenarios.
  *Framework limitation found:* `ClusterNode#restartedNode()` always gives the restarted
  node `DiscoveryNodeRole.BUILT_IN_ROLES`, so a rebooted voting-only node silently
  becomes a full node. The tests therefore call `runRandomly(allowReboots=false, …)` and
  perform reboots with their own role-preserving `restart(...)` helper. A one-line
  upstream fix (use `localNode.getRoles()`) would remove the workaround; it is mentioned
  in `UPSTREAM_ISSUE.md`.
  *Memory:* the framework's linearizability checker holds the whole history, so the
  tests run a single `runRandomly` round per test to stay within the default test heap.
- **Integration tests**: `OpenSearchIntegTestCase` with `InternalTestCluster`, scope TEST,
  plus a Docker Compose 2+1 rolling-restart script for the manual scenario.

## 9. Out of scope

No changes to OpenSearch core or to the documentation website. Moving the feature into
core is left for the upstream discussion (`UPSTREAM_ISSUE.md`).
