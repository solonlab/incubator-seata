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
package org.apache.seata.solon.annotation.datasource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.core.BeanWrap;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Unit tests for the {@code seata.excludes-for-auto-proxying} support of
 * {@link SeataAutoDataSourceProxyCreator}, aligned with Spring's
 * {@code shouldSkip} semantics (fully-qualified class name matching).
 */
public class SeataAutoDataSourceProxyCreatorTest {

    /**
     * Minimal datasource stub; only identity matters here.
     */
    static class StubDataSource implements DataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new UnsupportedOperationException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    @Test
    public void testExcludedClassIsNotProxied() {
        StubDataSource ds = new StubDataSource();
        BeanWrap bw = new BeanWrap(null, StubDataSource.class, ds, "stubDs");

        SeataAutoDataSourceProxyCreator creator = new SeataAutoDataSourceProxyCreator(
                new String[] {StubDataSource.class.getName()}, "AT");

        // the excluded bean must be returned as-is (not wrapped)
        Assertions.assertSame(ds, creator.getProxy(bw, ds));
    }

    @Test
    public void testNonDataSourceBeanIsReturnedAsIs() {
        Object bean = new Object();
        BeanWrap bw = new BeanWrap(null, Object.class, bean, "someBean");

        SeataAutoDataSourceProxyCreator creator = new SeataAutoDataSourceProxyCreator(
                new String[] {"com.example.Anything"}, "AT");

        Assertions.assertSame(bean, creator.getProxy(bw, bean));
    }

    @Test
    public void testLegacyConstructorKeepsBehavior() {
        // single-arg constructor (no excludes) must keep working for compatibility
        SeataAutoDataSourceProxyCreator creator = new SeataAutoDataSourceProxyCreator("AT");

        Object bean = new Object();
        BeanWrap bw = new BeanWrap(null, Object.class, bean, "someBean");
        Assertions.assertSame(bean, creator.getProxy(bw, bean));
    }
}
