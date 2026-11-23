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

#include <stdio.h>
#include <string.h>
#include "dds/dds.h"
#include "HelloWorld.h"

static bool property(const dds_qos_t *qos, const char *key, const char *expected) {
  char *text = NULL; bool propagate = false;
  bool ok = dds_qget_prop_propagate(qos, key, &text, &propagate) && propagate && strcmp(text, expected) == 0;
  dds_free(text); return ok;
}
static bool metadata(const dds_qos_t *qos, const char *expected_name) {
  void *bytes = NULL; size_t size = 0; bool propagate = false; char *name = NULL, *hidden = NULL;
  bool ok = property(qos, "visible", "java 🛰") && !dds_qget_prop(qos, "hidden", &hidden)
    && dds_qget_bprop_propagate(qos, "binary", &bytes, &size, &propagate) && propagate && size == 3
    && memcmp(bytes, "\0\xff" "A", 3) == 0
    && dds_qget_entity_name(qos, &name) && strcmp(name, expected_name) == 0;
  dds_free(bytes); dds_free(name); dds_free(hidden); return ok;
}
int main(void) {
  dds_entity_t p = dds_create_participant(108, NULL, NULL);
  dds_entity_t participants = dds_create_reader(p, DDS_BUILTIN_TOPIC_DCPSPARTICIPANT, NULL, NULL);
  dds_entity_t input = dds_create_topic(p, &HelloWorld_Message_desc, "QosExtensions", NULL, NULL);
  dds_entity_t output = dds_create_topic(p, &HelloWorld_Message_desc, "QosExtensionsReply", NULL, NULL);
  dds_qos_t *qos = dds_create_qos();
  dds_qset_partition1(qos, "telemetry.*");
  dds_entity_t sub = dds_create_subscriber(p, qos, NULL), pub = dds_create_publisher(p, qos, NULL);
  dds_delete_qos(qos); qos = dds_create_qos();
  dds_qset_reliability(qos, DDS_RELIABILITY_RELIABLE, DDS_SECS(2));
  dds_data_representation_id_t representation = DDS_DATA_REPRESENTATION_XCDR2;
  dds_qset_data_representation(qos, 1, &representation);
  dds_entity_t reader = dds_create_reader(sub, input, qos, NULL);
  dds_qset_prop_propagate(qos, "peer", "native", true);
  dds_qset_entity_name(qos, "native writer");
  dds_entity_t writer = dds_create_writer(pub, output, qos, NULL);
  dds_delete_qos(qos);
  if (reader < 0 || writer < 0 || participants < 0) return 2;
  bool participant_ok = false, endpoint_ok = false, ping = false;
  dds_time_t deadline = dds_time() + DDS_SECS(12);
  while (dds_time() < deadline && !(participant_ok && endpoint_ok && ping)) {
    void *samples[8] = {0}; dds_sample_info_t info[8];
    dds_return_t n = dds_take(participants, samples, info, 8, 8);
    if (n > 0) {
      for (dds_return_t i = 0; i < n; i++) if (info[i].valid_data) {
        const dds_builtintopic_participant_t *data = samples[i];
        if (metadata(data->qos, "java 🛰")) participant_ok = true;
      }
      dds_return_loan(participants, samples, n);
    }
    dds_instance_handle_t handles[8];
    n = dds_get_matched_publications(reader, handles, 8);
    if (n > 0) {
      dds_builtintopic_endpoint_t *data = dds_get_matched_publication_data(reader, handles[0]);
      if (data) { endpoint_ok = metadata(data->qos, "writer 🛰"); dds_builtintopic_free_endpoint(data); }
    }
    void *messages[1] = {0};
    n = dds_take(reader, messages, info, 1, 1);
    if (n > 0) {
      if (info[0].valid_data) ping = ((HelloWorld_Message *)messages[0])->id == 1;
      dds_return_loan(reader, messages, n);
    }
    dds_sleepfor(DDS_MSECS(5));
  }
  bool ok = participant_ok && endpoint_ok && ping;
  if (ok) {
    HelloWorld_Message reply = {.id = 2, .text = "extensions verified"};
    ok = dds_write(writer, &reply) >= 0 && dds_wait_for_acks(writer, DDS_SECS(3)) >= 0;
  }
  if (ok) {
    bool confirmed = false;
    deadline = dds_time() + DDS_SECS(5);
    while (!confirmed && dds_time() < deadline) {
      void *samples[1] = {0}; dds_sample_info_t info[1];
      dds_return_t n = dds_take(reader, samples, info, 1, 1);
      if (n > 0) {
        confirmed = info[0].valid_data && ((HelloWorld_Message *)samples[0])->id == 3;
        dds_return_loan(reader, samples, n);
      }
      if (!confirmed) dds_sleepfor(DDS_MSECS(5));
    }
    ok = confirmed;
  }
  if (!ok) fprintf(stderr, "Extension peer: participant=%d endpoint=%d ping=%d\n", participant_ok, endpoint_ok, ping);
  dds_delete(p); return ok ? 0 : 1;
}
