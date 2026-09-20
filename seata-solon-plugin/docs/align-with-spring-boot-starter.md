# seata-solon-plugin 与 seata-spring-boot-starter 的功能对齐说明

本文件记录 `seata-solon-plugin` 向 `seata-spring-boot-starter`（及其依赖的
`seata-spring-autoconfigure-client/core/server`）对齐的范围、实现方式与边界。

对齐目标是「功能体验等价 + 配置键一致」，而非照搬 Spring 的 Bean 装配模型：
Solon 与 Spring 的 IoC/AOP 机制不同，Solon 采用 `SeataPlugin`（Plugin 生命周期）
+ `GlobalTransactionalInterceptor`（注解拦截）+ `SolonConfigurationProvider`
（`ExtConfigurationProvider` 配置桥接）实现等价能力。

## 一、已对齐能力

### 1. JSON 序列化与反序列化安全策略
对齐 Spring 的 `SeataJsonProperties`。

- 新增 `org.apache.seata.solon.autoconfigure.properties.SeataJsonProperties`，
  字段 `serializerType`（`seata.json.serializer-type`）与 `allowlist`
  （`seata.json.allowlist`）。
- 用 Solon 的 `@Init` 复刻 Spring 的 `@PostConstruct`，初始化时调用
  `JsonAllowlistManager.getInstance().loadUserAllowlist(allowlist)`，为反序列化
  提供白名单安全兜底。
- `StarterConstants` 补 `JSON_PREFIX`；`PropertiesHelper` 注册
  `JSON_PREFIX -> SeataJsonProperties`。

### 2. 常量与属性补齐
- `StarterConstants` 补 `JSON_PREFIX`、`REGISTRY_METADATA_PREFIX`、
  `REGISTRY_IGNORED_INTERFACES`。
- 修正拼写 `REGISTRY_PREFERED_NETWORKS` → `REGISTRY_PREFERRED_NETWORKS`，
  旧名保留并标注 `@Deprecated` 以兼容外部引用。
- 新增 `RegistryMetadataProperties`，并注册
  `REGISTRY_METADATA_PREFIX -> RegistryMetadataProperties`。

### 3. TCC Fence（防悬挂）—— Solon 原生实现
对齐 Spring 的 `SeataSpringFenceAutoConfiguration` + `SpringFenceConfig` +
`SpringFenceHandler`，但**不引入任何 Spring 依赖**。

关键点：Spring 版通过 `TransactionTemplate` + `DataSourceUtils.getConnection`
保证「fence 表操作与业务二阶段方法共享同一本地事务连接、原子提交」。Solon 版用
solon-data 的编程式事务 API 等价复现：

| Spring | Solon 原生等价 |
| --- | --- |
| `TransactionTemplate.execute(...)` | `TranUtils.execute(meta, runnable)` |
| `DataSourceUtils.getConnection(ds)` | `TranUtils.getConnection(ds)`（复用事务绑定连接） |
| `@Transactional` 读隔离级别 | `@Transaction` / `BusinessActionContext` 的 `TX_ISOLATION` |
| `status.setRollbackOnly()` + 返回 false | 抛内部 `FenceRollbackSignal` 触发回滚，caller 还原 false |

新增文件：
- `tcc/fence/SolonFenceHandler`：实现 `FenceHandler` SPI，静态块
  `DefaultCommonFenceHandler.get().setFenceHandler(new SolonFenceHandler())`。
- `tcc/fence/TransactionMeta`：`@Transaction` 注解的编程式实例，用于动态构造
  事务元数据（policy/isolation/readOnly）。
- `tcc/fence/SolonFenceConfig`：继承 `CommonFenceConfig`，构造即注入
  （被代理的）DataSource；实例存入 `ObjectHolder`（`BEAN_NAME_SPRING_FENCE_CONFIG`）。
- `autoconfigure/properties/SeataFenceProperties`：绑定 `seata.tcc.fence`
  （`log-table-name`、`clean-period`）。

装配时序：`SeataPlugin` 在 `subWrapsOfType(DataSource)` 完成代理后，用
`getBeanAsync(DataSource.class)` 拿到**被代理的** DataSource 构建
`SolonFenceConfig`，确保 fence 记录与业务操作走同一连接。`CommonFenceConfig.init()`
由框架无关的 `TccActionInterceptorHandler` 在首次遇到 `useTCCFence()=true` 的 TCC
方法时懒触发，无需插件主动调用。

`clean-period` 支持 ISO-8601（`PT1H`/`P7D`）与简单形式（`7d`/`2h`/`30m`/`45s`/
`500ms`/裸数字按天），由 `SeataPlugin.parseCleanPeriod` 解析。

回滚信号传播链已静态核实：`FenceRollbackSignal` 构造为 `super(null, null, false,
false)`（无 message/cause、不写栈），而 solon `Utils.throwableUnwrap` 只剥
`InvocationTargetException`、`UndeclaredThrowableException` 和「恰好是裸 `RuntimeException`
且带 cause」三种壳——自定义子类不命中任何分支，`DbTran.execute()` →
`throwableUnwrap` → `TranManager.with`（`throws X` 原样透传）全程原样穿透，
caller 的 `catch (FenceRollbackSignal)` 可靠捕获。

### 4. 数据源代理排除（seata.excludes-for-auto-proxying）
对齐 Spring `SeataAutoDataSourceProxyCreator.shouldSkip`：按**全限定类名**匹配排除。
Solon 版 `SeataAutoDataSourceProxyCreator` 新增 `excludes` 构造参数，
`getProxy` 中对命中的 DataSource 跳过代理、原样返回；`SeataPlugin` 装配时从
`SeataProperties.getExcludesForAutoProxying()` 接线。旧的单参构造器保留兼容。

配套说明：`SeataProperties.scanPackages`/`excludesForScanning` 在 Solon 注解拦截器
模型下无扫描器概念，保留仅为配置兼容（注释已标明，不参与装配）。

## 二、暂不支持的能力

### Saga 状态机（建议使用 seata-spring-boot-starter）
经核实，Saga 的 Spring 依赖是**结构性**的，无法在不引入 Spring 的前提下对齐：

- 扩展点本身可插拔：`ServiceInvokerManager.putServiceInvoker`、
  `ExpressionFactoryManager.putExpressionFactory` 均为 Map 注册；
  `seata-saga-engine` 的 `AbstractStateMachineConfig` 也是 Spring-free 的。
- **但**：`AbstractStateMachineConfig.init()` 默认只注册 `SEQUENCE`/`EXCEPTION`
  两种表达式工厂，缺少状态机条件判断所必需的 `Default` 表达式引擎——该引擎在
  Spring 版是 `SpringELExpressionFactory`（SpEL）。同时
  `SpringBeanServiceInvoker`（靠 `ApplicationContext` 查 bean）、
  `DefaultSagaTransactionalTemplate`（`ApplicationContextAware`）、
  `ResourceUtil`（Spring `Resource` 加载状态机 JSON）都焊死在 Spring 上。

要在 Solon 下原生支持 Saga，需自备三样：① 非 SpEL 的默认表达式引擎（需选型
OGNL/Aviator/JSR-223）；② Solon 版 `ServiceInvoker`（从 Solon 容器查 bean）；
③ 非 Spring 的 `SagaTransactionalTemplate`。这属于独立子项目，建议按真实需求另行
立项。当前需要 Saga 的用户请使用 `seata-spring-boot-starter`。

## 三、验证说明

已验证通过（JDK 8 + Maven 3.8.8，solon 3.2.0 基线）：

- `mvn -f seata-solon-plugin/pom.xml compile` —— BUILD SUCCESS。
- `mvn -f seata-solon-plugin/pom.xml test` —— **31 个测试全部通过**，含新增的
  `SeataJsonPropertiesTest`、`RegistryMetadataPropertiesTest`、
  `SeataFencePropertiesTest`、`TransactionMetaTest`、`SeataPluginParseCleanPeriodTest`、
  `SeataAutoDataSourceProxyCreatorTest`。
- 验证过程中顺带修复两处基线问题：① `SolonConfigurationProvider` 误用
  `org.apache.commons.lang.StringUtils`（commons-lang 2.x 不在 classpath），改为等价的
  `commons-lang3.StringUtils`；② 本仓未提交的 `solon.version` 4.0.5 冒进已回退到
  基线 3.2.0（4.0.5 缺 `solon-boot-jdkhttp` 且 API 不兼容）。
- 注：本仓根 pom 的 `seata-spring-boot-starter`/`seata-spring-autoconfigure` 模块
  路径指向上游旧布局（实际在 `spring/` 子目录），从根目录 reactor 构建会失败；
  插件可独立验证（需先逐模块 install 依赖闭包，顺序：build → bom →
  dependencies → common/core/config/discovery/sqlparser/threadpool/json-common/
  serializer/compressor/rm/rm-datasource/integration-tx-api/tcc/tm）。
- Fence 的完整链路（真实 DB + TCC 二阶段）需在集成环境验证，纯逻辑与注解实现
  已由单测覆盖。

