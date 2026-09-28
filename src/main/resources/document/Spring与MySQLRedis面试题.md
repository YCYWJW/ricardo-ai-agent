# Spring 与 MySQL / Redis 面试题

#### 什么是 IOC 和 AOP？各自解决什么问题？
IOC 把对象创建与依赖管理交给 Spring 容器，用注解注入解耦依赖，解决「谁创建谁、谁依赖谁」的硬编码。AOP 把日志、事务、鉴权等横切关注点从业务剥离，在方法前后织入增强，解决重复代码散落问题。二者都基于动态代理实现。

#### Spring Bean 的生命周期？
实例化 → 属性注入 → Aware 接口回调 → BeanPostProcessor 前置处理 → 初始化（@PostConstruct / afterPropertiesSet）→ BeanPostProcessor 后置处理（AOP 代理在这生成）→ 使用 → 销毁（@PreDestroy / destroy）。

#### Spring 如何解决循环依赖？用的什么三级缓存？
用三级缓存解决单例 bean 的属性注入循环依赖：一级 singletonObjects 存成品、二级 earlySingletonObjects 存提前暴露的半成品、三级 singletonFactories 存 ObjectFactory。A 实例化后提前暴露到三级缓存，B 依赖 A 时拿到 A 半成品引用，B 完成再回填 A。注意：构造器注入和原型 bean 的循环依赖无法解决。

#### 哪些场景会导致 Spring 事务失效？
1）@Transactional 作用在非 public 方法；2）同类内部 this.method() 调用没走代理；3）异常被 try-catch 吞掉；4）抛 checked 异常默认不回滚（需指定 rollbackFor）；5）数据库引擎不支持事务（MyISAM）；6）final/static 方法被代理绕过；7）传播行为设置不当。

#### JDK 动态代理和 CGLIB 的区别？
JDK 动态代理基于接口，要求目标类实现接口，用 Proxy + InvocationHandler 生成代理；CGLIB 基于继承生成子类，不要求接口，但类和被代理方法不能是 final/static。Spring Boot 2.x 后默认统一用 CGLIB。

#### MySQL 索引为什么用 B+ 树？
B+ 树所有数据在叶子节点，非叶子只存索引键，一个节点能存更多键 → 树更矮、磁盘 IO 少；叶子节点连成有序链表，支持高效范围查询和排序。哈希不支持范围查询，红黑树树高随数据增长、IO 次数多，所以磁盘索引用 B+ 树。

#### MySQL 事务隔离级别？分别解决什么问题？
RU 读未提交（脏读、不可重复读、幻读都有）；RC 读已提交（解脏读，剩不可重复读/幻读）；RR 可重复读（MySQL 默认，解脏读+不可重复读，靠间隙锁基本解幻读）；Serializable 串行化（全解，性能最差）。核心是 MVCC + 锁共同作用。

#### MVCC 的原理是什么？
每行维护多版本，InnoDB 加隐藏列 DB_TRX_ID（最近修改事务 id）、DB_ROLL_PTR（回滚指针）、DB_ROW_ID，靠 undo log 保留旧版本。快照读时用 ReadView（记录活跃事务集合）判断行的版本可见性，实现不加锁读到一致快照，从而解不可重复读。

#### 一条 SQL 执行很慢，怎么排查优化？
先 EXPLAIN 看执行计划，关注 type（最好 range/ref/const，最坏 ALL 全表扫）、key（是否用索引）、rows、Extra（Using filesort/temporary）。优化：WHERE/ORDER BY/JOIN 列建索引、避免索引列函数运算、避免 SELECT *、大表 join 注意驱动表、深分页延迟关联、拆大事务归档历史数据。配合慢查询日志定位。

#### 缓存穿透、击穿、雪崩分别怎么解决？
穿透（查不存在的数据打穿 DB）：参数校验、布隆过滤器、空值短缓存。击穿（单热点 key 过期）：热点永不过期或逻辑过期、加互斥锁单线程回源。雪崩（大量 key 同时过期或 Redis 宕机）：过期时间加随机抖动、多级缓存、Redis 高可用、限流降级。

#### RDB 和 AOF 持久化有什么区别？
RDB 是快照，把某时刻全量数据序列化到磁盘，恢复快、文件小，但两次快照间宕机丢数据；AOF 是追加写命令日志，数据更安全、可配 always/everysec/no，但文件大恢复慢，需重写压缩。生产常用「AOF(everysec) 为主 + RDB 冷备」或 Redis 4.0 混合持久化。

#### 如何保证 DB 和 Redis 缓存一致性？
1）Cache Aside：先更 DB 再删缓存，读时未命中回源回填，配「延迟双删」降窗口期风险；2）先删缓存再更 DB（有旧缓存回填问题）；3）Canal/Binlog 监听 DB 变更异步刷新。双写真强一致做不到，实践中追求最终一致 + 超时过期 + 失败重试兜底。

#### Redis 如何实现分布式锁？有什么坑？
SET key value NX PX timeout 原子加锁（value 用唯一 token），Lua 脚本判断 token 再删除防误删。坑：超时要大于业务执行时间否则提前释放；主从异步复制下主宕机锁可能丢失，严格场景用 Redlock 或换 ZK；续期用 Redisson 看门狗。