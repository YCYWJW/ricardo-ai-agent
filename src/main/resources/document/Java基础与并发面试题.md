# Java 基础与并发面试题

#### 说一下 JVM 的内存区域划分？
JVM 内存分线程共享和线程私有两部分。线程共享：堆存放对象实例与数组，是 GC 主战场，又分新生代（Eden + 两个 Survivor）与老年代；方法区（JDK8 起叫元空间，用本地内存）存放类元信息、常量、静态变量。线程私有：虚拟机栈（每个方法一个栈帧）、本地方法栈、程序计数器。虚拟机栈过深会抛 StackOverflowError。

#### 什么是类加载机制？双亲委派模型是什么？
类加载器把 .class 字节流加载进内存生成 Class 对象，经历加载、验证、准备、解析、初始化五阶段。双亲委派要求收到加载请求先委派给父加载器，父无法完成才自己加载，避免核心类（如 java.lang.String）被篡改，保证类的唯一性。路径：Bootstrap → Platform → Application。

#### 说一下常见 GC 算法和垃圾回收器？
判定垃圾用「可达性分析」，从 GC Roots 出发不可达即标记回收。算法：标记-清除（有碎片）、标记-复制（新生代用，快但费空间）、标记-整理（老年代用，无碎片但慢）。回收器：G1 主流，把堆分成大小相等的 Region，优先回收垃圾最多区域、可控停顿；ZGC/Shenandoah 追求亚毫秒级停顿；吞吐优先可考虑 Parallel。

#### 线上 Java 应用频繁 Full GC，怎么排查？
先看监控确认是堆不足还是内存泄漏。用 jstat 看 GC 频率与堆使用趋势，jmap 生成堆转储，再用 MAT/VisualVM 分析大对象和 GC Roots 引用链。常见原因：大对象直接进老年代、static 集合或线程缓存持续增长泄漏、堆参数偏小。定位后调 JVM 参数或修泄漏点。

#### ArrayList 和 LinkedList 的区别与选型？
ArrayList 底层动态数组，随机访问 O(1)，尾部插入摊销 O(1)，中间插入删除 O(n)，扩容 1.5 倍增；LinkedList 底层双向链表，随机访问 O(n)，头尾增删 O(1)。「读多写少、按下标」用 ArrayList（实际更常用），「频繁头尾增删」才考虑 LinkedList。

#### HashMap 的底层原理？JDK8 做了哪些优化？
HashMap 是「数组 + 链表/红黑树」。存值时先 hash 定位数组下标，冲突挂链表（尾插）。JDK8 优化：链表≥8 且容量≥64 转红黑树（查询 O(n)→O(logn)）；扩容翻倍、元素在原位或原位置+旧容量；插入改尾插避免并发扩容死循环。默认负载因子 0.75。

#### ConcurrentHashMap 如何保证线程安全？
JDK8 采用「CAS + synchronized」：目标桶为空用 CAS 无锁插入，非空则锁住该桶头节点再操作，锁粒度细到单桶；get 不加锁，value 用 volatile 保证可见性；扩容支持多线程分段迁移。相比 Hashtable 全表锁，并发度大幅提升。

#### Java 线程有哪些状态？如何流转？
6 个状态：NEW、RUNNABLE（含就绪/运行）、BLOCKED（等锁）、WAITING（无限等待如 wait/join）、TIMED_WAITING（限时等待如 sleep）、TERMINATED。流转：NEW → start() → RUNNABLE；抢锁失败 → BLOCKED，拿锁回 RUNNABLE；wait → WAITING，被 notify/超时回 RUNNABLE；执行完 → TERMINATED。

#### synchronized 和 ReentrantLock 的区别？怎么选？
synchronized 是 JVM 层隐式锁，自动加解锁，JDK6 后有偏向/轻量/重量锁升级；ReentrantLock 是 JDK 层显式锁，需手动 lock/unlock 配合 finally，但支持可中断、可限时 tryLock、公平锁、Condition 精确唤醒。简单场景 synchronized 够用省心，需要公平/可中断/多条件时选 ReentrantLock。

#### volatile 的作用和原理？
保证可见性和有序性，不保证原子性。可见性靠内存屏障 + 缓存一致协议，写后刷新主存、读从主存读；有序性通过禁止指令重排实现，常用于双重检查锁单例。注意 i++ 等复合操作它救不了，仍需加锁或用原子类。

#### ThreadLocal 的原理？使用时注意什么？
给每个线程一份变量副本，本质是 Thread 内部有 ThreadLocalMap，以 ThreadLocal 为 key 存值，常用于保存线程上下文（用户会话、连接、链路 id）。坑：key 是弱引用但 value 强引用，线程池复用线程导致 value 无法释放 → 内存泄漏，用完必须 remove()。

#### 线程池核心参数？提交任务的执行流程？
核心参数：corePoolSize、maximumPoolSize、keepAliveTime+unit、workQueue、threadFactory、拒绝策略。流程：先看是否达核心线程数（不够新建）；够了入队（队列没满排队）；队列满了看是否达最大线程数（不够新建）；全满走拒绝策略（AbortPolicy 抛异常 / CallerRunsPolicy 提交线程执行）。