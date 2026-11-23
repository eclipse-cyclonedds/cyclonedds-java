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
#include <stdlib.h>
#include <string.h>
#include "dds/dds.h"
#include "dds/ddsc/dds_psmx.h"
#include "dds/ddsc/dds_loaned_sample.h"
#include "dds/ddsi/ddsi_serdata.h"
#include "typesupport.h"
#include "HelloWorld.h"

#define CHECK(expression) do { if (!(expression)) { fprintf(stderr, "PSMX check failed at line %d: %s\n", __LINE__, #expression); return 1; } } while (0)
static unsigned released;
static void release_loan(dds_loaned_sample_t *loan) { (void)loan; released++; }
int main(void) {
  dds_entity_t participant = dds_create_participant(110, NULL, NULL);
  CHECK(participant > 0);
  const dds_topic_descriptor_t *desc = &HelloWorld_Message_desc;
  dds_entity_t topic = java_create_topic(participant, "PsmxByteSupport", desc->m_typename, true, 5,
      desc->type_information.data, desc->type_information.sz, desc->type_mapping.data, desc->type_mapping.sz, NULL);
  CHECK(topic > 0);
  const struct ddsi_sertype *type;
  CHECK(dds_get_entity_sertype(topic, &type) == DDS_RETCODE_OK);
  unsigned char wire[] = {0, 7, 0, 0, 7, 0, 0, 0, 3, 0, 0, 0, 'o', 'k', 0};
  unsigned char output[sizeof(wire)];
  dds_psmx_metadata_t md = {.sample_state = DDS_LOANED_SAMPLE_STATE_SERIALIZED_DATA,
      .sample_size = sizeof(wire) - 4, .timestamp = 42, .statusinfo = 0};
  memcpy(&md.cdr_identifier, wire, 2); memcpy(&md.cdr_options, wire + 2, 2);
  dds_loaned_sample_t loan = {.ops = {.free = release_loan}, .metadata = &md, .sample_ptr = wire + 4};
  ddsrt_atomic_st32(&loan.refc, 1);
  struct ddsi_serdata *data = type->serdata_ops->from_psmx(type, &loan);
  CHECK(data != NULL && data->kind == SDK_DATA && data->timestamp.v == 42 && data->statusinfo == 0);
  CHECK(ddsi_serdata_size(data) == sizeof(wire));
  ddsi_serdata_to_ser(data, 0, sizeof(output), output);
  CHECK(memcmp(output, wire, sizeof(wire)) == 0);
  wire[12] = 'x';
  ddsi_serdata_to_ser(data, 0, sizeof(output), output);
  CHECK(output[12] == 'o');
  dds_loaned_sample_unref(&loan);
  CHECK(released == 1); /* Serialized PSMX input is copied, with no retained loan. */
  md.sample_state = DDS_LOANED_SAMPLE_STATE_SERIALIZED_KEY;
  md.sample_size = 4; md.statusinfo = 1;
  ddsrt_atomic_st32(&loan.refc, 1);
  struct ddsi_serdata *key = type->serdata_ops->from_psmx(type, &loan);
  CHECK(key != NULL && key->kind == SDK_KEY && key->statusinfo == 1);
  CHECK(type->serdata_ops->eqkey(data, key));
  ddsi_serdata_unref(key);
  md.sample_state = DDS_LOANED_SAMPLE_STATE_RAW_DATA;
  CHECK(type->serdata_ops->from_psmx(type, &loan) == NULL);
  md.sample_state = DDS_LOANED_SAMPLE_STATE_SERIALIZED_DATA; md.sample_size = 1;
  CHECK(type->serdata_ops->from_psmx(type, &loan) == NULL);
  CHECK(ddsrt_atomic_ld32(&loan.refc) == 1);
  dds_loaned_sample_unref(&loan); CHECK(released == 2);
  ddsi_serdata_unref(data);
  wire[12] = 'o';
  struct java_sample sample = {.bytes = wire, .size = sizeof(wire), .kind = SDK_DATA};
  loan.sample_ptr = &sample; ddsrt_atomic_st32(&loan.refc, 1);
  data = type->serdata_ops->from_loaned_sample(type, SDK_DATA, (const char *)&sample, &loan, true);
  CHECK(data != NULL && data->loan == &loan && released == 2);
  ddsi_serdata_unref(data); CHECK(released == 3);
  sample.size = 0; ddsrt_atomic_st32(&loan.refc, 1);
  CHECK(type->serdata_ops->from_loaned_sample(type, SDK_DATA, (const char *)&sample, &loan, true) == NULL);
  CHECK(ddsrt_atomic_ld32(&loan.refc) == 1);
  dds_loaned_sample_unref(&loan); CHECK(released == 4);
  CHECK(dds_delete(participant) == DDS_RETCODE_OK);
  puts("PSMX byte support: serialized data/key, copying, metadata, malformed input and loan ownership passed");
  return 0;
}
