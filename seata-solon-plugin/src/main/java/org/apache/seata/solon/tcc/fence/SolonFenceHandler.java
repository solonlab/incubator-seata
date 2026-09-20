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

import org.apache.seata.common.Constants;
import org.apache.seata.common.exception.ExceptionUtil;
import org.apache.seata.common.exception.FrameworkErrorCode;
import org.apache.seata.common.exception.SkipCallbackWrapperException;
import org.apache.seata.common.executor.Callback;
import org.apache.seata.common.thread.ThreadPoolExecutorFactory;
import org.apache.seata.integration.tx.api.fence.DefaultCommonFenceHandler;
import org.apache.seata.integration.tx.api.fence.FenceHandler;
import org.apache.seata.integration.tx.api.fence.constant.CommonFenceConstant;
import org.apache.seata.integration.tx.api.fence.exception.CommonFenceException;
import org.apache.seata.integration.tx.api.fence.store.CommonFenceDO;
import org.apache.seata.integration.tx.api.fence.store.CommonFenceStore;
import org.apache.seata.integration.tx.api.fence.store.db.CommonFenceStoreDataBaseDAO;
import org.apache.seata.integration.tx.api.remoting.TwoPhaseResult;
import org.apache.seata.rm.tcc.api.BusinessActionContext;
import org.apache.seata.rm.tcc.api.BusinessActionContextUtil;
import org.noear.solon.data.annotation.Transaction;
import org.noear.solon.data.tran.TranIsolation;
import org.noear.solon.data.tran.TranPolicy;
import org.noear.solon.data.tran.TranUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Common Fence Handler for Solon (idempotent, non_rollback, suspend).
 *
 * <p>This is the Solon-native counterpart of {@code SpringFenceHandler}. It relies on
 * solon-data's programmatic transaction API ({@link TranUtils}) instead of Spring's
 * {@code TransactionTemplate}/{@code DataSourceUtils}, so the plugin stays free of any
 * Spring dependency while providing equivalent TCC fence (anti-suspension) capability.</p>
 *
 * <p>Rollback semantics note: unlike Spring which supports {@code status.setRollbackOnly()}
 * while still returning a value, solon-data rolls back only when the runnable throws. The
 * "business returns false, so roll back but report false" case is reproduced by throwing an
 * internal {@link FenceRollbackSignal} that carries the result and is unwrapped by the caller.</p>
 */
public class SolonFenceHandler implements FenceHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SolonFenceHandler.class);

    private static final CommonFenceStore COMMON_FENCE_DAO = CommonFenceStoreDataBaseDAO.getInstance();

    private static DataSource dataSource;

    private static final int MAX_THREAD_CLEAN = 1;

    private static final int MAX_QUEUE_SIZE = 500;

    /**
     * limit of delete record by date (per sql)
     */
    private static final int LIMIT_DELETE = 1000;

    /**
     * default transaction meta: required policy, unspecified isolation, not read-only.
     */
    private static final Transaction DEFAULT_TRANSACTION =
            new TransactionMeta(TranPolicy.required, TranIsolation.unspecified, false);

    private static final LinkedBlockingQueue<FenceLogIdentity> LOG_QUEUE = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);

    private static FenceLogCleanRunnable fenceLogCleanRunnable;

    private static ExecutorService logCleanExecutor;

    static {
        try {
            initLogCleanExecutor();
            DefaultCommonFenceHandler.get().setFenceHandler(new SolonFenceHandler());
        } catch (Exception e) {
            LOGGER.error("init fence log clean executor error", e);
        }
    }

    public static DataSource getDataSource() {
        return SolonFenceHandler.dataSource;
    }

    public static void setDataSource(DataSource dataSource) {
        SolonFenceHandler.dataSource = dataSource;
    }

    /**
     * common prepare method enhanced
     *
     * @param xid            the global transaction id
     * @param branchId       the branch transaction id
     * @param actionName     the action name
     * @param targetCallback the target callback
     * @return the object returned by the target callback
     */
    @Override
    public Object prepareFence(String xid, Long branchId, String actionName, Callback<Object> targetCallback) {
        Transaction meta = createTransaction(null);
        final Object[] holder = new Object[1];
        try {
            TranUtils.execute(meta, () -> {
                Connection conn = TranUtils.getConnection(dataSource);
                boolean result = insertCommonFenceLog(conn, xid, branchId, actionName, CommonFenceConstant.STATUS_TRIED);
                LOGGER.info("Common fence prepare result: {}. xid: {}, branchId: {}", result, xid, branchId);
                if (result) {
                    holder[0] = targetCallback.execute();
                } else {
                    throw new CommonFenceException(
                            String.format(
                                    "Insert common fence record error, prepare fence failed. xid= %s, branchId= %s",
                                    xid, branchId),
                            FrameworkErrorCode.InsertRecordError);
                }
            });
            return holder[0];
        } catch (CommonFenceException e) {
            if (e.getErrcode() == FrameworkErrorCode.DuplicateKeyException) {
                LOGGER.error(
                        "Branch transaction has already rollbacked before,prepare fence failed. xid= {},branchId = {}",
                        xid,
                        branchId);
                addToLogCleanQueue(xid, branchId);
            }
            throw new SkipCallbackWrapperException(e);
        } catch (Throwable t) {
            throw new SkipCallbackWrapperException(t);
        }
    }

    /**
     * common commit method enhanced
     *
     * @param commitMethod  commit method
     * @param targetTCCBean target common bean
     * @param xid           the global transaction id
     * @param branchId      the branch transaction id
     * @param args          commit method's parameters
     * @return the boolean
     */
    @Override
    public boolean commitFence(Method commitMethod, Object targetTCCBean, String xid, Long branchId, Object[] args) {
        Transaction meta = createTransaction(getTransactionAnnotationByMethod(commitMethod, targetTCCBean));
        final boolean[] holder = new boolean[] {false};
        try {
            TranUtils.execute(meta, () -> {
                Connection conn = TranUtils.getConnection(dataSource);
                CommonFenceDO commonFenceDO = COMMON_FENCE_DAO.queryCommonFenceDO(conn, xid, branchId);
                if (commonFenceDO == null) {
                    throw new CommonFenceException(
                            String.format(
                                    "Common fence record not exists, commit fence method failed. xid= %s, branchId= %s",
                                    xid, branchId),
                            FrameworkErrorCode.RecordNotExists);
                }
                if (CommonFenceConstant.STATUS_COMMITTED == commonFenceDO.getStatus()) {
                    LOGGER.info(
                            "Branch transaction has already committed before. idempotency rejected. xid: {}, branchId: {}, status: {}",
                            xid,
                            branchId,
                            commonFenceDO.getStatus());
                    holder[0] = true;
                    return;
                }
                if (CommonFenceConstant.STATUS_ROLLBACKED == commonFenceDO.getStatus()
                        || CommonFenceConstant.STATUS_SUSPENDED == commonFenceDO.getStatus()) {
                    if (LOGGER.isWarnEnabled()) {
                        LOGGER.warn(
                                "Branch transaction status is unexpected. xid: {}, branchId: {}, status: {}",
                                xid,
                                branchId,
                                commonFenceDO.getStatus());
                    }
                    holder[0] = false;
                    return;
                }
                boolean result = updateStatusAndInvokeTargetMethod(
                        conn,
                        commitMethod,
                        targetTCCBean,
                        xid,
                        branchId,
                        CommonFenceConstant.STATUS_COMMITTED,
                        args);
                holder[0] = result;
                LOGGER.info("Common fence commit result: {}. xid: {}, branchId: {}", result, xid, branchId);
                if (!result) {
                    // business result is false, roll back the transaction (report false)
                    throw new FenceRollbackSignal();
                }
            });
        } catch (FenceRollbackSignal s) {
            // transaction rolled back, holder already holds false
        } catch (Throwable t) {
            throw new SkipCallbackWrapperException(t);
        }
        return holder[0];
    }

    /**
     * Common rollback method enhanced
     *
     * @param rollbackMethod rollback method
     * @param targetTCCBean  target tcc bean
     * @param xid            the global transaction id
     * @param branchId       the branch transaction id
     * @param args           rollback method's parameters
     * @param actionName     the action name
     * @return the boolean
     */
    @Override
    public boolean rollbackFence(
            Method rollbackMethod, Object targetTCCBean, String xid, Long branchId, Object[] args, String actionName) {
        Transaction meta = createTransaction(getTransactionAnnotationByMethod(rollbackMethod, targetTCCBean));
        final boolean[] holder = new boolean[] {false};
        try {
            TranUtils.execute(meta, () -> {
                Connection conn = TranUtils.getConnection(dataSource);
                CommonFenceDO commonFenceDO = COMMON_FENCE_DAO.queryCommonFenceDO(conn, xid, branchId);
                // non_rollback
                if (commonFenceDO == null) {
                    boolean result =
                            insertCommonFenceLog(conn, xid, branchId, actionName, CommonFenceConstant.STATUS_SUSPENDED);
                    LOGGER.info("Insert common fence record result: {}. xid: {}, branchId: {}", result, xid, branchId);
                    if (!result) {
                        throw new CommonFenceException(
                                String.format(
                                        "Insert common fence record error, rollback fence method failed. xid= %s, branchId= %s",
                                        xid, branchId),
                                FrameworkErrorCode.InsertRecordError);
                    }
                    holder[0] = true;
                    return;
                } else {
                    if (CommonFenceConstant.STATUS_ROLLBACKED == commonFenceDO.getStatus()
                            || CommonFenceConstant.STATUS_SUSPENDED == commonFenceDO.getStatus()) {
                        LOGGER.info(
                                "Branch transaction had already rollbacked before, idempotency rejected. xid: {}, branchId: {}, status: {}",
                                xid,
                                branchId,
                                commonFenceDO.getStatus());
                        holder[0] = true;
                        return;
                    }
                    if (CommonFenceConstant.STATUS_COMMITTED == commonFenceDO.getStatus()) {
                        if (LOGGER.isWarnEnabled()) {
                            LOGGER.warn(
                                    "Branch transaction status is unexpected. xid: {}, branchId: {}, status: {}",
                                    xid,
                                    branchId,
                                    commonFenceDO.getStatus());
                        }
                        holder[0] = false;
                        return;
                    }
                }
                boolean result = updateStatusAndInvokeTargetMethod(
                        conn,
                        rollbackMethod,
                        targetTCCBean,
                        xid,
                        branchId,
                        CommonFenceConstant.STATUS_ROLLBACKED,
                        args);
                holder[0] = result;
                LOGGER.info("Common fence rollback result: {}. xid: {}, branchId: {}", result, xid, branchId);
                if (!result) {
                    throw new FenceRollbackSignal();
                }
            });
        } catch (FenceRollbackSignal s) {
            // transaction rolled back, holder already holds false
        } catch (Throwable t) {
            Throwable cause = t.getCause();
            if (cause instanceof SQLException) {
                SQLException sqlException = (SQLException) cause;
                String sqlState = sqlException.getSQLState();
                int errorCode = sqlException.getErrorCode();
                if (Constants.DEAD_LOCK_SQL_STATE.equals(sqlState) && Constants.DEAD_LOCK_ERROR_CODE == errorCode) {
                    // MySQL deadlock exception
                    LOGGER.error(
                            "Common fence rollback fail. xid: {}, branchId: {}, This exception may be due to the deadlock caused by the transaction isolation level being Repeatable Read. The seata server will try to roll back again, so you can ignore this exception. (To avoid this exception, you can set transaction isolation to Read Committed.)",
                            xid,
                            branchId);
                }
            }
            throw new SkipCallbackWrapperException(t);
        }
        return holder[0];
    }

    /**
     * Insert Common fence log
     *
     * @param conn     the db connection
     * @param xid      the xid
     * @param branchId the branchId
     * @param status   the status
     * @return the boolean
     */
    private static boolean insertCommonFenceLog(
            Connection conn, String xid, Long branchId, String actionName, Integer status) {
        CommonFenceDO commonFenceDO = new CommonFenceDO();
        commonFenceDO.setXid(xid);
        commonFenceDO.setBranchId(branchId);
        commonFenceDO.setActionName(actionName);
        commonFenceDO.setStatus(status);
        return COMMON_FENCE_DAO.insertCommonFenceDO(conn, commonFenceDO);
    }

    /**
     * Update Common Fence status and invoke target method
     *
     * @param method        target method
     * @param targetTCCBean target bean
     * @param xid           the global transaction id
     * @param branchId      the branch transaction id
     * @param status        the common fence status
     * @return the boolean
     */
    private static boolean updateStatusAndInvokeTargetMethod(
            Connection conn,
            Method method,
            Object targetTCCBean,
            String xid,
            Long branchId,
            int status,
            Object[] args)
            throws Throwable {
        boolean result =
                COMMON_FENCE_DAO.updateCommonFenceDO(conn, xid, branchId, status, CommonFenceConstant.STATUS_TRIED);
        if (result) {
            try {
                // invoke two phase method
                Object ret = method.invoke(targetTCCBean, args);
                if (null != ret) {
                    if (ret instanceof TwoPhaseResult) {
                        result = ((TwoPhaseResult) ret).isSuccess();
                    } else {
                        result = (boolean) ret;
                    }
                }
            } catch (Exception e) {
                throw ExceptionUtil.unwrap(e);
            }
        }
        return result;
    }

    /**
     * Delete Common Fence
     *
     * @param xid      the global transaction id
     * @param branchId the branch transaction id
     * @return the boolean
     */
    public static boolean deleteFence(String xid, Long branchId) {
        final boolean[] holder = new boolean[] {false};
        try {
            TranUtils.execute(DEFAULT_TRANSACTION, () -> {
                Connection conn = TranUtils.getConnection(dataSource);
                holder[0] = COMMON_FENCE_DAO.deleteCommonFenceDO(conn, xid, branchId);
            });
        } catch (Throwable e) {
            LOGGER.error("delete fence log failed, xid: {}, branchId: {}", xid, branchId, e);
        }
        return holder[0];
    }

    /**
     * Delete Common Fence By Datetime
     *
     * @param datetime datetime
     * @return the deleted row count
     */
    @Override
    public int deleteFenceByDate(Date datetime) {
        DataSource dataSource = SolonFenceHandler.getDataSource();
        Connection connection = null;
        int total = 0;
        try {
            connection = dataSource.getConnection();
            while (true) {
                Set<String> xidSet = COMMON_FENCE_DAO.queryEndStatusXidsByDate(connection, datetime, LIMIT_DELETE);
                if (xidSet.isEmpty()) {
                    break;
                }
                total += COMMON_FENCE_DAO.deleteTCCFenceDO(connection, new ArrayList<>(xidSet), datetime);
                if (xidSet.size() < LIMIT_DELETE) {
                    break;
                }
            }
        } catch (RuntimeException | SQLException e) {
            LOGGER.error("delete fence log failed ", e);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    LOGGER.warn("close connection failed when delete fence log by date", e);
                }
            }
        }
        return total;
    }

    private static void initLogCleanExecutor() {
        logCleanExecutor = ThreadPoolExecutorFactory.newThreadPoolExecutor(
                "fenceLogCleanThread",
                MAX_THREAD_CLEAN,
                MAX_THREAD_CLEAN,
                Integer.MAX_VALUE,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                true);
        fenceLogCleanRunnable = new FenceLogCleanRunnable();
        logCleanExecutor.submit(fenceLogCleanRunnable);
    }

    private static void addToLogCleanQueue(final String xid, final long branchId) {
        FenceLogIdentity logIdentity = new FenceLogIdentity();
        logIdentity.setXid(xid);
        logIdentity.setBranchId(branchId);
        try {
            LOG_QUEUE.add(logIdentity);
        } catch (Exception e) {
            LOGGER.warn(
                    "Insert tcc fence record into queue for async delete error,xid:{},branchId:{}", xid, branchId, e);
        }
    }

    /**
     * Build a transaction meta with the business transactional attributes.
     *
     * @param methodTransaction the {@code @Transaction} annotation declared on the two-phase method (nullable)
     * @return the transaction meta to drive the local transaction
     */
    private Transaction createTransaction(Transaction methodTransaction) {
        Map<String, Object> businessActionContext = Optional.ofNullable(BusinessActionContextUtil.getContext())
                .map(BusinessActionContext::getActionContext)
                .orElse(null);
        if (methodTransaction == null && businessActionContext == null) {
            return DEFAULT_TRANSACTION;
        }
        if (methodTransaction != null) {
            return methodTransaction;
        } else {
            boolean containIsolation = businessActionContext.containsKey(Constants.TX_ISOLATION);
            if (!containIsolation) {
                return DEFAULT_TRANSACTION;
            }
            int isolationLevel = (int) businessActionContext.get(Constants.TX_ISOLATION);
            return new TransactionMeta(TranPolicy.required, toTranIsolation(isolationLevel), false);
        }
    }

    /**
     * Read the solon-data {@code @Transaction} annotation from the two-phase method, falling back
     * to the declaring bean type. Mirrors the Spring version reading {@code @Transactional}.
     */
    private static Transaction getTransactionAnnotationByMethod(Method method, Object targetBean) {
        if (method != null) {
            Transaction t = method.getAnnotation(Transaction.class);
            if (t != null) {
                return t;
            }
        }
        if (targetBean != null) {
            return targetBean.getClass().getAnnotation(Transaction.class);
        }
        return null;
    }

    /**
     * Convert a JDBC {@link Connection} isolation level to the solon-data {@link TranIsolation}.
     * The numeric values of both are aligned with the JDBC constants.
     */
    private static TranIsolation toTranIsolation(int jdbcLevel) {
        for (TranIsolation isolation : TranIsolation.values()) {
            if (isolation.level == jdbcLevel) {
                return isolation;
            }
        }
        return TranIsolation.unspecified;
    }

    /**
     * clean fence log that has the final status runnable.
     *
     * @see CommonFenceConstant
     */
    private static class FenceLogCleanRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    FenceLogIdentity logIdentity = LOG_QUEUE.take();
                    boolean ret = SolonFenceHandler.deleteFence(logIdentity.getXid(), logIdentity.getBranchId());
                    if (!ret) {
                        LOGGER.error(
                                "delete fence log failed, xid: {}, branchId: {}",
                                logIdentity.getXid(),
                                logIdentity.getBranchId());
                    }
                } catch (InterruptedException e) {
                    LOGGER.error("take fence log from queue for clean be interrupted", e);
                } catch (Exception e) {
                    LOGGER.error("exception occur when clean fence log", e);
                }
            }
        }
    }

    private static class FenceLogIdentity {
        /**
         * the global transaction id
         */
        private String xid;

        /**
         * the branch transaction id
         */
        private Long branchId;

        public String getXid() {
            return xid;
        }

        public Long getBranchId() {
            return branchId;
        }

        public void setXid(String xid) {
            this.xid = xid;
        }

        public void setBranchId(Long branchId) {
            this.branchId = branchId;
        }
    }

    /**
     * Internal signal used to roll back the local transaction while reporting a {@code false}
     * two-phase result (equivalent to Spring's {@code status.setRollbackOnly()} + return false).
     */
    private static class FenceRollbackSignal extends RuntimeException {
        FenceRollbackSignal() {
            super(null, null, false, false);
        }
    }
}
