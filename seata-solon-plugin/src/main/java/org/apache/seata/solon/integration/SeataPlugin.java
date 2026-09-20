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
package org.apache.seata.solon.integration;

import org.apache.seata.common.holder.ObjectHolder;
import org.apache.seata.solon.annotation.GlobalTransactionalInterceptor;
import org.apache.seata.solon.annotation.datasource.SeataAutoDataSourceProxyCreator;
import org.apache.seata.solon.autoconfigure.properties.PropertiesHelper;
import org.apache.seata.solon.autoconfigure.SeataAutoConfiguration;
import org.apache.seata.solon.autoconfigure.properties.SeataFenceProperties;
import org.apache.seata.solon.autoconfigure.properties.client.ServiceProperties;
import org.apache.seata.solon.tcc.fence.SolonFenceConfig;
import org.apache.seata.solon.integration.intercept.SeataHttpExtension;
import org.apache.seata.solon.integration.intercept.SeataNamiFilter;
import org.apache.seata.solon.integration.intercept.SeataSolonRouterInterceptor;
import org.apache.seata.solon.autoconfigure.properties.SeataProperties;
import org.apache.seata.spring.annotation.GlobalLock;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.noear.nami.NamiManager;
import org.noear.solon.Utils;
import org.noear.solon.core.AppContext;
import org.noear.solon.core.Plugin;
import org.noear.solon.core.util.ClassUtil;
import org.noear.solon.net.http.HttpExtensionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.time.Duration;

import static org.apache.seata.common.Constants.BEAN_NAME_SPRING_FENCE_CONFIG;

/**
 * Seata for solon plugin (like module lifecycle)
 *
 * @author noear 2024/10/25 created
 */
public class SeataPlugin implements Plugin {
    private static final Logger LOGGER = LoggerFactory.getLogger(SeataPlugin.class);

    @Override
    public void start(AppContext context) throws Throwable {
        context.getBeanAsync(ServiceProperties.class, bean -> {
            bean.afterPropertiesSet();
        });

        PropertiesHelper.initBeanMap();

        //for autoconfigure
        context.beanScan(PropertiesHelper.class);
        context.beanMake(SeataAutoConfiguration.class);

        SeataProperties seataProperties = context.getBean(SeataProperties.class);
        SeataAutoDataSourceProxyCreator seataAutoDataSourceProxyCreator = new SeataAutoDataSourceProxyCreator(
                seataProperties.getExcludesForAutoProxying(), seataProperties.getDataSourceProxyMode());

        //for dataSource proxy
        context.subWrapsOfType(DataSource.class, bw -> {
            bw.proxySet(seataAutoDataSourceProxyCreator);
        }, Integer.MIN_VALUE);

        //for tcc fence (anti-suspension); only when a DataSource exists.
        //the (proxied) DataSource is used so fence records share the business local
        //transaction connection, keeping fence + business op atomic.
        context.getBeanAsync(DataSource.class, dataSource -> {
            SolonFenceConfig fenceConfig = new SolonFenceConfig(dataSource);
            SeataFenceProperties fenceProperties = context.getBean(SeataFenceProperties.class);
            if (fenceProperties != null) {
                if (Utils.isNotEmpty(fenceProperties.getLogTableName())) {
                    fenceConfig.setLogTableName(fenceProperties.getLogTableName());
                }
                Duration cleanPeriod = parseCleanPeriod(fenceProperties.getCleanPeriod());
                if (cleanPeriod != null) {
                    fenceConfig.setCleanPeriod(cleanPeriod);
                }
            }
            //the framework-agnostic TccActionInterceptorHandler reads this and lazily calls init()
            ObjectHolder.INSTANCE.setObject(BEAN_NAME_SPRING_FENCE_CONFIG, fenceConfig);
        });

        //for nami
        if (ClassUtil.hasClass(() -> NamiManager.class)) {
            NamiManager.reg(new SeataNamiFilter());
        }

        //for http-utils
        if (ClassUtil.hasClass(() -> HttpExtensionManager.class)) {
            HttpExtensionManager.add(new SeataHttpExtension());
        }

        //for solon
        context.app().routerInterceptor(Integer.MIN_VALUE, new SeataSolonRouterInterceptor());

        //for annotation
        GlobalTransactionalInterceptor globalTransactionalInterceptor = new GlobalTransactionalInterceptor();
        context.beanInterceptorAdd(GlobalLock.class, globalTransactionalInterceptor);
        context.beanInterceptorAdd(GlobalTransactional.class, globalTransactionalInterceptor);
    }

    /**
     * Parse the {@code seata.tcc.fence.clean-period} value into a {@link Duration}.
     *
     * <p>Supports ISO-8601 duration text (e.g. {@code PT1H}, {@code P7D}) as well as the
     * simple {@code <number><unit>} form used by Spring's duration binder, where unit is one of
     * {@code d} (days), {@code h} (hours), {@code m} (minutes), {@code s} (seconds), or
     * {@code ms} (millis); a bare number is treated as days to match
     * {@code CommonFenceConfig}'s default unit. Returns {@code null} when blank or unparseable
     * (keeping the {@code CommonFenceConfig} default).</p>
     */
    static Duration parseCleanPeriod(String value) {
        if (Utils.isEmpty(value)) {
            return null;
        }
        String text = value.trim();
        try {
            // ISO-8601, e.g. PT1H / P7D
            if (text.length() > 1 && (text.charAt(0) == 'P' || text.charAt(0) == 'p'
                    || ((text.charAt(0) == '+' || text.charAt(0) == '-')
                        && (text.charAt(1) == 'P' || text.charAt(1) == 'p')))) {
                return Duration.parse(text);
            }
            // simple <number><unit> form
            String lower = text.toLowerCase();
            if (lower.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(lower.substring(0, lower.length() - 2).trim()));
            } else if (lower.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(lower.substring(0, lower.length() - 1).trim()));
            } else if (lower.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(lower.substring(0, lower.length() - 1).trim()));
            } else if (lower.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(lower.substring(0, lower.length() - 1).trim()));
            } else if (lower.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(lower.substring(0, lower.length() - 1).trim()));
            } else {
                // bare number -> days (CommonFenceConfig default unit)
                return Duration.ofDays(Long.parseLong(lower));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Invalid seata.tcc.fence.clean-period value '{}', keep default. cause: {}",
                    value, e.getMessage());
            return null;
        }
    }
}