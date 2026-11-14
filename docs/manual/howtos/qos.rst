..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause


QoS policies
============

Applications use the official ``PolicyFactory`` and immutable QoS views. Policy
methods return new values; they do not modify an existing policy or entity.
Policies and QoS objects must belong to the entity's ``ServiceEnvironment``.

Transient-local history
-----------------------

Configure the writer's late-joiner retention separately from its ordinary history:

.. code-block:: java

   var policies = org.omg.dds.core.policy.PolicyFactory.getPolicyFactory(environment);
   var retained = policies.Durability().withTransientLocal();
   var limits = policies.ResourceLimits()
       .withMaxSamples(128).withMaxInstances(8).withMaxSamplesPerInstance(16);
   var writerQos = publisher.getDefaultDataWriterQos().withPolicies(
       retained,
       policies.Reliability().withReliable(),
       policies.History().withKeepLast(16),
       policies.DurabilityService().withHistoryDepth(16),
       limits);
   var readerQos = subscriber.getDefaultDataReaderQos().withPolicies(
       retained, policies.Reliability().withReliable(),
       policies.History().withKeepLast(16), limits);

Cyclone DDS uses ``DurabilityService`` history settings for transient-local
retention. Its default retention is one sample per instance, even if ordinary
writer history is deeper. Data remains available to late joiners while the writer
exists. This does not provide storage across writer/process restarts.
TRANSIENT/PERSISTENT modes requiring an external durability service are rejected.
The normative API spells its persistent convenience method ``withPersitent()``\ ;
that spelling is retained in the unmodified API.

Discovery is asynchronous. Match the expected writers before calling
``reader.waitForHistoricalData(...)``\ ; calling it with no known writers does not
wait for future discovery. Batches can be partial, so collect samples across
waitset wakeups until the application's expected result or deadline is reached.

Resource limits accept positive values or ``ResourceLimits.LENGTH_UNLIMITED``.
History depth must be positive. The core validates consistency between policies
when creating or updating an entity, reporting ``InconsistentPolicyException`` for
incompatible history/resource settings.

Partitions and other policies
-----------------------------

Partitions apply to native publishers/subscribers:

.. code-block:: java

   var partition = policies.Partition().withName(java.util.List.of("telemetry"));
   var qos = participant.getDefaultPublisherQos().withPolicy(partition);
   var publisher = participant.createPublisher(qos, null, null);

Publishers and subscribers must have compatible partitions to match. Empty/default
partition names represent the default partition. The policy stores an immutable
copy of its names.

The provider forwards deadline, lifespan, liveliness/lease duration,
shared/exclusive ownership, ownership strength, time-based filtering and writer
unregistration lifecycle settings to the C core. Use ``assertLiveliness()`` where
required by the selected liveliness kind. Listener callbacks and status getters
report deadline and liveliness changes, including recovery after a lost lease.

Destination order, discovery data and other policies
----------------------------------------------------

``DestinationOrder`` selects reception or source timestamp ordering. With source
ordering, an update older than the latest update of the same instance is rejected.
``UserData`` applies to participants/readers/writers; ``GroupData`` applies to
publishers/subscribers, and ``TopicData`` applies to topics. Byte-data policies copy
the selected input slice and return a new array from every getter, so neither
caller-owned input nor returned arrays can mutate a policy:

.. code-block:: java

   var user = policies.UserData().withValue(new byte[]{1, 0, (byte) 255}, 0, 3);
   var qos = publisher.getDefaultDataWriterQos().withPolicies(
       user, policies.DestinationOrder().withSourceTimestamp(),
       policies.LatencyBudget().withDuration(1, java.util.concurrent.TimeUnit.MILLISECONDS),
       policies.TransportPriority().withValue(7));

The normative ``PolicyFactory`` does not define ``TopicData()`` or
``Presentation()`` factory methods. Obtain their initial values from
``participant.getDefaultTopicQos().getTopicData()`` and
``participant.getDefaultPublisherQos().getPresentation()``. Presentation scope
values are forwarded; coherent/ordered-access profiles are rejected.
Latency budget is an advertised requested/offered hint; transport priority is
forwarded to Cyclone and its transport, without a guarantee of OS/network priority.

Type consistency and representation
-----------------------------------

The unmodified OMG Java API predates the modern two-kind XTypes policy.
``withExactTypeTypeConsistency()`` maps to ``DISALLOW_TYPE_COERCION``;
``withAssignableTypeConsistency()`` maps to ``ALLOW_TYPE_COERCION``. Validation
requires type metadata. The default assignable mode checks member names, permits bounds to
differ and allows widening. Cyclone-specific type-consistency options below can
override those defaults. The integration tests verify a newer appendable
writer schema matching an older reader in assignable mode, while the exact-type
reader rejects it and reports ``TypeConsistencyEnforcement`` in its status.
The old ``EXACT_NAME`` and ``DECLARED`` kinds have no faithful core equivalent
and are rejected when applied, rather than being silently treated as assignable.

``Representation()`` defaults to XCDR2 (ID ``2``). XCDR1 uses ID ``0``. The
original OMG API's ``Id`` class has no XCDR2 constant, so specify numeric IDs:

.. code-block:: java

   var xcdr1 = policyFactory.Representation().withValue((short) 0);
   var either = policyFactory.Representation().withValue((short) 0, (short) 2);
   var writerQos = publisher.getDefaultDataWriterQos().withPolicy(xcdr1);
   var readerQos = subscriber.getDefaultDataReaderQos().withPolicy(either);

A writer selects exactly one representation; a reader may accept both. Other
representation IDs are rejected. Schema restrictions are described in
:doc:`idl`.
``EntityFactory`` exposes the standard auto-enable setting; disabling entity
creation is rejected because disabled entities are not supported by the core.

Reader lifecycle
----------------

``ReaderDataLifecycle`` exposes both standard auto-purge delays, defaulting to
infinity, and forwards the infinite configuration to the core. The pinned core
stores finite values but its default reader cache does not implement the purge
timers. Applying finite delays therefore raises ``UnsupportedOperationException``.
The previous QoS stays intact. This is a core restriction, not a claim that finite
purging works. Ordinary disposed/no-writer state notifications remain available.

``copyFromTopicQos`` copies supported policies common to the topic and target
endpoint. Explicit endpoint QoS remains under application control. Topic QoS and
participant/container endpoint defaults support the implemented policies.

Updates
-------

``entity.setQos(newQos)`` calls the native update operation. The Java view changes
only after native success. The core can reject immutable changes with
``ImmutablePolicyException`` and unsupported updates with
``UnsupportedOperationException``. In the pinned baseline, changing an enabled
publisher's partition, or changing deadline/latency-budget matching contracts,
is unsupported; create the entity with those policies instead. Unsupported update
errors identify the changed policies. The provider does not silently recreate enabled entities.

The unmodified pinned core advertises enabled-reader time-based filter updates
without applying the new interval to its default cache. The provider therefore
rejects changes to this policy after reader creation. Configure the interval when
creating the reader, or close and recreate it. An unchanged interval is accepted.
Ownership strength, user/group/topic data, transport priority and other
core-supported mutable policies continue to use the native update path.

The integration suite tests transient-local late joining, resource/history
consistency, partitions, source/reception order, time-based filtering and live
updates, exclusive ownership and strength transfer, per-instance deadline misses,
liveliness loss/recovery, and exact/assignable schema matching. An independent C
participant verifies binary user/group/topic discovery data and enabled-entity
metadata updates. Rejected updates leave Java QoS unchanged. These tests do not
establish every DDS profile or all policy combinations.


Cyclone extension policies
--------------------------

The normative OMG API stays unchanged. Additional policies are defined in
``org.eclipse.cyclonedds.core.policy`` and created with
``org.eclipse.cyclonedds.omg.CyclonePolicyFactory``. They use the same immutable
``withPolicy`` / ``withPolicies`` operations as standard policies:

.. code-block:: java

   var cyclone = org.eclipse.cyclonedds.omg.CyclonePolicyFactory.getPolicyFactory(environment);
   var participantQos = factory.getDefaultParticipantQos().withPolicies(
       cyclone.EntityName().withName("vehicle gateway"),
       cyclone.Property().withValue("application", "telemetry", true),
       cyclone.BinaryProperty().withValue("version", new byte[]{1, 0, 2}, false));
   var participant = factory.createParticipant(0, participantQos, null, null);

``Property`` and ``BinaryProperty`` contain immutable maps of names to values
and their ``propagate`` flags. Calling ``withValue`` adds or replaces a named
entry; ``without`` returns a new policy without it. Binary input/output is copied.
Names and string values support UTF-8, including supplementary characters; NUL
and malformed surrogate sequences are rejected. ``propagate=false`` keeps the
property local. Properties apply to participants, readers and writers, matching
the native core's supported entity scopes. They are distinct from ``UserData``.
The pinned core treats properties and entity names as immutable after creation.

``EntityName`` applies to participants, topics, publishers, subscribers, readers
and writers. ``IgnoreLocal`` applies to participants, publishers, subscribers,
readers and writers; its kinds are ``NOTHING``, ``PARTICIPANT`` and ``PROCESS``.
For example, a writer configured with
``cyclone.IgnoreLocal().withKind(IgnoreLocal.Kind.PARTICIPANT)`` does not match
readers from the same participant, but can match a remote C or Java participant.

``WriterBatching`` applies to writers:

.. code-block:: java

   var qos = publisher.getDefaultDataWriterQos().withPolicy(
       cyclone.WriterBatching().withEnabled(true));
   var writer = publisher.createDataWriter(topic, qos);
   writer.write(sample);
   org.eclipse.cyclonedds.omg.CycloneEntities.flush(writer);

The flush operation forwards to ``dds_write_flush`` to send buffered RTPS
messages. Batching can improve throughput at the cost of latency.

``PsmxInstances`` applies to readers and writers. ``withNames(List.of())``
explicitly disables PSMX on an endpoint. A nonempty list selects already
configured native plugin instances. Unknown instance names are rejected by the
core when using this PSMX-capable type support. The byte-oriented type support accepts serialized PSMX data and keys and copies
them into validated CDR buffers. Raw C-layout PSMX samples are rejected. This
policy does not install a plugin or provide Java sample loans.

To inspect the effective native policy, use
``CycloneEntities.getNativePolicy(entity, Property.class)`` (or
``getNativeQos(entity)`` for the complete view). An absent policy is returned as
``null``. For built-in or matched-endpoint discovery snapshots, use
``CyclonePolicyFactory.getDiscoveredPolicy(data, Property.class)`` or
``EntityName.class``; these helpers also work on cloned snapshots.

Modern type consistency and representation matching
---------------------------------------------------

``cyclone.TypeConsistency()`` extends the standard policy with immutable
``withIgnoreSequenceBounds``, ``withIgnoreStringBounds``, ``withIgnoreMemberNames``,
``withPreventTypeWidening`` and ``withForceTypeValidation`` options. All five
flags are forwarded and retained in native/discovery snapshots. The defaults
remain true, true, false, false and true respectively.

``DataRepresentation.compareTo`` provides a deterministic lexicographic ordering
of the representation-ID lists. Disjoint sets have no DDS request/offer ordering:
``requestedOfferedContract().compareTo(...)`` rejects that case instead of
reporting incompatible XCDR1-only and XCDR2-only policies as equal. For directional
writer/reader compatibility, use
``CyclonePolicyFactory.isRepresentationCompatible(writerPolicy, readerPolicy)``.
The offered writer policy must select exactly one representation.

XML QoS profiles
-----------------

The standard ``QosProvider`` now uses Cyclone's native XML profile parser. It
accepts inline XML, a local file path or a ``file:`` URI, including UTF-8 paths.
File contents are read when the provider is constructed; returned QoS views own
copied policy values. Select profiles and optional named entity QoS as follows:

.. code-block:: java

   var profiles = org.omg.dds.core.QosProvider.newQosProvider(
       "profiles.xml", "MyLibrary::MyProfile", environment);
   var writerQos = profiles.getDataWriterQos("ReliableWriter");
   var participantQos = profiles.getDomainParticipantQos();

An ID containing ``::`` selects a fully qualified key instead of appending the
ID to the provider's default library/profile. The native parser resolves profile
inheritance. Participant, topic, publisher, subscriber, reader and writer QoS are
supported. Factory QoS has no native profile entity kind and is rejected.
The core must be built with ``DDS_HAS_QOS_PROVIDER``. Unsupported core features
in a loaded profile remain subject to the same validation when applied to an
entity; loading a profile does not emulate disabled entities, coherent access,
finite auto-purge timers or XML data serialization.

``qos-extensions`` verifies native values and independent C discovery, including
private/public properties, binary values, UTF-8 names, wildcard partitions,
IgnoreLocal matching, batching/flush and PSMX selection validation. ``qos-xml``
checks all six native entity kinds, inheritance, named profiles, file/URI loading,
errors and an actual Java exchange using profile-derived QoS.
