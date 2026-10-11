/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.plugin.votingonly;

import org.opensearch.OpenSearchException;

/**
 * Reported to the coordination layer of a voting-only node instead of a successful publish response, so that the publication can never
 * be committed. This is intended behaviour, not an error condition.
 */
public class VotingOnlyNodePublicationException extends OpenSearchException {

    public VotingOnlyNodePublicationException(String msg, Object... args) {
        super(msg, args);
    }
}
