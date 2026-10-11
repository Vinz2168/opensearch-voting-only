/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.votingonly;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.cluster.coordination.PublicationTransportHandler;
import org.opensearch.cluster.coordination.PublishWithJoinResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.transport.TransportResponse;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportInterceptor;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.TransportResponseHandler;
import org.opensearch.transport.stream.StreamTransportResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Installed only on voting-only nodes. Only an elected cluster manager sends publish and commit requests, so intercepting those actions
 * is the same as acting only when this voting-only node is the leader.
 * <ul>
 *     <li>Publish requests are sent normally, so that the targets accept the new cluster state, but every successful response is turned
 *     into a failure. The publication can therefore never reach a quorum and is never committed.</li>
 *     <li>Commit requests are never sent (defence in depth: they cannot be reached without a publish quorum).</li>
 * </ul>
 * Turning a response into a failure is indistinguishable from losing the response on the network, which the coordination layer already
 * tolerates, so safety is unaffected. See DESIGN.md, section 5.
 */
public class VotingOnlyPublicationInterceptor implements TransportInterceptor {

    private static final Logger logger = LogManager.getLogger(VotingOnlyPublicationInterceptor.class);

    private final String localNodeName;
    private final AtomicLong lastLoggedTerm = new AtomicLong(-1L);
    private final AtomicLong failedPublishResponses = new AtomicLong();
    private final AtomicLong suppressedCommits = new AtomicLong();

    public VotingOnlyPublicationInterceptor(String localNodeName) {
        this.localNodeName = localNodeName;
    }

    static boolean isPublishAction(String action) {
        return PublicationTransportHandler.PUBLISH_STATE_ACTION_NAME.equals(action)
            || PublicationTransportHandler.PUBLISH_REMOTE_STATE_ACTION_NAME.equals(action);
    }

    static boolean isCommitAction(String action) {
        return PublicationTransportHandler.COMMIT_STATE_ACTION_NAME.equals(action);
    }

    @Override
    public AsyncSender interceptSender(AsyncSender sender) {
        return new AsyncSender() {
            @Override
            public <T extends TransportResponse> void sendRequest(
                Transport.Connection connection,
                String action,
                TransportRequest request,
                TransportRequestOptions options,
                TransportResponseHandler<T> handler
            ) {
                if (isPublishAction(action)) {
                    sender.sendRequest(connection, action, request, options, new FailingPublishResponseHandler<>(handler));
                } else if (isCommitAction(action)) {
                    suppressedCommits.incrementAndGet();
                    handler.handleException(
                        new TransportException(
                            new VotingOnlyNodePublicationException(
                                "voting-only node [{}] does not commit cluster state publications",
                                localNodeName
                            )
                        )
                    );
                } else {
                    sender.sendRequest(connection, action, request, options, handler);
                }
            }
        };
    }

    /**
     * The number of successful publish responses that were turned into failures on this node.
     */
    public long getFailedPublishResponses() {
        return failedPublishResponses.get();
    }

    /**
     * The number of commit requests that were not sent from this node.
     */
    public long getSuppressedCommits() {
        return suppressedCommits.get();
    }

    private void logOncePerTerm(long term, long version) {
        final long previous = lastLoggedTerm.getAndAccumulate(term, Math::max);
        if (previous < term) {
            logger.info(
                "voting-only node [{}] won the election in term [{}] only to propagate cluster state version [{}] to full "
                    + "cluster-manager nodes; its publications are failed on purpose and it will not become the elected cluster manager",
                localNodeName,
                term,
                version
            );
        }
    }

    private class FailingPublishResponseHandler<T extends TransportResponse> implements TransportResponseHandler<T> {

        private final TransportResponseHandler<T> delegate;

        FailingPublishResponseHandler(TransportResponseHandler<T> delegate) {
            this.delegate = delegate;
        }

        @Override
        public T read(StreamInput in) throws IOException {
            return delegate.read(in);
        }

        @Override
        public void handleResponse(T response) {
            failedPublishResponses.incrementAndGet();
            long term = -1L;
            long version = -1L;
            if (response instanceof PublishWithJoinResponse publishWithJoinResponse) {
                term = publishWithJoinResponse.getPublishResponse().getTerm();
                version = publishWithJoinResponse.getPublishResponse().getVersion();
                logOncePerTerm(term, version);
            }
            delegate.handleException(
                new TransportException(
                    new VotingOnlyNodePublicationException(
                        "voting-only node [{}] does not commit cluster state publications; this election (term [{}]) was only used to "
                            + "propagate cluster state version [{}] to full cluster-manager nodes",
                        localNodeName,
                        term,
                        version
                    )
                )
            );
        }

        @Override
        public void handleStreamResponse(StreamTransportResponse<T> response) {
            delegate.handleStreamResponse(response);
        }

        @Override
        public void handleException(TransportException exp) {
            delegate.handleException(exp);
        }

        @Override
        public void handleRejection(Exception exp) {
            delegate.handleRejection(exp);
        }

        @Override
        public String executor() {
            return delegate.executor();
        }

        @Override
        public boolean skipsDeserialization() {
            return delegate.skipsDeserialization();
        }

        @Override
        public String toString() {
            return "VotingOnlyFailingPublishResponseHandler{" + delegate + "}";
        }
    }
}
