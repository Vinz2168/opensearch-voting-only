# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.

ARG OPENSEARCH_VERSION=3.9.0
FROM opensearchproject/opensearch:${OPENSEARCH_VERSION}

COPY --chown=opensearch:opensearch plugin.zip /tmp/voting-only.zip
RUN /usr/share/opensearch/bin/opensearch-plugin install --batch file:///tmp/voting-only.zip \
    && rm /tmp/voting-only.zip
