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
package org.apache.seata.solon.tcc.fence;

import org.apache.seata.common.exception.FrameworkErrorCode;
import org.apache.seata.integration.tx.api.fence.config.CommonFenceConfig;
import org.apache.seata.integration.tx.api.fence.exception.CommonFenceException;

import javax.sql.DataSource;

/**
 * Solon fence config, the Solon-native counterpart of {@code SpringFenceConfig}.
 *
 * <p>Unlike the Spring version which needs both a {@code DataSource} and a
 * {@code PlatformTransactionManager}, the Solon version only needs the (Seata-proxied)
 * {@code DataSource}: local transactions are driven by solon-data's {@code TranUtils}
 * inside {@link SolonFenceHandler}, so no external transaction manager is required.</p>
 *
 * <p>The instance is registered into {@code ObjectHolder} under
 * {@code Constants.BEAN_NAME_SPRING_FENCE_CONFIG}; the framework-agnostic
 * {@code TccActionInterceptorHandler} lazily calls {@link #init()} the first time it
 * encounters a TCC method with {@code useTCCFence()=true}.</p>
 */
public class SolonFenceConfig extends CommonFenceConfig {

    private final DataSource dataSource;

    public SolonFenceConfig(DataSource dataSource) {
        if (dataSource == null) {
            throw new CommonFenceException(FrameworkErrorCode.DateSourceNeedInjected);
        }
        this.dataSource = dataSource;
        // hand the (proxied) dataSource to the handler so fence records share the
        // business local transaction connection (atomicity of fence + business op).
        SolonFenceHandler.setDataSource(dataSource);
    }

    public DataSource getDataSource() {
        return dataSource;
    }
}
