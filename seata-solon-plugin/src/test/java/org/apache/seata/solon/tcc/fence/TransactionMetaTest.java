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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.noear.solon.data.annotation.Transaction;
import org.noear.solon.data.tran.TranIsolation;
import org.noear.solon.data.tran.TranPolicy;

public class TransactionMetaTest {

    @Test
    public void testTransactionMetaFields() {
        TransactionMeta meta = new TransactionMeta(
                TranPolicy.required, TranIsolation.read_committed, false);

        Assertions.assertEquals(TranPolicy.required, meta.policy());
        Assertions.assertEquals(TranIsolation.read_committed, meta.isolation());
        Assertions.assertFalse(meta.readOnly());
        Assertions.assertEquals("", meta.message());
        // must behave as a Transaction annotation instance
        Assertions.assertEquals(Transaction.class, meta.annotationType());
    }

    @Test
    public void testTransactionMetaWithMessage() {
        TransactionMeta meta = new TransactionMeta(
                TranPolicy.requires_new, TranIsolation.unspecified, true, "fence");

        Assertions.assertEquals(TranPolicy.requires_new, meta.policy());
        Assertions.assertEquals(TranIsolation.unspecified, meta.isolation());
        Assertions.assertTrue(meta.readOnly());
        Assertions.assertEquals("fence", meta.message());
    }
}
