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
}
