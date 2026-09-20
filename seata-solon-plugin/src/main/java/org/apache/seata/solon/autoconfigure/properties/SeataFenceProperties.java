/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.seata.solon.autoconfigure.properties;

import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

import static org.apache.seata.solon.autoconfigure.StarterConstants.TCC_FENCE_PREFIX;

/**
 * TCC fence (anti-suspension) properties, bound from {@code seata.tcc.fence}.
 *
 * <p>Solon-native counterpart of the Spring {@code @ConfigurationProperties(TCC_FENCE_PREFIX)}
 * binding applied on {@code SpringFenceConfig}. The two fields map to
 * {@code CommonFenceConfig#setCleanPeriod(Duration)} and
 * {@code CommonFenceConfig#setLogTableName(String)}.</p>
 *
 * <p>{@code cleanPeriod} is kept as a raw string (e.g. {@code "7d"}, {@code "PT1H"}) and parsed
 * to a {@link java.time.Duration} at wiring time, to avoid binder-specific Duration handling.</p>
 */
@Configuration
@Inject(value = "${" + TCC_FENCE_PREFIX + "}", required = false)
public class SeataFenceProperties {

    private String logTableName;

    private String cleanPeriod;

    public String getLogTableName() {
        return logTableName;
    }

    public void setLogTableName(String logTableName) {
        this.logTableName = logTableName;
    }

    public String getCleanPeriod() {
        return cleanPeriod;
    }

    public void setCleanPeriod(String cleanPeriod) {
        this.cleanPeriod = cleanPeriod;
    }
}
