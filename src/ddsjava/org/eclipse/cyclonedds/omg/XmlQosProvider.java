/*
 * Copyright(c) 2026 Sven Trittler
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
 * v. 1.0 which is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause
*/

package org.eclipse.cyclonedds.omg;

import org.eclipse.cyclonedds.internal.Native;
import org.omg.dds.core.*;
import org.omg.dds.domain.*;
import org.omg.dds.pub.*;
import org.omg.dds.sub.*;
import org.omg.dds.topic.*;

/** Immutable Java views of XML profiles parsed by Cyclone's native QoS provider. */
final class XmlQosProvider extends QosProvider {
    private final ServiceEnvironment env;
    private final String uri, profile;
    XmlQosProvider(ServiceEnvironment env, String uri, String profile) {
        this.env = env;
        java.util.Objects.requireNonNull(uri);
        String text = xmlText(uri);
        if (!text.startsWith("<")) {
            try {
                var path = uri.startsWith("file:") ? java.nio.file.Path.of(java.net.URI.create(uri)) : java.nio.file.Path.of(uri);
                text = xmlText(java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException error) { throw new IllegalArgumentException("Cannot read XML QoS profile: " + uri, error); }
            if (!text.startsWith("<")) throw new IllegalArgumentException("Expected an XML QoS document");
        }
        this.uri = text;
        this.profile = profile == null ? "" : profile;
    }
    private static String xmlText(String text) {
        String result = text.stripLeading();
        return result.startsWith("\ufeff") ? result.substring(1).stripLeading() : result;
    }
    public ServiceEnvironment getEnvironment() { return env; }
    private <T extends EntityQos<?>> T get(Class<T> api, int kind, String id) {
        String key = id == null || id.isEmpty() ? profile
            : id.contains("::") || profile.isEmpty() ? id : profile + "::" + id;
        if (key.isEmpty()) throw new IllegalArgumentException("An XML QoS library/profile key is required");
        try { return Discovery.qos(env, api, Native.profileQos(uri, key, kind)); }
        catch (org.eclipse.cyclonedds.core.DdsException error) {
            Throwable mapped = Errors.map(env, error);
            if (mapped instanceof RuntimeException runtime) throw runtime;
            throw new Errors.Failure(env, error);
        }
    }
    public DomainParticipantQos getDomainParticipantQos() { return getDomainParticipantQos(null); }
    public DomainParticipantQos getDomainParticipantQos(String id) { return get(DomainParticipantQos.class, 0, id); }
    public PublisherQos getPublisherQos() { return getPublisherQos(null); }
    public PublisherQos getPublisherQos(String id) { return get(PublisherQos.class, 1, id); }
    public SubscriberQos getSubscriberQos() { return getSubscriberQos(null); }
    public SubscriberQos getSubscriberQos(String id) { return get(SubscriberQos.class, 2, id); }
    public TopicQos getTopicQos() { return getTopicQos(null); }
    public TopicQos getTopicQos(String id) { return get(TopicQos.class, 3, id); }
    public DataReaderQos getDataReaderQos() { return getDataReaderQos(null); }
    public DataReaderQos getDataReaderQos(String id) { return get(DataReaderQos.class, 4, id); }
    public DataWriterQos getDataWriterQos() { return getDataWriterQos(null); }
    public DataWriterQos getDataWriterQos(String id) { return get(DataWriterQos.class, 5, id); }
    public DomainParticipantFactoryQos getDomainParticipantFactoryQos() { return getDomainParticipantFactoryQos(null); }
    public DomainParticipantFactoryQos getDomainParticipantFactoryQos(String id) {
        throw Policies.unsupported("Participant factory profiles have no native QoS-provider entity kind");
    }
}
