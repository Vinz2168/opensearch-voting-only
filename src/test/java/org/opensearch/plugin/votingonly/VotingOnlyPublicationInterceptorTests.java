/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.votingonly;

import org.opensearch.cluster.coordination.PublicationTransportHandler;
import org.opensearch.cluster.coordination.PublishResponse;
import org.opensearch.cluster.coordination.PublishWithJoinResponse;
import org.opensearch.core.common.io.stream.StreamInput;
import org.opensearch.core.transport.TransportResponse;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.Transport;
import org.opensearch.transport.TransportException;
import org.opensearch.transport.TransportInterceptor;
import org.opensearch.transport.TransportRequest;
import org.opensearch.transport.TransportRequestOptions;
import org.opensearch.transport.TransportResponseHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;

public class VotingOnlyPublicationInterceptorTests extends OpenSearchTestCase {

    private static class RecordingSender implements TransportInterceptor.AsyncSender {
        final List<String> actions = new ArrayList<>();
        final List<TransportResponseHandler<?>> handlers = new ArrayList<>();

        @Override
        public <T extends TransportResponse> void sendRequest(
            Transport.Connection connection,
            String action,
            TransportRequest request,
            TransportRequestOptions options,
            TransportResponseHandler<T> handler
        ) {
            actions.add(action);
            handlers.add(handler);
        }
    }

    private static class RecordingHandler<T extends TransportResponse> implements TransportResponseHandler<T> {
        final AtomicReference<T> response = new AtomicReference<>();
        final AtomicReference<TransportException> exception = new AtomicReference<>();

        @Override
        public void handleResponse(T response) {
            assertTrue(this.response.compareAndSet(null, response));
        }

        @Override
        public void handleException(TransportException exp) {
            assertTrue(exception.compareAndSet(null, exp));
        }

        @Override
        public String executor() {
            return ThreadPool.Names.GENERIC;
        }

        @Override
        public T read(StreamInput in) {
            throw new UnsupportedOperationException();
        }
    }

    private final VotingOnlyPublicationInterceptor interceptor = new VotingOnlyPublicationInterceptor("vo");
    private final RecordingSender recordingSender = new RecordingSender();
    private final TransportInterceptor.AsyncSender sender = interceptor.interceptSender(recordingSender);

    @SuppressWarnings("unchecked")
    private static <T extends TransportResponse> TransportResponseHandler<T> lastHandler(RecordingSender recordingSender) {
        return (TransportResponseHandler<T>) recordingSender.handlers.get(recordingSender.handlers.size() - 1);
    }

    public void testSuccessfulPublishResponseIsTurnedIntoFailure() {
        final String action = randomFrom(
            PublicationTransportHandler.PUBLISH_STATE_ACTION_NAME,
            PublicationTransportHandler.PUBLISH_REMOTE_STATE_ACTION_NAME
        );
        final RecordingHandler<PublishWithJoinResponse> handler = new RecordingHandler<>();
        sender.sendRequest(null, action, TransportRequest.Empty.INSTANCE, TransportRequestOptions.EMPTY, handler);

        // the request itself is sent, so that the target accepts the state
        assertEquals(List.of(action), recordingSender.actions);

        final TransportResponseHandler<PublishWithJoinResponse> wrapped = lastHandler(recordingSender);
        wrapped.handleResponse(new PublishWithJoinResponse(new PublishResponse(7, 42), Optional.empty()));

        assertNull(handler.response.get());
        final TransportException exception = handler.exception.get();
        assertNotNull(exception);
        assertThat(exception.getRootCause(), instanceOf(VotingOnlyNodePublicationException.class));
        assertThat(exception.getRootCause().getMessage(), containsString("term [7]"));
        assertThat(exception.getRootCause().getMessage(), containsString("version [42]"));
        assertEquals(1, interceptor.getFailedPublishResponses());
    }

    public void testPublishFailureIsPassedThrough() {
        final RecordingHandler<PublishWithJoinResponse> handler = new RecordingHandler<>();
        sender.sendRequest(
            null,
            PublicationTransportHandler.PUBLISH_STATE_ACTION_NAME,
            TransportRequest.Empty.INSTANCE,
            TransportRequestOptions.EMPTY,
            handler
        );
        final TransportException original = new TransportException("boom");
        lastHandler(recordingSender).handleException(original);
        assertThat(handler.exception.get(), sameInstance(original));
        assertEquals(0, interceptor.getFailedPublishResponses());
    }

    public void testCommitIsNeverSent() {
        final RecordingHandler<TransportResponse.Empty> handler = new RecordingHandler<>();
        sender.sendRequest(
            null,
            PublicationTransportHandler.COMMIT_STATE_ACTION_NAME,
            TransportRequest.Empty.INSTANCE,
            TransportRequestOptions.EMPTY,
            handler
        );
        assertTrue(recordingSender.actions.isEmpty());
        assertNull(handler.response.get());
        assertThat(handler.exception.get().getRootCause(), instanceOf(VotingOnlyNodePublicationException.class));
        assertEquals(1, interceptor.getSuppressedCommits());
    }

    public void testOtherActionsAreUntouched() {
        final String action = randomFrom(
            "internal:cluster/coordination/join",
            "internal:cluster/request_pre_vote",
            "internal:coordination/fault_detection/leader_check",
            "indices:data/read/search"
        );
        final RecordingHandler<TransportResponse.Empty> handler = new RecordingHandler<>();
        sender.sendRequest(null, action, TransportRequest.Empty.INSTANCE, TransportRequestOptions.EMPTY, handler);
        assertEquals(List.of(action), recordingSender.actions);
        assertThat(recordingSender.handlers.get(0), sameInstance(handler));
    }
}
