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

import HelloWorld.Message;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.eclipse.cyclonedds.core.policy.*;
import org.eclipse.cyclonedds.internal.Native;
import org.omg.dds.core.*;
import org.omg.dds.core.policy.*;
import org.omg.dds.core.status.*;
import org.omg.dds.domain.*;
import org.omg.dds.pub.*;
import org.omg.dds.sub.*;

/** Validate native values, independent discovery, matching and XML profile application. */
public final class QosExtensionsTest {
    private QosExtensionsTest() {}
    private interface Action { void run() throws Exception; }
    private static void rejects(Class<? extends Throwable> expected, Action action) throws Exception {
        try { action.run(); } catch (Throwable error) {
            if (expected.isInstance(error)) return;
            throw new AssertionError("Expected " + expected.getName(), error);
        }
        throw new AssertionError("Expected " + expected.getName());
    }
    private static void await(ServiceEnvironment env, Condition condition) throws Exception {
        var ws = WaitSet.newWaitSet(env);
        try { ws.attachCondition(condition); ws.waitForConditions(8, TimeUnit.SECONDS); }
        finally { ((AutoCloseable) ws).close(); }
    }
    public static void main(String[] args) throws Exception {
        var env = ServiceEnvironment.createInstance(QosExtensionsTest.class.getClassLoader());
        if (args[0].equals("xml")) xml(env); else extensions(env, args[1]);
    }
    private static void extensions(ServiceEnvironment env, String executable) throws Exception {
        var factory = DomainParticipantFactory.getInstance(env);
        var cf = CyclonePolicyFactory.getPolicyFactory(env);
        var pf = PolicyFactory.getPolicyFactory(env);
        byte[] input = {0, (byte)255, 65};
        var props = cf.Property().withValue("visible", "java 🛰", true).withValue("hidden", "private", false);
        var binary = cf.BinaryProperty().withValue("binary", input, true).withValue("empty", new byte[0], false);
        input[0] = 9; binary.getValue().get("binary").value()[0] = 10;
        assert binary.getValue().get("binary").value()[0] == 0;
        assert props.without("hidden").getValue().size() == 1 && props.getValue().size() == 2;
        rejects(UnsupportedOperationException.class, () -> props.getValue().clear());
        rejects(IllegalArgumentException.class, () -> cf.Property().withValue("bad\0key", "x", true));
        rejects(IllegalArgumentException.class, () -> cf.EntityName().withName("bad\ud800"));
        rejects(IllegalArgumentException.class, () -> cf.PsmxInstances().withNames(List.of("x", "x")));
        var participantQos = factory.getDefaultParticipantQos().withPolicies(props, binary, cf.EntityName().withName("java 🛰"));
        var snapshot = Discovery.qos(env, DomainParticipantQos.class, Native.roundTripQos(Policies.nativeQos(participantQos)));
        assert snapshot.get(Property.class).equals(props);
        assert snapshot.get(BinaryProperty.class).equals(binary);
        assert snapshot.get(EntityName.class).getName().equals("java 🛰");
        var empty = participantQos.withPolicies(props.without("hidden").without("visible"), binary.without("binary").without("empty"));
        var emptySnapshot = Discovery.qos(env, DomainParticipantQos.class, Native.roundTripQos(Policies.nativeQos(empty)));
        assert emptySnapshot.get(Property.class) == null || emptySnapshot.get(Property.class).getValue().isEmpty();
        assert emptySnapshot.get(BinaryProperty.class) == null || emptySnapshot.get(BinaryProperty.class).getValue().isEmpty();
        for (var kind : IgnoreLocal.Kind.values()) {
            var q = participantQos.withPolicy(cf.IgnoreLocal().withKind(kind));
            assert Discovery.qos(env, DomainParticipantQos.class, Native.roundTripQos(Policies.nativeQos(q))).get(IgnoreLocal.class).getKind() == kind;
        }
        var tc = cf.TypeConsistency().withAssignableTypeConsistency().withIgnoreSequenceBounds(false).withIgnoreStringBounds(false)
            .withIgnoreMemberNames(true).withPreventTypeWidening(true).withForceTypeValidation(false);
        var rq = Policies.defaults(env, DataReaderQos.class, false).withPolicies(tc, cf.PsmxInstances().withNames(List.of("one", "two")));
        var readerSnapshot = Discovery.qos(env, DataReaderQos.class, Native.roundTripQos(Policies.nativeQos(rq)));
        assert readerSnapshot.get(TypeConsistencyEnforcement.class).equals(tc);
        assert readerSnapshot.get(PsmxInstances.class).getNames().equals(List.of("one", "two"));
        var xcdr1 = pf.Representation().withValue((short)0);
        var xcdr2 = pf.Representation().withValue((short)2);
        assert xcdr1.compareTo(xcdr2) != 0;
        rejects(IllegalArgumentException.class, () -> xcdr1.requestedOfferedContract().compareTo(xcdr2));
        assert !CyclonePolicyFactory.isRepresentationCompatible(xcdr1, xcdr2);
        assert CyclonePolicyFactory.isRepresentationCompatible(xcdr1, pf.Representation().withValue((short)0, (short)2));
        Process peer = null;
        try (var participant = factory.createParticipant(108, participantQos, null, null);
             var topic = participant.createTopic("QosExtensions", Message.class);
             var reply = participant.createTopic("QosExtensionsReply", Message.class);
             var pub = participant.createPublisher(participant.getDefaultPublisherQos().withPolicies(
                 pf.Partition().withName(List.of("telemetry.eu")), cf.EntityName().withName("publisher")), null, null);
             var sub = participant.createSubscriber(participant.getDefaultSubscriberQos().withPolicy(
                 pf.Partition().withName(List.of("telemetry.eu"))), null, null);
             var writer = pub.createDataWriter(topic, pub.getDefaultDataWriterQos().withPolicies(props, binary,
                 cf.EntityName().withName("writer 🛰"), cf.IgnoreLocal().withKind(IgnoreLocal.Kind.PARTICIPANT),
                 cf.WriterBatching().withEnabled(true), cf.PsmxInstances().withNames(List.of())));
             var ownReader = sub.createDataReader(topic);
             var reader = sub.createDataReader(reply, sub.getDefaultDataReaderQos().withPolicy(pf.Reliability().withReliable()))) {
            assert CycloneEntities.getNativeQos(participant).get(Property.class).equals(props);
            assert CycloneEntities.getNativeQos(participant).get(BinaryProperty.class).equals(binary);
            assert CycloneEntities.getNativePolicy(pub, EntityName.class).getName().equals("publisher");
            rejects(IllegalArgumentException.class, () -> pub.createDataWriter(topic, pub.getDefaultDataWriterQos()
                .withPolicy(cf.PsmxInstances().withNames(List.of("unconfigured-instance")))));
            try (var configuredReader = sub.createDataReader(topic, sub.getDefaultDataReaderQos().withPolicy(tc))) {
                assert CycloneEntities.getNativePolicy(configuredReader, TypeConsistencyEnforcement.class).equals(tc);
            }
            var nativeWriter = CycloneEntities.getNativeQos(writer);
            assert WriterBatching.class.cast(nativeWriter.get(WriterBatching.class)).isEnabled();
            assert IgnoreLocal.class.cast(nativeWriter.get(IgnoreLocal.class)).getKind() == IgnoreLocal.Kind.PARTICIPANT;
            assert PsmxInstances.class.cast(nativeWriter.get(PsmxInstances.class)).getNames().isEmpty();
            peer = new ProcessBuilder(executable).inheritIO().start();
            var match = writer.getStatusCondition(); match.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
            if (writer.getPublicationMatchedStatus().getCurrentCount() == 0) await(env, match);
            assert writer.getPublicationMatchedStatus().getCurrentCount() == 1;
            assert ownReader.getMatchedPublications().isEmpty();
            try (var ready = reader.select().getCondition()) {
                writer.write(new Message(1, "ping"));
                CycloneEntities.flush(writer);
                await(env, ready);
                var samples = reader.select().take(null);
                assert samples.size() == 1 && samples.get(0).getData().get_text().equals("extensions verified");
            }
            var remote = reader.getMatchedPublicationData(reader.getMatchedPublications().iterator().next());
            assert CyclonePolicyFactory.getDiscoveredPolicy(remote, Property.class).getValue().get("peer").value().equals("native");
            assert CyclonePolicyFactory.getDiscoveredPolicy(remote, EntityName.class).getName().equals("native writer");
            var cloned = remote.clone();
            assert CyclonePolicyFactory.getDiscoveredPolicy(cloned, Property.class).equals(CyclonePolicyFactory.getDiscoveredPolicy(remote, Property.class));
            writer.write(new Message(3, "done")); CycloneEntities.flush(writer);
            assert peer.waitFor(5, TimeUnit.SECONDS) && peer.exitValue() == 0;
            var before = participant.getQos();
            rejects(ImmutablePolicyException.class, () -> participant.setQos(before.withPolicy(props.without("hidden"))));
            assert participant.getQos().equals(before);
            rejects(IllegalArgumentException.class, () -> participant.getDefaultPublisherQos().withPolicy(null));
        } finally { if (peer != null && peer.isAlive()) { peer.destroyForcibly(); peer.waitFor(); } }
        System.out.println("Extension policies: native round-trip, UTF-8/binary discovery, private propagation, partitions, IgnoreLocal, batching/flush and PSMX selection passed");
    }
    private static void xml(ServiceEnvironment env) throws Exception {
        if (!Native.hasQosProvider()) { System.out.println("XML QoS provider disabled in this core build"); return; }
        String xml = """
            <dds><qos_library name="Audit">
              <qos_profile name="base">
                <domain_participant_qos><user_data><value>AQID</value></user_data></domain_participant_qos>
                <publisher_qos><partition><name><element>xml</element></name></partition></publisher_qos>
                <subscriber_qos><partition><name><element>xml</element></name></partition></subscriber_qos>
                <topic_qos><history><kind>KEEP_LAST_HISTORY_QOS</kind><depth>3</depth></history></topic_qos>
                <datareader_qos><reliability><kind>RELIABLE_RELIABILITY_QOS</kind></reliability></datareader_qos>
                <datawriter_qos><reliability><kind>RELIABLE_RELIABILITY_QOS</kind></reliability></datawriter_qos>
              </qos_profile>
              <qos_profile name="derived" base_name="Audit::base">
                <datawriter_qos name="retained"><history><kind>KEEP_LAST_HISTORY_QOS</kind><depth>7</depth></history></datawriter_qos>
              </qos_profile>
              <qos_profile name="xml"><datawriter_qos><data_representation><id><element>XML</element></id></data_representation></datawriter_qos></qos_profile>
            </qos_library></dds>
            """;
        var provider = QosProvider.newQosProvider(xml, "Audit::base", env);
        assert Arrays.equals(provider.getDomainParticipantQos().getUserData().getValue(), new byte[]{1,2,3});
        assert provider.getPublisherQos().getPartition().getName().equals(Set.of("xml"));
        assert provider.getSubscriberQos().getPartition().getName().equals(Set.of("xml"));
        assert provider.getTopicQos().getHistory().getDepth() == 3;
        assert provider.getDataReaderQos().get(Reliability.class).getKind() == Reliability.Kind.RELIABLE;
        assert provider.getDataWriterQos().getReliability().getKind() == Reliability.Kind.RELIABLE;
        var unsupported = provider.getDataWriterQos("Audit::xml");
        assert unsupported.getRepresentation().getValue().equals(List.of((short)1));
        var inherited = provider.getDataWriterQos("Audit::derived::retained");
        assert QosProvider.newQosProvider(xml, "Audit::derived", env).getDataWriterQos("retained").equals(inherited);
        assert inherited.getHistory().getDepth() == 7;
        var path = Files.createTempFile("Cyclone QoS 🛰 ", ".xml");
        try {
            Files.writeString(path, "\ufeff" + xml);
            var fileProvider = QosProvider.newQosProvider(path.toString(), "Audit::base", env);
            assert fileProvider.getPublisherQos().equals(provider.getPublisherQos());
            var uriProvider = QosProvider.newQosProvider(path.toUri().toString(), "Audit::base", env);
            assert uriProvider.getTopicQos().equals(provider.getTopicQos());
        } finally { Files.deleteIfExists(path); }
        rejects(RuntimeException.class, () -> provider.getDataWriterQos("Audit::missing"));
        rejects(RuntimeException.class, () -> QosProvider.newQosProvider("<dds>broken", "Audit::base", env).getDataWriterQos());
        rejects(UnsupportedOperationException.class, provider::getDomainParticipantFactoryQos);
        var factory = DomainParticipantFactory.getInstance(env);
        try (var p = factory.createParticipant(109, provider.getDomainParticipantQos(), null, null);
             var topic = p.createTopic("XmlQos", Message.class, provider.getTopicQos(), null, null);
             var pub = p.createPublisher(provider.getPublisherQos(), null, null);
             var sub = p.createSubscriber(provider.getSubscriberQos(), null, null);
             var writer = pub.createDataWriter(topic, inherited);
             var reader = sub.createDataReader(topic, provider.getDataReaderQos())) {
            rejects(IllegalArgumentException.class, () -> pub.createDataWriter(topic, unsupported));
            assert CycloneEntities.getNativePolicy(writer, History.class).getDepth() == 7;
            var status = writer.getStatusCondition(); status.setEnabledStatuses(Set.of(PublicationMatchedStatus.class));
            if (writer.getPublicationMatchedStatus().getCurrentCount() == 0) await(env, status);
            try (var ready = reader.select().getCondition()) {
                writer.write(new Message(7, "xml")); await(env, ready);
                assert reader.select().take(null).get(0).getData().get_id() == 7;
            }
        }
        System.out.println("XML QoS: six entity kinds, profile inheritance, named selection, UTF-8 file paths, errors and native application passed");
    }
}
