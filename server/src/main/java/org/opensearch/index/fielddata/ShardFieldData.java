/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.index.fielddata;

import com.carrotsearch.hppc.ObjectLongHashMap;
import org.apache.lucene.util.Accountable;
import org.opensearch.common.FieldMemoryStats;
import org.opensearch.common.metrics.CounterMetric;
import org.opensearch.common.regex.Regex;
import org.opensearch.common.util.CollectionUtils;
import org.opensearch.common.util.concurrent.ConcurrentCollections;
import org.opensearch.core.index.shard.ShardId;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * On heap field data for shards
 *
 * @opensearch.internal
 */
public class ShardFieldData implements IndexFieldDataCache.Listener {

    private final CounterMetric evictionsMetric = new CounterMetric();
    private final CounterMetric totalMetric = new CounterMetric();
    private final ConcurrentMap<String, CounterMetric> perFieldTotals = ConcurrentCollections.newConcurrentMap();

    public FieldDataStats stats(String... fields) {
        ObjectLongHashMap<String> fieldTotals = null;
        if (CollectionUtils.isEmpty(fields) == false) {
            fieldTotals = new ObjectLongHashMap<>();
            for (Map.Entry<String, CounterMetric> entry : perFieldTotals.entrySet()) {
                if (Regex.simpleMatch(fields, entry.getKey())) {
                    fieldTotals.put(entry.getKey(), entry.getValue().count());
                }
            }
        }
        return new FieldDataStats(
            totalMetric.count(),
            evictionsMetric.count(),
            fieldTotals == null ? null : new FieldMemoryStats(fieldTotals)
        );
    }

    @Override
    public void onCache(ShardId shardId, String fieldName, Accountable ramUsage) {
        totalMetric.inc(ramUsage.ramBytesUsed());
        CounterMetric total = perFieldTotals.get(fieldName);
        if (total != null) {
            total.inc(ramUsage.ramBytesUsed());
        } else {
            total = new CounterMetric();
            total.inc(ramUsage.ramBytesUsed());
            CounterMetric prev = perFieldTotals.putIfAbsent(fieldName, total);
            if (prev != null) {
                prev.inc(ramUsage.ramBytesUsed());
            }
        }
    }

    @Override
    public void onRemoval(ShardId shardId, String fieldName, boolean wasEvicted, long sizeInBytes) {
        if (wasEvicted) {
            evictionsMetric.inc();
        }
        if (sizeInBytes != -1) {
            totalMetric.dec(sizeInBytes);

            CounterMetric total = perFieldTotals.get(fieldName);
            if (total != null) {
                total.dec(sizeInBytes);
            }
        }
    }
}
