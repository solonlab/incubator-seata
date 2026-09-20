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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Unit tests for {@link SeataPlugin#parseCleanPeriod(String)}, covering ISO-8601,
 * simple {@code <number><unit>} forms, bare number (days), and invalid/blank input.
 */
public class SeataPluginParseCleanPeriodTest {

    @Test
    public void testBlankReturnsNull() {
        Assertions.assertNull(SeataPlugin.parseCleanPeriod(null));
        Assertions.assertNull(SeataPlugin.parseCleanPeriod(""));
        Assertions.assertNull(SeataPlugin.parseCleanPeriod("   "));
    }

    @Test
    public void testIso8601() {
        Assertions.assertEquals(Duration.ofHours(1), SeataPlugin.parseCleanPeriod("PT1H"));
        Assertions.assertEquals(Duration.ofDays(7), SeataPlugin.parseCleanPeriod("P7D"));
    }

    @Test
    public void testSimpleUnits() {
        Assertions.assertEquals(Duration.ofMillis(500), SeataPlugin.parseCleanPeriod("500ms"));
        Assertions.assertEquals(Duration.ofDays(7), SeataPlugin.parseCleanPeriod("7d"));
        Assertions.assertEquals(Duration.ofHours(2), SeataPlugin.parseCleanPeriod("2h"));
        Assertions.assertEquals(Duration.ofMinutes(30), SeataPlugin.parseCleanPeriod("30m"));
        Assertions.assertEquals(Duration.ofSeconds(45), SeataPlugin.parseCleanPeriod("45s"));
    }

    @Test
    public void testBareNumberIsDays() {
        Assertions.assertEquals(Duration.ofDays(3), SeataPlugin.parseCleanPeriod("3"));
    }

    @Test
    public void testInvalidReturnsNull() {
        Assertions.assertNull(SeataPlugin.parseCleanPeriod("abc"));
        Assertions.assertNull(SeataPlugin.parseCleanPeriod("7x"));
    }
}
