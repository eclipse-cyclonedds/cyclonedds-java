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

#include "dds/dds.h"
#include "Parity.h"
#include <stdlib.h>
#include <stdio.h>

int main(int argc, char **argv) {
  if (argc != 3) return 2;
  dds_entity_t p = dds_create_participant((dds_domainid_t)atoi(argv[1]), NULL, NULL);
  if (p < 0) return 3;
  dds_qos_t *q = dds_create_qos();
  dds_qset_reliability(q, DDS_RELIABILITY_RELIABLE, DDS_SECS(3));
  dds_qset_history(q, DDS_HISTORY_KEEP_ALL, 0);
  dds_data_representation_id_t representation = atoi(argv[2]) == 1 ? DDS_DATA_REPRESENTATION_XCDR1 : DDS_DATA_REPRESENTATION_XCDR2;
  dds_qset_data_representation(q, 1, &representation);
  dds_entity_t t = dds_create_topic(p, &Parity_Masks_desc, "ParityMasks", NULL, NULL);
  dds_entity_t reply = dds_create_topic(p, &Parity_Masks_desc, "ParityReply", NULL, NULL);
  dds_entity_t rd = dds_create_reader(p, t, q, NULL), wr = dds_create_writer(p, reply, q, NULL);
  dds_delete_qos(q);
  if (rd < 0 || wr < 0) { dds_delete(p); return 4; }
  dds_time_t until = dds_time() + DDS_SECS(15);
  int result = 5;
  while (dds_time() < until) {
    void *samples[8] = {0}; dds_sample_info_t info[8];
    dds_return_t count = dds_take(rd, samples, info, 8, 8);
    if (count < 0) break;
    for (int i = 0; i < count; i++) {
      if (!info[i].valid_data) continue;
      Parity_Masks *m = samples[i];
      if (m->id != 7 || m->one != 1 || m->small != 17 || m->byteFlags != 129 || m->shortFlags != 32769
          || m->intFlags != UINT32_C(0x80000001) || m->longFlags != UINT64_C(0x8000000000000001)
          || m->fixedFlags[0] != 1 || m->fixedFlags[1] != 128 || m->flags._length != 2
          || m->flags._buffer[0] != 32768 || m->flags._buffer[1] != 1
          || m->choice._d != 1 || m->choice._u.mask != UINT64_C(0x8000000000000000)) { result = 6; goto done; }
      dds_publication_matched_status_t match;
      while (dds_time() < until) {
        if (dds_get_publication_matched_status(wr, &match) == 0 && match.current_count > 0) break;
        dds_sleepfor(DDS_MSECS(5));
      }
      m->id = 8;
      result = dds_write(wr, m) == 0 ? 0 : 7;
      goto done;
    }
    if (count > 0) dds_return_loan(rd, samples, count);
    dds_sleepfor(DDS_MSECS(5));
    continue;
  done:
    if (count > 0) dds_return_loan(rd, samples, count);
    if (!result) dds_wait_for_acks(wr, DDS_SECS(3));
    break;
  }
  dds_delete(p);
  if (result) fprintf(stderr, "parity-peer failed: %d\n", result);
  return result;
}
