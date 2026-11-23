..
   Copyright(c) 2026 Sven Trittler

   This program and the accompanying materials are made available under the
   terms of the Eclipse Public License v. 2.0 which is available at
   http://www.eclipse.org/legal/epl-2.0, or the Eclipse Distribution License
   v. 1.0 which is available at
   http://www.eclipse.org/org/documents/edl-v10.php.

   SPDX-License-Identifier: EPL-2.0 OR BSD-3-Clause

Asynchronous data, predicates and listener diagnostics
=======================================================

The normative OMG interfaces remain unchanged. Additional operations are exposed
by ``org.eclipse.cyclonedds.omg.CycloneEntities``.

Wait for unread data
--------------------

.. code-block:: java

   var available = CycloneEntities.waitDataAsync(reader);
   available.thenAccept(ready -> {
       if (ready) {
           for (var sample : reader.select().take(null)) {
               if (sample.getData() != null) process(sample.getData());
           }
       }
   });
   // Unregister this particular wait without closing the reader:
   // available.cancel(false);

The result is a ``CompletableFuture<Boolean>``. It completes with true when unread
data is observed, with false when its reader or parent closes, or with cancellation
when cancelled. Already-read samples do not satisfy a new wait. Waiting does not
consume or reserve samples against other consumers. Several callers may wait on
the same reader. No thread is occupied while waiting. Pending completions run on
the CompletableFuture default executor, outside participant monitors. Cancelling
the returned future releases its registration; use cancellation rather than merely
abandoning a future. Exceptional completion, including ``orTimeout``, also removes
the registration. Existing listener registrations and ancestor DataOnReaders
listeners remain usable alongside async waits.

Callbacks and failures
----------------------

Application callbacks are serial per originating entity. Different endpoints,
including those sharing a participant, dispatch independently using shared daemon
workers. Ancestor listeners may therefore receive concurrent callbacks from
different endpoints and must synchronize shared application state.

Pending DataAvailable and DataOnReaders notifications are coalesced per dispatch
target. Drain available samples in a callback; notification count is not sample
count. Other status events retain their individual immutable snapshots. Status
queues are not bounded, so handlers must still return promptly. Closing an entity
cancels its pending deliveries; an already-running callback may finish afterward.

``CycloneEntities.getLastListenerException(reader)`` returns the first failure
observed on that source entity, or null. Failures are also sent to the callback
thread's uncaught-exception handler. A failing application handler does not stop
later notifications. Errors in a listener inherited from a subscriber or participant
are recorded on the originating endpoint.

Typed Java predicates
---------------------

.. code-block:: java

   try (var selected = CycloneEntities.createQueryCondition(
           reader, sample -> sample.get_id() == 42)) {
       waitset.attachCondition(selected);
       waitset.waitForConditions(5, TimeUnit.SECONDS);
       for (var sample : CycloneEntities.take(reader, selected, 1024)) {
           if (sample.getData() != null) process(sample.getData());
       }
       waitset.detachCondition(selected);
   }

Use ``CycloneEntities.read`` to keep matching samples in the cache. An overload
accepts a standard ``Subscriber.DataState``. The condition belongs to its reader;
using another reader is rejected. Predicates execute on native DDS threads, must
be thread-safe and short, and must never call DDS operations. Predicate failures
are retained and surfaced by ``throwIfFilterFailed``, the condition trigger getter,
and the read/take helpers. Lifecycle notifications contain keys only and bypass
the ordinary-data predicate, just as in the native query path.

These conditions share the existing process-wide limit of 64 live query callbacks
with SQL query conditions. Closing a condition releases its slot. SQL queries and
ContentFilteredTopic remain available through the normative API.

Combined write and disposal
---------------------------

``CycloneEntities.writeDispose(writer, sample)`` writes the data and disposes its
instance in one native DDS operation. A second overload accepts a nonnegative
source timestamp and ``TimeUnit``. The internal lightweight writer also exposes
``writeDispose``. Ordinary write, dispose and unregister semantics are unchanged.

Encoding buffers
----------------

Runtime writes borrow operation-local CDR buffers. The shared pool retains at most
16 buffers of at most one MiB each; larger buffers are discarded after use. Codecs
return buffers even when serialization fails. The public ``CdrWriter.pooled``
factory supports the same behavior with try-with-resources. Returned byte arrays
remain independent copies; this is not a zero-copy or application loan API.
