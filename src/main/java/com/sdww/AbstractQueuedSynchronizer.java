
package com.sdww;

/**
 * 整个java.util.concurrent中最重要的AQS，该类提供了一种用于构建同步器的Abstract类，用户只需要通过继承protect方法就能方便的开发出自己所需的同步工具
 * 重点：
 * 1.该类中有一个原子更新的int 域，该域被称为state，是整个AQS的状态指示器
 * 2.使用该类的正确方式是继承+组合。通过继承完善所需的同步模型，通过组合得到完整的同步工具类
 * 3.该类一般有两种使用方式：独占或共享。这里的关键在于，如果一个类既实现了独占又实现了共享，两种模式下的FIFO是同一个！
 * 4.要使用ConditionObject需要满足以下条件：
 *  a.该类必须支持独占模式
 *  b.该类的isHeldExclusively方法必须准确反映是否为独占锁
 *  c.release需要释放getStatus的全部，acquire返回当前state状态且必须保存之前的state状态值
 */
public class AbstractQueuedSynchronizer {

    class Node {
        /**
         * Status field, taking on only the values:
         *   SIGNAL:     The successor of this node is (or will soon be)
         *               blocked (via park), so the current node must
         *               unpark its successor when it releases or
         *               cancels. To avoid races, acquire methods must
         *               first indicate they need a signal,
         *               then retry the atomic acquire, and then,
         *               on failure, block.
         *   CANCELLED:  This node is cancelled due to timeout or interrupt.
         *               Nodes never leave this state. In particular,
         *               a thread with cancelled node never again blocks.
         *   CONDITION:  This node is currently on a condition queue.
         *               It will not be used as a sync queue node
         *               until transferred, at which time the status
         *               will be set to 0. (Use of this value here has
         *               nothing to do with the other uses of the
         *               field, but simplifies mechanics.)
         *   PROPAGATE:  A releaseShared should be propagated to other
         *               nodes. This is set (for head node only) in
         *               doReleaseShared to ensure propagation
         *               continues, even if other operations have
         *               since intervened.
         *   0:          None of the above
         *
         * The values are arranged numerically to simplify use.
         * Non-negative values mean that a node doesn't need to
         * signal. So, most code doesn't need to check for particular
         * values, just for sign.
         *
         * The field is initialized to 0 for normal sync nodes, and
         * CONDITION for condition nodes.  It is modified using CAS
         * (or when possible, unconditional volatile writes).
         */
        volatile int waitStatus;
    }

    /**
     * Head of the wait queue, lazily initialized.  Except for
     * initialization, it is modified only via method setHead.  Note:
     * If head exists, its waitStatus is guaranteed not to be
     * CANCELLED.
     *
     * 等待队列头
     */
    private transient volatile Node head;

    /**
     * Tail of the wait queue, lazily initialized.  Modified only via
     * method enq to add new wait node.
     *
     * 等待队列尾
     */
    private transient volatile Node tail;

    /**
     * The synchronization state.
     */
    private volatile int state;

    /**
     * Acquires in exclusive mode, ignoring interrupts.  Implemented
     * by invoking at least once {@link #tryAcquire},
     * returning on success.  Otherwise the thread is queued, possibly
     * repeatedly blocking and unblocking, invoking {@link
     * #tryAcquire} until success.  This method can be used
     * to implement method {@link Lock#lock}
     * 核心方法之一：用于获取独占锁，流程为：
     * 1. 尝试获取资源(tryAcquire)
     * 2. 添加一个node到等待队列中
     * 3. 在这个node上等待
     *
     * @param arg the acquire argument.  This value is conveyed to
     *        {@link #tryAcquire} but is otherwise uninterpreted and
     *        can represent anything you like.
     */
    public final void acquire(int arg) {
        if (!tryAcquire(arg) &&
                acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }

    /**
     * Creates and enqueues node for current thread and given mode.
     * 为当前线程创建一个等待node，并添加到队列中去
     *
     * @param mode Node.EXCLUSIVE for exclusive, Node.SHARED for shared
     * @return the new node
     */
    private Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode);
        // Try the fast path of enq; backup to full enq on failure
        Node pred = tail;
        if (pred != null) {
            node.prev = pred;
            //尝试使用CAS将node直接放到队列尾部
            if (compareAndSetTail(pred, node)) {
                pred.next = node;
                return node;
            }
        }
        enq(node);
        return node;
    }

    /**
     * Inserts node into queue, initializing if necessary. See picture above.
     * @param node the node to insert
     * @return node's predecessor
     */
    private Node enq(final Node node) {
        for (;;) {
            Node t = tail;
            //队列还未初始化
            if (t == null) { // Must initialize
                if (compareAndSetHead(new Node()))
                    tail = head;
            } else {
                //循环尝试将node放在队列尾部
                node.prev = t;
                if (compareAndSetTail(t, node)) {
                    t.next = node;
                    return t;
                }
            }
        }
    }

    /**
     * Acquires in exclusive uninterruptible mode for thread already in
     * queue. Used by condition wait methods as well as acquire.
     *
     * @param node the node
     * @param arg the acquire argument
     * @return {@code true} if interrupted while waiting
     */
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                //prev
                final Node p = node.predecessor();
                /**
                 * 当p == head时，此时可能存在两种情况：
                 * 1. p仍处于运行状态，此时tryAcquire将为false
                 * 2. p已经运行完毕，此时tryAcquire为true
                 */
                if (p == head && tryAcquire(arg)) {
                    //node成功获取到锁，将当前node设置为head
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return interrupted;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Checks and updates status for a node that failed to acquire.
     * Returns true if thread should block. This is the main signal
     * control in all acquire loops.  Requires that pred == node.prev.
     *
     * 重要方法：这里需要注意的是，每个线程由此决定它pred.waitStatus
     * 如果当前线程需要阻塞，则将pred.waitStatus置为SIGNAL状态
     * 该特性是由CLH 数据结构决定的
     *
     * @param pred node's predecessor holding status
     * @param node the node
     * @return {@code true} if thread should block
     */
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus;
        if (ws == Node.SIGNAL)
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             */
            return true;
        //只有cacelled一种情况
        if (ws > 0) {
            /*
             * Predecessor was cancelled. Skip over predecessors and
             * indicate retry.
             */
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);
            pred.next = node;
        } else {
            /*
             * waitStatus must be 0 or PROPAGATE.  Indicate that we
             * need a signal, but don't park yet.  Caller will need to
             * retry to make sure it cannot acquire before parking.
             * 在独占模式下，pred.status == 0，说明pred为head，当前线程不需要park
             */
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }

    /**
     * Acquires in exclusive interruptible mode.
     * 与acquireQueued差不多，只是把addWaiter方法纳入了该方法内部
     * 与普通 acquire不同仅在于遇到interrupted时将抛出错误，而不是返回一个标志位
     * @param arg the acquire argument
     */
    private void doAcquireInterruptibly(int arg)
            throws InterruptedException {
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return;
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Cancels an ongoing attempt to acquire.
     *
     * @param node the node
     */
    private void cancelAcquire(Node node) {
        // Ignore if node doesn't exist
        if (node == null)
            return;

        //step1.将node.thread置为null,因为该项无意义了
        node.thread = null;

        // Skip cancelled predecessors
        // 这里的隐含逻辑为去掉Queue中该node之前所有waitStatus为cancelled的node
        Node pred = node.prev;
        // 向前查找到queue中第一个waitStatus不为cancelled的node，标记为pred
        while (pred.waitStatus > 0)
            node.prev = pred = pred.prev;

        // predNext is the apparent node to unsplice. CASes below will
        // fail if not, in which case, we lost race vs another cancel
        // or signal, so no further action is necessary.
        // predNext只会在后面的CAS中当做expect使用，这里拿到也仅为这一个目的
        Node predNext = pred.next;

        // Can use unconditional write instead of CAS here.
        // After this atomic step, other Nodes can skip past us.
        // Before, we are free of interference from other threads.
        // 当waitStatus被置为CANCELLED后，后面的node向前查找时将跳过该node，而且在某些情况下将会把该node从queue中清除出去
        node.waitStatus = Node.CANCELLED;

        // If we are the tail, remove ourselves.
        // node == tail时，清理
        if (node == tail && compareAndSetTail(node, pred)) {
            compareAndSetNext(pred, predNext, null);
        } else {
            // If successor needs signal, try to set pred's next-link
            // so it will get one. Otherwise wake it up to propagate.
            int ws;
            if (pred != head &&
                    // pred.waitStatus必须为SIGNAL状态
                    ((ws = pred.waitStatus) == Node.SIGNAL ||
                            // 如果pred.waitStatus为其他等待状态，也要用CAS将其状态置换为SIGNAL状态
                            (ws <= 0 && compareAndSetWaitStatus(pred, ws, Node.SIGNAL))) &&
                    pred.thread != null) {

                // 将node从queue中移除
                // pred -> node -> next 变为：
                // pred -> next
                Node next = node.next;
                if (next != null && next.waitStatus <= 0)
                    compareAndSetNext(pred, predNext, next);
            } else {
                unparkSuccessor(node);
            }

            node.next = node; // help GC
        }
    }

    /**
     * Acquires in exclusive timed mode.
     *
     * @param arg the acquire argument
     * @param nanosTimeout max wait time
     * @return {@code true} if acquired
     */
    private boolean doAcquireNanos(int arg, long nanosTimeout)
            throws InterruptedException {
        if (nanosTimeout <= 0L)
            return false;
        final long deadline = System.nanoTime() + nanosTimeout;
        final Node node = addWaiter(Node.EXCLUSIVE);
        boolean failed = true;
        try {
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);
                    p.next = null; // help GC
                    failed = false;
                    return true;
                }
                nanosTimeout = deadline - System.nanoTime();
                if (nanosTimeout <= 0L)
                    return false;
                if (shouldParkAfterFailedAcquire(p, node) &&
                        nanosTimeout > spinForTimeoutThreshold)
                    LockSupport.parkNanos(this, nanosTimeout);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Releases in exclusive mode.  Implemented by unblocking one or
     * more threads if {@link #tryRelease} returns true.
     * This method can be used to implement method {@link Lock#unlock}.
     * 对于release来说就简单多了，只需要将head唤醒即可
     *
     * @param arg the release argument.  This value is conveyed to
     *        {@link #tryRelease} but is otherwise uninterpreted and
     *        can represent anything you like.
     * @return the value returned from {@link #tryRelease}
     */
    public final boolean release(int arg) {
        if (tryRelease(arg)) {
            Node h = head;
            if (h != null && h.waitStatus != 0)
                unparkSuccessor(h);
            return true;
        }
        return false;
    }

    /**
     * Wakes up node's successor, if one exists.
     *
     * @param node the node
     */
    private void unparkSuccessor(Node node) {
        /*
         * If status is negative (i.e., possibly needing signal) try
         * to clear in anticipation of signalling.  It is OK if this
         * fails or if status is changed by waiting thread.
         *
         * ws < 0说明后一个节点的状态为等待：每一个node的status保存的都是后一个节点的等待状态
         */
        int ws = node.waitStatus;
        if (ws < 0)
            compareAndSetWaitStatus(node, ws, 0);

        /*
         * Thread to unpark is held in successor, which is normally
         * just the next node.  But if cancelled or apparently null,
         * traverse backwards from tail to find the actual
         * non-cancelled successor.
         *
         * 从后往前清理为null以及waitStatus为cancelled的node
         * s指向的是后续第一个不为cancelled状态node的thread
         *
         */
        Node s = node.next;
        if (s == null || s.waitStatus > 0) {
            s = null;
            for (Node t = tail; t != null && t != node; t = t.prev)
                if (t.waitStatus <= 0)
                    s = t;
        }
        if (s != null)
            LockSupport.unpark(s.thread);
    }

    /**
     * Attempts to acquire in shared mode. This method should query if
     * the state of the object permits it to be acquired in the shared
     * mode, and if so to acquire it.
     *
     * <p>This method is always invoked by the thread performing
     * acquire.  If this method reports failure, the acquire method
     * may queue the thread, if it is not already queued, until it is
     * signalled by a release from some other thread.
     *
     * <p>The default implementation throws {@link
     * UnsupportedOperationException}.
     *
     * @param arg the acquire argument. This value is always the one
     *        passed to an acquire method, or is the value saved on entry
     *        to a condition wait.  The value is otherwise uninterpreted
     *        and can represent anything you like.
     * @return a negative value on failure; zero if acquisition in shared
     *         mode succeeded but no subsequent shared-mode acquire can
     *         succeed; and a positive value if acquisition in shared
     *         mode succeeded and subsequent shared-mode acquires might
     *         also succeed, in which case a subsequent waiting thread
     *         must check availability. (Support for three different
     *         return values enables this method to be used in contexts
     *         where acquires only sometimes act exclusively.)  Upon
     *         success, this object has been acquired.
     *
     *         这里的返回值有三种：
     *         1.小于0，说明请求失败
     *         2.等于0，当前成功，后续将失败
     *         3.大于0，当前及后续都将成功
     * @throws IllegalMonitorStateException if acquiring would place this
     *         synchronizer in an illegal state. This exception must be
     *         thrown in a consistent fashion for synchronization to work
     *         correctly.
     * @throws UnsupportedOperationException if shared mode is not supported
     */
    protected int tryAcquireShared(int arg) {
        throw new UnsupportedOperationException();
    }

    /**
     * Acquires in shared uninterruptible mode.
     * @param arg the acquire argument
     */
    private void doAcquireShared(int arg) {
        final Node node = addWaiter(Node.SHARED);
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();
                if (p == head) {
                    int r = tryAcquireShared(arg);
                    //当前获取成功了
                    if (r >= 0) {
                        setHeadAndPropagate(node, r);
                        p.next = null; // help GC
                        if (interrupted)
                            selfInterrupt();
                        failed = false;
                        return;
                    }
                }
                if (shouldParkAfterFailedAcquire(p, node) &&
                        parkAndCheckInterrupt())
                    interrupted = true;
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }

    /**
     * Sets head of queue, and checks if successor may be waiting
     * in shared mode, if so propagating if either propagate > 0 or
     * PROPAGATE status was set.
     *
     * 只有doAcquireShared、doAcquireSharedInterruptibly、doAcquireSharedNanos方法中会调用该方法
     * 该方法被调用的前提是propagate >= 0, 也就是说此时该线程尝试获取share资源成功了，那么可能会成功的不止这一个，因此需要将
     * 这个信息传播下去
     *
     * 该方法为shared mode的核心方法，也就是说，当处于shared模式下的线程被唤醒后，它还会检查synQueue中后续Node是否需要唤醒：
     * 当后续的节点为shared模式，且处于需要等待唤醒的状态时（Node.statue为SIGNAL 或 PROPAGATE），程序将进行进一步唤醒（虽然不一定能够唤醒成功）
     *
     * @param node the node
     * @param propagate the return value from a tryAcquireShared
     */
    private void setHeadAndPropagate(Node node, int propagate) {
        Node h = head; // Record old head for check below
        //进入该方法的前提含有node.prev == head，而且当前线程被唤醒了，说明old head仅用于判断
        //由于当前线程tryAccquireShared成功了，为了保证逻辑继续执行，所以将该node放到head处
        setHead(node);
        /*
         * Try to signal next queued node if:
         *   Propagation was indicated by caller,
         *     or was recorded (as h.waitStatus either before
         *     or after setHead) by a previous operation
         *     (note: this uses sign-check of waitStatus because
         *      PROPAGATE status may transition to SIGNAL.)
         * and
         *   The next node is waiting in shared mode,
         *     or we don't know, because it appears null
         *
         * The conservatism in both of these checks may cause
         * unnecessary wake-ups, but only when there are multiple
         * racing acquires/releases, so most need signals now or soon
         * anyway.
         */
        // 此处的propagate 隐含条件是 >= 0的，如果propagate > 0，则说明shared资源还有很多，需要唤醒其他等待线程
        // 使用h.waitStatus < 0判断的原因是waitStatus有两种状态:SIGNAL || PROPAGATE
        // h == null说明 propagate == 0 && h == null || h.waitStatus < 0，即仍有线程等待在head node处，再加上需要判断后续的s.isShared()!
        // 后面的(h = head) == null || h.waitStatus 表示 setHead(node)方法后的head(不一定是node,因为还有可能其他线程也更换了head)
        if (propagate > 0 || h == null || h.waitStatus < 0 ||
                (h = head) == null || h.waitStatus < 0) {
            Node s = node.next;
            if (s == null || s.isShared())
                doReleaseShared();
        }
    }

    /**
     * Release action for shared mode -- signals successor and ensures
     * propagation. (Note: For exclusive mode, release just amounts
     * to calling unparkSuccessor of head if it needs signal.)
     */
    private void doReleaseShared() {
        /*
         * Ensure that a release propagates, even if there are other
         * in-progress acquires/releases.  This proceeds in the usual
         * way of trying to unparkSuccessor of head if it needs
         * signal. But if it does not, status is set to PROPAGATE to
         * ensure that upon release, propagation continues.
         * Additionally, we must loop in case a new node is added
         * while we are doing this. Also, unlike other uses of
         * unparkSuccessor, we need to know if CAS to reset status
         * fails, if so rechecking.
         */
        for (;;) {
            Node h = head;
            if (h != null && h != tail) {
                int ws = h.waitStatus;
                if (ws == Node.SIGNAL) {
                    if (!compareAndSetWaitStatus(h, Node.SIGNAL, 0))
                        continue;            // loop to recheck cases
                    unparkSuccessor(h);
                }
                //这一步的原因是可能其他线程刚刚把Head的SIGNAL改为了0，且还未来得及替换Head Node
                else if (ws == 0 &&
                        !compareAndSetWaitStatus(h, 0, Node.PROPAGATE))
                    continue;                // loop on failed CAS
            }
            if (h == head)                   // loop if head changed，head changed的条件是新的node被加入head处
                break;
        }
    }

    /**
     * Condition是用来提供与Object Monitor类似同步语义的工具，其语义行为与object.wait();object.notify();object.notifyAll()类似
     * 个人看法：由于synchronize关键字的不足（无法interrupt，控制力度不够等），所以java.util.concurrent提供了lock接口
     * object.wait(),object.notify方法又与synchronize关键字关系紧密，必须配合使用，所以针对实现了lock接口的类我们也需要相对应的wait,notify方法
     * 实现这些方法的结果就是Condition接口。
     * 通俗来说：Condition接口是用于与lock接口搭配使用的wait与notify
     *
     * 当然，Condition接口的实现方式由个人自己定义其行为。
     */
    public class ConditionObject implements Condition, java.io.Serializable {

        /** First node of condition queue. */
        private transient Node firstWaiter;
        /** Last node of condition queue. */
        private transient Node lastWaiter;

        /**
         * Creates a new {@code ConditionObject} instance.
         */
        public ConditionObject() { }

        /**
         * Implements interruptible condition wait.
         * <ol>
         * <li> If current thread is interrupted, throw InterruptedException.
         * <li> Save lock state returned by {@link #getState}.
         * <li> Invoke {@link #release} with saved state as argument,
         *      throwing IllegalMonitorStateException if it fails.
         * <li> Block until signalled or interrupted.
         * <li> Reacquire by invoking specialized version of
         *      {@link #acquire} with saved state as argument.
         * <li> If interrupted while blocked in step 4, throw InterruptedException.
         * </ol>
         *
         * 1.如果当前thread的interrupt标志位为真，则抛出Interrupted异常
         * ---隐含条件：当前线程已经拿到了对应lock的锁资源（其实就是ASQ的state）
         * 2.记录当前线程所获取锁资源的状态
         * 3.调用release方法释放当前获取的锁资源
         * 4.将当前线程包装为Node放入Condition队列中，并block等待同步条件
         * 5.满足等待条件后，重新尝试获取之前的锁状态（将node放入sync队列中）
         * 6.如果在blocking的状态中被interrupted，则抛出Interrupted异常
         */
        public final void await() throws InterruptedException {
            //上述step1
            if (Thread.interrupted())
                throw new InterruptedException();

            //上述step2 & step3
            Node node = addConditionWaiter();
            //释放lock资源的方法，只需注意其遇到exception只会将node置为CANCELLED，不会抛出错误
            int savedState = fullyRelease(node);
            int interruptMode = 0;

            //!isOnSyncQueue(node) 代表着该节点未被移入sync队列，也就是说未获取到同步条件
            //在这种情况下，线程应blocking并等待同步条件
            while (!isOnSyncQueue(node)) {
                LockSupport.park(this);
                if ((interruptMode = checkInterruptWhileWaiting(node)) != 0)
                    break;
            }
            //acquireQueued为真代表发生了Interrupt
            if (acquireQueued(node, savedState) && interruptMode != THROW_IE)
                interruptMode = REINTERRUPT;

            //从firstWaiter开始，遍历清除所有cancelled的node
            if (node.nextWaiter != null) // clean up if cancelled
                unlinkCancelledWaiters();

            //根据interruptMode标志位决定如何处理：抛出异常还是调用Thread.currentThread().interrupt()
            if (interruptMode != 0)
                reportInterruptAfterWait(interruptMode);
        }

        /**
         * Adds a new waiter to wait queue.
         * 添加一个Condition node到lastWaiter处
         * 请注意的是，从该方法可以看出，每一个Condition对象都有自己的lastWaiter与firstWaiter
         * 也就是说每个Condition对象都有自己的Condition Queue
         * 调用此方法时未释放lock资源，不用考虑同步问题
         * @return its new wait node
         */
        private Node addConditionWaiter() {
            Node t = lastWaiter;
            // If lastWaiter is cancelled, clean out.
            if (t != null && t.waitStatus != Node.CONDITION) {
                unlinkCancelledWaiters();
                t = lastWaiter;
            }
            Node node = new Node(Thread.currentThread(), Node.CONDITION);
            if (t == null)
                firstWaiter = node;
            else
                t.nextWaiter = node;
            lastWaiter = node;
            return node;
        }

        /**
         * Transfers node, if necessary, to sync queue after a cancelled wait.
         * Returns true if thread was cancelled before being signalled.
         *
         * 判断该以何种方式处理Interrupt
         * 1.如果该node的状态为CONDITION，则返回的是抛出Interrupt异常
         * 2.如果该node的状态为其他，selfInterrupt
         *
         * @param node the node
         * @return true if cancelled before the node was signalled
         */
        final boolean transferAfterCancelledWait(Node node) {
            if (compareAndSetWaitStatus(node, Node.CONDITION, 0)) {
                enq(node);
                return true;
            }
            /*
             * If we lost out to a signal(), then we can't proceed
             * until it finishes its enq().  Cancelling during an
             * incomplete transfer is both rare and transient, so just
             * spin.
             * 等待其他线程操作，多个线程操作同一个node只会有一个抛出Interrupt EX
             */
            while (!isOnSyncQueue(node))
                Thread.yield();
            return false;
        }

        /**
         * Removes and transfers nodes until hit non-cancelled one or
         * null. Split out from signal in part to encourage compilers
         * to inline the case of no waiters.
         *
         * 对于condition的signal来说，就是将node从condition queue转移到sync queue中
         * @param first (non-null) the first node on condition queue
         */
        private void doSignal(Node first) {
            do {
                //firstWaiter = first.nextWaiter,即将firstNode从Condition Queue中移除
                if ( (firstWaiter = first.nextWaiter) == null)
                    lastWaiter = null;
                //help GC
                first.nextWaiter = null;
            } while (!transferForSignal(first) &&
                    (first = firstWaiter) != null);
        }

        /**
         * Transfers a node from a condition queue onto sync queue.
         * Returns true if successful.
         * 将node从condition队列移除，放入到sync队列中
         * @param node the node
         * @return true if successfully transferred (else the node was
         * cancelled before signal)
         */
        final boolean transferForSignal(Node node) {
            /*
             * If cannot change waitStatus, the node has been cancelled.
             */
            //修改ws状态
            if (!compareAndSetWaitStatus(node, Node.CONDITION, 0))
                return false;

            /*
             * Splice onto queue and try to set waitStatus of predecessor to
             * indicate that thread is (probably) waiting. If cancelled or
             * attempt to set waitStatus fails, wake up to resync (in which
             * case the waitStatus can be transiently and harmlessly wrong).
             */
            // 将node放入sync队列中
            Node p = enq(node);
            int ws = p.waitStatus;
            // 如果pred为cancelled或无法置为SIGNAL状态，则唤醒node.thread，让node.thread继续执行acquireQueued逻辑
            if (ws > 0 || !compareAndSetWaitStatus(p, ws, Node.SIGNAL))
                LockSupport.unpark(node.thread);
            return true;
        }

        /**
         * Removes and transfers all nodes.
         * 该方法与singal差不多，只是唤醒所有的node而已
         * @param first (non-null) the first node on condition queue
         */
        private void doSignalAll(Node first) {
            lastWaiter = firstWaiter = null;
            do {
                Node next = first.nextWaiter;
                first.nextWaiter = null;
                transferForSignal(first);
                first = next;
            } while (first != null);
        }
    }

}