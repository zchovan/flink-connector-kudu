/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.kudu.table.function.lookup;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.compress.utils.Lists;
import org.apache.flink.connector.kudu.connector.KuduFilterInfo;
import org.apache.flink.connector.kudu.connector.KuduTableInfo;
import org.apache.flink.connector.kudu.connector.converter.RowResultConverter;
import org.apache.flink.connector.kudu.connector.converter.RowResultRowDataConverter;
import org.apache.flink.connector.kudu.connector.reader.KuduInputSplit;
import org.apache.flink.connector.kudu.connector.reader.KuduReader;
import org.apache.flink.connector.kudu.connector.reader.KuduReaderConfig;
import org.apache.flink.connector.kudu.connector.reader.KuduReaderIterator;
import org.apache.flink.table.data.GenericRowData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.functions.FunctionContext;
import org.apache.flink.table.functions.TableFunction;
import org.apache.kudu.shaded.com.google.common.cache.Cache;
import org.apache.kudu.shaded.com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** LookupFunction based on the RowData object type. */
public class KuduRowDataLookupFunction extends TableFunction<RowData> {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(KuduRowDataLookupFunction.class);

    private final KuduTableInfo tableInfo;
    private final KuduReaderConfig kuduReaderConfig;
    private final List<String> keyNames;
    private final List<String> projectedFields;
    private final long cacheMaxSize;
    private final long cacheExpireMs;
    private final int maxRetryTimes;
    private final RowResultConverter<RowData> convertor;

    private transient Cache<RowData, List<RowData>> cache;
    private transient KuduReader<RowData> kuduReader;

    private KuduRowDataLookupFunction(
            List<String> keyNames,
            KuduTableInfo tableInfo,
            KuduReaderConfig kuduReaderConfig,
            List<String> projectedFields,
            KuduLookupOptions kuduLookupOptions) {
        this.tableInfo = tableInfo;
        this.convertor = new RowResultRowDataConverter();
        this.projectedFields = projectedFields;
        this.keyNames = keyNames;
        this.kuduReaderConfig = kuduReaderConfig;
        this.cacheMaxSize = kuduLookupOptions.getCacheMaxSize();
        this.cacheExpireMs = kuduLookupOptions.getCacheExpireMs();
        this.maxRetryTimes = kuduLookupOptions.getMaxRetryTimes();
    }

    public RowData buildCacheKey(Object... keys) {
        return GenericRowData.of(keys);
    }

    /**
     * invoke entry point of lookup function.
     *
     * @param keys join keys
     */
    public void eval(Object... keys) {
        if (keys.length != keyNames.size()) {
            throw new RuntimeException("The join keys are of unequal lengths");
        }
        // cache key
        RowData keyRow = buildCacheKey(keys);
        if (this.cache != null) {
            List<RowData> cacheRows = this.cache.getIfPresent(keyRow);
            if (CollectionUtils.isNotEmpty(cacheRows)) {
                for (RowData cacheRow : cacheRows) {
                    collect(cacheRow);
                }
                return;
            }
        }

        for (int retry = 1; retry <= maxRetryTimes; retry++) {
            try {
                List<KuduFilterInfo> kuduFilterInfos = buildKuduFilterInfo(keys);
                this.kuduReader.setTableFilters(kuduFilterInfos);
                KuduInputSplit[] inputSplits = kuduReader.createInputSplits(1);
                ArrayList<RowData> rows = new ArrayList<>();
                for (KuduInputSplit inputSplit : inputSplits) {
                    KuduReaderIterator<RowData> scanner =
                            kuduReader.scanner(inputSplit.getScanToken());
                    // not use cache
                    if (cache == null) {
                        while (scanner.hasNext()) {
                            collect(scanner.next());
                        }
                    } else {
                        while (scanner.hasNext()) {
                            RowData row = scanner.next();
                            rows.add(row);
                            collect(row);
                        }
                        rows.trimToSize();
                    }
                }
                if (cache != null) {
                    cache.put(keyRow, rows);
                }
                break;
            } catch (Exception e) {
                LOG.error(String.format("Kudu scan error, retry times = %d", retry), e);
                if (retry >= maxRetryTimes) {
                    throw new RuntimeException("Execution of Kudu scan failed.", e);
                }
                try {
                    Thread.sleep(1000L * retry);
                } catch (InterruptedException e1) {
                    throw new RuntimeException(e1);
                }
            }
        }
    }

    private List<KuduFilterInfo> buildKuduFilterInfo(Object... keyValS) {
        List<KuduFilterInfo> kuduFilterInfos = Lists.newArrayList();
        for (int i = 0; i < keyNames.size(); i++) {
            KuduFilterInfo kuduFilterInfo =
                    KuduFilterInfo.Builder.create(keyNames.get(i)).equalTo(keyValS[i]).build();
            kuduFilterInfos.add(kuduFilterInfo);
        }
        return kuduFilterInfos;
    }

    @Override
    public void open(FunctionContext context) {
        try {
            super.open(context);
            this.kuduReader =
                    new KuduReader<>(this.tableInfo, this.kuduReaderConfig, this.convertor);
            // build kudu cache
            this.kuduReader.setTableProjections(
                    projectedFields.isEmpty() ? projectedFields : null);
            this.cache =
                    this.cacheMaxSize == -1 || this.cacheExpireMs == -1
                            ? null
                            : CacheBuilder.newBuilder()
                                    .expireAfterWrite(this.cacheExpireMs, TimeUnit.MILLISECONDS)
                                    .maximumSize(this.cacheMaxSize)
                                    .build();
        } catch (Exception ioe) {
            LOG.error("Exception while creating connection to Kudu.", ioe);
            throw new RuntimeException("Cannot create connection to Kudu.", ioe);
        }
    }

    @Override
    public void close() {
        if (null != this.kuduReader) {
            try {
                this.kuduReader.close();
                if (cache != null) {
                    this.cache.cleanUp();
                    // help gc
                    this.cache = null;
                }
                this.kuduReader = null;
            } catch (IOException e) {
                // ignore exception when close.
                LOG.warn("exception when close table", e);
            }
        }
    }

    /** Builder for KuduRowDataLookupFunction. */
    public static class Builder {
        private KuduTableInfo tableInfo;
        private KuduReaderConfig kuduReaderConfig;
        private List<String> keyNames;
        private List<String> projectedFields;
        private KuduLookupOptions kuduLookupOptions;

        public static Builder options() {
            return new Builder();
        }

        public Builder tableInfo(KuduTableInfo tableInfo) {
            this.tableInfo = tableInfo;
            return this;
        }

        public Builder kuduReaderConfig(KuduReaderConfig kuduReaderConfig) {
            this.kuduReaderConfig = kuduReaderConfig;
            return this;
        }

        public Builder keyNames(List<String> keyNames) {
            this.keyNames = keyNames;
            return this;
        }

        public Builder projectedFields(List<String> projectedFields) {
            this.projectedFields = projectedFields;
            return this;
        }

        public Builder kuduLookupOptions(KuduLookupOptions kuduLookupOptions) {
            this.kuduLookupOptions = kuduLookupOptions;
            return this;
        }

        public KuduRowDataLookupFunction build() {
            return new KuduRowDataLookupFunction(
                    keyNames, tableInfo, kuduReaderConfig, projectedFields, kuduLookupOptions);
        }
    }
}
