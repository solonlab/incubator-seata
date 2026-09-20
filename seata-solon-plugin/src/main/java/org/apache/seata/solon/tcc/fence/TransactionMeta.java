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

import org.noear.solon.data.annotation.Transaction;
import org.noear.solon.data.tran.TranIsolation;
import org.noear.solon.data.tran.TranPolicy;

import java.lang.annotation.Annotation;

/**
 * A programmatic {@link Transaction} instance used to drive {@link org.noear.solon.data.tran.TranUtils#execute}
 * without an actual annotated method. This lets the fence handler build transaction metadata (policy /
 * isolation / read-only) on the fly, mirroring how the Spring version constructs a {@code TransactionTemplate}.
 */
public class TransactionMeta implements Transaction {

    private final TranPolicy policy;
    private final TranIsolation isolation;
    private final boolean readOnly;
    private final String message;

    public TransactionMeta(TranPolicy policy, TranIsolation isolation, boolean readOnly) {
        this(policy, isolation, readOnly, "");
    }

    public TransactionMeta(TranPolicy policy, TranIsolation isolation, boolean readOnly, String message) {
        this.policy = policy;
        this.isolation = isolation;
        this.readOnly = readOnly;
        this.message = message;
    }

    @Override
    public TranPolicy policy() {
        return policy;
    }

    @Override
    public TranIsolation isolation() {
        return isolation;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public Class<? extends Annotation> annotationType() {
        return Transaction.class;
    }
}
