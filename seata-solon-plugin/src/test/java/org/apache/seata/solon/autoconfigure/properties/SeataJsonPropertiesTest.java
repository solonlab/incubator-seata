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

import org.apache.seata.common.json.JsonAllowlistManager;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SeataJsonPropertiesTest {

    @Test
    public void testSeataJsonProperties() {
        SeataJsonProperties properties = new SeataJsonProperties();

        properties.setSerializerType("jackson");
        Assertions.assertEquals("jackson", properties.getSerializerType());

        properties.setAllowlist("com.company.model.,com.company.SomeClass");
        Assertions.assertEquals("com.company.model.,com.company.SomeClass", properties.getAllowlist());
    }

    @Test
    public void testInitLoadsAllowlist() {
        SeataJsonProperties properties = new SeataJsonProperties();
        properties.setAllowlist("com.example.demo.");
        // init loads the user allowlist into the shared JsonAllowlistManager
        properties.init();

        JsonAllowlistManager manager = JsonAllowlistManager.getInstance();
        Assertions.assertTrue(manager.isAllowed("com.example.demo.Order"));
        Assertions.assertFalse(manager.isAllowed("com.unknown.Evil"));

        // reset to avoid leaking state to other tests
        manager.clearUserAllowlist();
    }
}
