<!-- Draft issue for opensearch-project/documentation-website.
     Open it only after the OpenSearch PR is accepted, and link it from the PR checklist.
     Replace #NNNN with the OpenSearch PR number and X.Y with the first release that includes it. -->

# [DOC] Document the `voting_only` cluster-manager node role

**What do you want to do?**

- [x] Add new documentation

**Tell us about your request**

OpenSearch X.Y adds a `voting_only` node role (opensearch-project/OpenSearch#NNNN). The role is
provided by the `voting-only` plugin. It needs a short section where node roles and cluster
formation are documented (the node types table in *Creating a cluster*, and the
`cluster.election.strategy` setting in the cluster settings reference). Proposed text:

---

### Voting-only cluster manager nodes

A voting-only node is a cluster-manager-eligible node that **votes in cluster manager elections
and stores the cluster state, but never becomes the elected cluster manager**. Use it as a
lightweight tiebreaker, for example a small VM in a third location next to two full
cluster-manager nodes (a "2 + 1" topology). If either full node fails, the tiebreaker keeps a
majority available, and the surviving full node becomes cluster manager.

Install the `voting-only` plugin on **every cluster-manager-eligible node**, then configure the
voting-only node:

```yaml
node.roles: [ cluster_manager, voting_only ]
cluster.election.strategy: voting_only
```

Configure every other cluster-manager-eligible node:

```yaml
node.roles: [ cluster_manager, data ]
cluster.election.strategy: voting_only
```

Keep the following in mind:

- `voting_only` must be combined with `cluster_manager` (or the deprecated `master`). On its own,
  the node fails to start.
- `cluster.election.strategy` is a static setting. It must be set to `voting_only` on all
  cluster-manager-eligible nodes.
- `_cat/nodes` shows the role as `v`, for example `mv` for a dedicated voting-only node.
- **High availability requires at least two cluster-manager-eligible nodes that are not
  voting-only.** With only one, losing it leaves no node that can become cluster manager. The
  elected cluster manager logs a warning when the voting configuration violates this.
- Dedicated voting-only nodes are skipped by snapshot repository verification.
- Sizing: a voting-only node does not perform the cluster manager's work, so it needs less heap
  and CPU than a full cluster manager node. It still receives and stores every cluster state
  update, though, so disk speed and network latency to the other cluster-manager-eligible nodes
  still affect how fast cluster state updates are committed.
- In a rare case, the voting-only node is the only node holding the latest cluster state, for
  example during a fast rolling restart. It then wins a short election only to hand that state
  to a full node, and steps down at once. The logs show an INFO message from the voting-only node
  explaining this, together with a warning about a failed publication. Both are expected.

#### Settings

| Setting | Description |
|---|---|
| `cluster.election.strategy` | Static. Set to `voting_only` on all cluster-manager-eligible nodes to enable voting-only nodes. Default is `default`. |

---

**Version:** X.Y and later.

**What other resources are available?**

- OpenSearch PR: opensearch-project/OpenSearch#NNNN
- Feature request: opensearch-project/OpenSearch#NNNN
- Prototype, design document and test results: https://github.com/Vinz2168/opensearch-voting-only
