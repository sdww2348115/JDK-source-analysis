package com.sdww;

/**
 * JDK1.8对于ConcurrentHashMap的实现做了一定修改，基本去掉了Segment相关内容，主要修改点如下：
 * 1.采用类似HashMap的实现，使用Node数组作为基本table，再通过給Node设置不同属性的方式判断节点可用状态，写锁的基本单元也以table中的node为准
 * 2.当遇到扩容的情况时，concurrentHashMap可并发处理，加快扩容所需时间，提升整体吞吐效率
 */
public class ConcurrentHashMap {

    /**
     * 1.值为-1: map正在初始化
     * 2.值<-1: map正在resize，参与resize的线程数量为该值的绝对值-1
     * 3.刚初始化完成，该值为0或者初始table的大小（一旦涉及put操作，立即以该值进行初始化操作）
     * 4.除此之外，即为下一次resize所需元素大小，相当于普通HashMap中的threshold
     */
    private transient volatile int sizeCtl;

    /**
     * concurrentHashMap的核心插入操作
     * @param key
     * @param value
     * @param onlyIfAbsent
     * @return
     */
    final V putVal(K key, V value, boolean onlyIfAbsent) {
        if (key == null || value == null) throw new NullPointerException();
        int hash = spread(key.hashCode());
        //用于记录table的hash槽位处元素个数，考虑是否将该槽位转换为tree
        int binCount = 0;
        //这里之所以要使用循环，是因为下方部分逻辑只会对数据结构进行改变，但不能真正将值插入到正确的位置处
        for (Node<K,V>[] tab = table;;) {
            Node<K,V> f; int n, i, fh;
            //如果concurrentHashMap未经过初始化，初始化之
            if (tab == null || (n = tab.length) == 0)
                tab = initTable();
            //将值放入"空"槽位中，只需一次CAS操作即可
            else if ((f = tabAt(tab, i = (n - 1) & hash)) == null) {
                if (casTabAt(tab, i, null,
                        new Node<K,V>(hash, key, value, null)))
                    break;                   // no lock when adding to empty bin
            }
            //检测到槽位状态为MOVED，则帮助并发执行transfer相关操作
            else if ((fh = f.hash) == MOVED)
                tab = helpTransfer(tab, f);
            //插入非"空"槽位真实逻辑
            else {
                V oldVal = null;
                //通过对table中槽位节点的synchronize来实现分段锁
                //从这里可以看出：经过JDK的优化之后，synchronized关键字的性能并不一定比AQS差，使用哪一个还需根据情况进行具体分析
                synchronized (f) {
                    //将值插入到链表中，唯一需要注意的是binCount记录了table对应位置Node链表中元素的个数
                    if (tabAt(tab, i) == f) {
                        if (fh >= 0) {
                            binCount = 1;
                            for (Node<K,V> e = f;; ++binCount) {
                                K ek;
                                if (e.hash == hash &&
                                        ((ek = e.key) == key ||
                                                (ek != null && key.equals(ek)))) {
                                    oldVal = e.val;
                                    if (!onlyIfAbsent)
                                        e.val = value;
                                    break;
                                }
                                Node<K,V> pred = e;
                                if ((e = e.next) == null) {
                                    pred.next = new Node<K,V>(hash, key,
                                            value, null);
                                    break;
                                }
                            }
                        }
                        //将值插入到tree中
                        else if (f instanceof TreeBin) {
                            Node<K,V> p;
                            binCount = 2;
                            if ((p = ((TreeBin<K,V>)f).putTreeVal(hash, key,
                                    value)) != null) {
                                oldVal = p.val;
                                if (!onlyIfAbsent)
                                    p.val = value;
                            }
                        }
                    }
                }
                //根据binCount的结果，判断是否需要将普通Node转为Tree Bin
                if (binCount != 0) {
                    if (binCount >= TREEIFY_THRESHOLD)
                        treeifyBin(tab, i);
                    if (oldVal != null)
                        return oldVal;
                    break;
                }
            }
        }
        //添加记录
        addCount(1L, binCount);
        return null;
    }

    /**
     * 将链表转化为红黑树的关键函数
     * @param tab
     * @param index
     */
    private final void treeifyBin(Node<K,V>[] tab, int index) {
        Node<K,V> b; int n, sc;
        if (tab != null) {
            //首先判断table大小，如果table太小的话先尝试通过扩容table的方式减少冲突
            if ((n = tab.length) < MIN_TREEIFY_CAPACITY)
                tryPresize(n << 1);
            //核心转换函数
            else if ((b = tabAt(tab, index)) != null && b.hash >= 0) {
                synchronized (b) {
                    if (tabAt(tab, index) == b) {
                        TreeNode<K,V> hd = null, tl = null;
                        for (Node<K,V> e = b; e != null; e = e.next) {
                            TreeNode<K,V> p =
                                    new TreeNode<K,V>(e.hash, e.key, e.val,
                                            null, null);
                            if ((p.prev = tl) == null)
                                hd = p;
                            else
                                tl.next = p;
                            tl = p;
                        }
                        //插入/转换等步骤实际执行函数
                        setTabAt(tab, index, new TreeBin<K,V>(hd));
                    }
                }
            }
        }
    }

    /**
     * 构建红黑树
     * @param b
     */
    TreeBin(TreeNode<K,V> b) {
        //TREEBIN为-2，代表该bin为一个TreeBin
        super(TREEBIN, null, null, null);
        this.first = b;
        TreeNode<K,V> r = null;
        //初始化所有的TreeNode，此时所有的TreeNode以链表的形式组织在一起，通过next指针连接
        for (TreeNode<K,V> x = b, next; x != null; x = next) {
            next = (TreeNode<K,V>)x.next;
            x.left = x.right = null;
            //初始化首节点
            if (r == null) {
                x.parent = null;
                x.red = false;
                r = x;
            }
            else {
                K k = x.key;
                int h = x.hash;
                Class<?> kc = null;
                for (TreeNode<K,V> p = r;;) {
                    int dir, ph;
                    K pk = p.key;
                    if ((ph = p.hash) > h)
                        dir = -1;
                    else if (ph < h)
                        dir = 1;
                    //无法比较大小或者hash值相等的情况，使用额外方式进行比较
                    else if ((kc == null &&
                            (kc = comparableClassFor(k)) == null) ||
                            (dir = compareComparables(kc, k, pk)) == 0)
                        dir = tieBreakOrder(k, pk);
                    //红黑树的具体插入实现
                    TreeNode<K,V> xp = p;
                    if ((p = (dir <= 0) ? p.left : p.right) == null) {
                        x.parent = xp;
                        if (dir <= 0)
                            xp.left = x;
                        else
                            xp.right = x;
                        r = balanceInsertion(r, x);
                        break;
                    }
                }
            }
        }
        this.root = r;
        assert checkInvariants(root);
    }

    /**
     * 更新concurrentHashMap的count值
     * @param x
     * @param check
     */
    private final void addCount(long x, int check) {
        CounterCell[] as; long b, s;
        if ((as = counterCells) != null ||
                //原子更新baseCount失败
                !U.compareAndSwapLong(this, BASECOUNT, b = baseCount, s = b + x)) {
            CounterCell a; long v; int m;
            boolean uncontended = true;
            if (as == null || (m = as.length - 1) < 0 ||
                    (a = as[ThreadLocalRandom.getProbe() & m]) == null ||
                    //首先尝试原子更新CountCell中的值
                    !(uncontended =
                            U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))) {
                //往as中添加新的CounterCell，且添加失败
                fullAddCount(x, uncontended);
                return;
            }
            if (check <= 1)
                return;
            s = sumCount();
        }
        //check代表是否检查table容量，是否需要resize
        if (check >= 0) {
            Node<K,V>[] tab, nt; int n, sc;
            while (s >= (long)(sc = sizeCtl) && (tab = table) != null &&
                    (n = tab.length) < MAXIMUM_CAPACITY) {
                int rs = resizeStamp(n);
                //map已经处于resize状态下，说明有其他thread正在进行transfer操作
                if (sc < 0) {
                    //当sc为负数时，其表示的含义为此次resize的目标(高RESIZESTAMP位)+此次resize最多容纳的并行线程数(32-RESIZESTAMP)
                    //所以如果sc >> RESIZE_STAMP_SHIFT != rs时，说明当前resize的目标已经大于需要resize的目标了，直接退出
                    if ((sc >>> RESIZE_STAMP_SHIFT) != rs
                            //由于transfer时的初始值即为(rs<<RESIZE_STAMP_SHIFT) + 2,因此这里的rs+1理应不应该出现
                            || sc == rs + 1
                            //目前并行transfer线程已经达到最大并行线程限制
                            || sc == rs + MAX_RESIZERS
                            //TODO:上述两个判断的rs是否应该为rs << RESIZE_STAMP_SHIFT呢？
                            //resize已经完成
                            || (nt = nextTable) == null
                            //已经没有需要transfer的bin了
                            || transferIndex <= 0)
                        break;
                    if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
                        transfer(tab, nt);
                }
                //开始进行resize
                else if (U.compareAndSwapInt(this, SIZECTL, sc,
                        (rs << RESIZE_STAMP_SHIFT) + 2))
                    transfer(tab, null);
                s = sumCount();
            }
        }
    }



    /**
     * 添加count值
     * @param x
     * @param wasUncontended
     */
    private final void fullAddCount(long x, boolean wasUncontended) {
        //随机初始化一个int值，设置其为h
        int h;
        if ((h = ThreadLocalRandom.getProbe()) == 0) {
            ThreadLocalRandom.localInit();      // force initialization
            h = ThreadLocalRandom.getProbe();
            wasUncontended = true;
        }
        boolean collide = false;                // True if last slot nonempty
        for (;;) {
            CounterCell[] as; CounterCell a; int n; long v;
            if ((as = counterCells) != null && (n = as.length) > 0) {
                //随机位置的countCell为空
                if ((a = as[(n - 1) & h]) == null) {
                    if (cellsBusy == 0) {            // Try to attach new Cell
                        CounterCell r = new CounterCell(x); // Optimistic create
                        if (cellsBusy == 0 &&
                                //cas替换cellsBusy值为1，代表获取到counterCells的写锁
                                //下方操作为将新创建的CounterCell值放入对应位置中
                                U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                            boolean created = false;
                            try {               // Recheck under lock
                                CounterCell[] rs; int m, j;
                                if ((rs = counterCells) != null &&
                                        (m = rs.length) > 0 &&
                                        rs[j = (m - 1) & h] == null) {
                                    rs[j] = r;
                                    created = true;
                                }
                            } finally {
                                cellsBusy = 0;
                            }
                            if (created)
                                break;
                            continue;           // Slot is now non-empty
                        }
                    }
                    collide = false;
                }
                else if (!wasUncontended)       // CAS already known to fail
                    wasUncontended = true;      // Continue after rehash
                //再次尝试通过cas将值附加到对应位置的CounterCell中
                else if (U.compareAndSwapLong(a, CELLVALUE, v = a.value, v + x))
                    break;
                //counterCells数组已经被更新，不再指向原来的数组了，说明经过了resize
                else if (counterCells != as || n >= NCPU)
                    collide = false;            // At max size or stale
                //说明发生了碰撞，即cellsBusy为ture，且无法通过CAS将value附加到CounterCell中
                else if (!collide)
                    collide = true;
                //再次尝试获取cellsBusy锁资源，扩大counterCells数组
                //隐含含义为：当前的CounterCells冲突严重，无法正确处理统计信息，需要扩大CounterCells的个数避免冲突，提高效率
                else if (cellsBusy == 0 &&
                        U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                    try {
                        if (counterCells == as) {// Expand table unless stale
                            CounterCell[] rs = new CounterCell[n << 1];
                            for (int i = 0; i < n; ++i)
                                rs[i] = as[i];
                            counterCells = rs;
                        }
                    } finally {
                        cellsBusy = 0;
                    }
                    collide = false;
                    continue;                   // Retry with expanded table
                }
                h = ThreadLocalRandom.advanceProbe(h);
            }
            //当CounterCells数组为空，初始化CounterCells，第一次初始化CounterCells数组大小为2，每次扩展都为原大小*2
            else if (cellsBusy == 0 && counterCells == as &&
                    U.compareAndSwapInt(this, CELLSBUSY, 0, 1)) {
                boolean init = false;
                try {                           // Initialize table
                    if (counterCells == as) {
                        CounterCell[] rs = new CounterCell[2];
                        rs[h & 1] = new CounterCell(x);
                        counterCells = rs;
                        init = true;
                    }
                } finally {
                    cellsBusy = 0;
                }
                if (init)
                    break;
            }
            //最后再尝试一下baseCount能否直接添加count值
            else if (U.compareAndSwapLong(this, BASECOUNT, v = baseCount, v + x))
                break;                          // Fall back on using base
        }
    }

    /**
     * ConcurrentHashMap通过baseCount与CounterCell数组共同记录当前map中保存的元素数量
     * 1.首先尝试使用一个volitale值BaseCount记录元素总数
     * 2.当baseCount冲突严重，影响吞吐时，则使用辅助数组CounterCells帮助记录元素数量
     * 3.调用size()函数时则需要将baseCount与辅助数组所保存的值加起来则可得到最终结果。
     * @return
     */
    final long sumCount() {
        CounterCell[] as = counterCells; CounterCell a;
        long sum = baseCount;
        if (as != null) {
            for (int i = 0; i < as.length; ++i) {
                if ((a = as[i]) != null)
                    sum += a.value;
            }
        }
        return sum;
    }

    /**
     * Moves and/or copies the nodes in each bin to new table. See
     * above for explanation.
     */
    private final void transfer(Node<K,V>[] tab, Node<K,V>[] nextTab) {
        int n = tab.length, stride;
        //根据CPU数量计算步进
        if ((stride = (NCPU > 1) ? (n >>> 3) / NCPU : n) < MIN_TRANSFER_STRIDE)
            stride = MIN_TRANSFER_STRIDE; // subdivide range
        //创建新table
        if (nextTab == null) {            // initiating
            try {
                @SuppressWarnings("unchecked")
                Node<K,V>[] nt = (Node<K,V>[])new Node<?,?>[n << 1];
                nextTab = nt;
            } catch (Throwable ex) {      // try to cope with OOME
                sizeCtl = Integer.MAX_VALUE;
                return;
            }
            nextTable = nextTab;
            transferIndex = n;
        }
        int nextn = nextTab.length;
        ForwardingNode<K,V> fwd = new ForwardingNode<K,V>(nextTab);
        boolean advance = true;
        boolean finishing = false; // to ensure sweep before committing nextTab
        for (int i = 0, bound = 0;;) {
            Node<K,V> f; int fh;
            while (advance) {
                int nextIndex, nextBound;
                if (--i >= bound || finishing)
                    advance = false;
                else if ((nextIndex = transferIndex) <= 0) {
                    i = -1;
                    advance = false;
                }
                else if (U.compareAndSwapInt
                        (this, TRANSFERINDEX, nextIndex,
                                nextBound = (nextIndex > stride ?
                                        nextIndex - stride : 0))) {
                    bound = nextBound;
                    i = nextIndex - 1;
                    advance = false;
                }
            }
            if (i < 0 || i >= n || i + n >= nextn) {
                int sc;
                //transfer完成
                if (finishing) {
                    nextTable = null;
                    table = nextTab;
                    sizeCtl = (n << 1) - (n >>> 1);
                    return;
                }
                //从transfer统计处线程数-1
                if (U.compareAndSwapInt(this, SIZECTL, sc = sizeCtl, sc - 1)) {
                    //判断transfer是否完成，是否为当前目标transfer
                    if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
                        return;
                    finishing = advance = true;
                    i = n; // recheck before commit
                }
            }
            //将forwordNode放到原tab中第i的位置
            else if ((f = tabAt(tab, i)) == null)
                advance = casTabAt(tab, i, null, fwd);
            //在抢占的过程中发现第i位置的node已经被置换为forwordNode，说明其他线程已处理
            else if ((fh = f.hash) == MOVED)
                advance = true; // already processed
            else {
                //获得forwordNode的monitor，并将原i位置的Node中所有值移到新table中
                synchronized (f) {
                    if (tabAt(tab, i) == f) {
                        Node<K,V> ln, hn;
                        if (fh >= 0) {
                            //通过计算最高位是否为0确定node的位置
                            //0 -> 新table的i位置
                            //1 -> 新table的n + i位置
                            int runBit = fh & n;
                            Node<K,V> lastRun = f;
                            for (Node<K,V> p = f.next; p != null; p = p.next) {
                                int b = p.hash & n;
                                if (b != runBit) {
                                    runBit = b;
                                    lastRun = p;
                                }
                            }
                            if (runBit == 0) {
                                ln = lastRun;
                                hn = null;
                            }
                            else {
                                hn = lastRun;
                                ln = null;
                            }
                            for (Node<K,V> p = f; p != lastRun; p = p.next) {
                                int ph = p.hash; K pk = p.key; V pv = p.val;
                                if ((ph & n) == 0)
                                    ln = new Node<K,V>(ph, pk, pv, ln);
                                else
                                    hn = new Node<K,V>(ph, pk, pv, hn);
                            }
                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd);
                            advance = true;
                        }
                        else if (f instanceof TreeBin) {
                            TreeBin<K,V> t = (TreeBin<K,V>)f;
                            TreeNode<K,V> lo = null, loTail = null;
                            TreeNode<K,V> hi = null, hiTail = null;
                            int lc = 0, hc = 0;
                            //简单将treeNode连接起来，最后在new TreeBin处构建红黑树
                            for (Node<K,V> e = t.first; e != null; e = e.next) {
                                int h = e.hash;
                                TreeNode<K,V> p = new TreeNode<K,V>
                                        (h, e.key, e.val, null, null);
                                if ((h & n) == 0) {
                                    if ((p.prev = loTail) == null)
                                        lo = p;
                                    else
                                        loTail.next = p;
                                    loTail = p;
                                    ++lc;
                                }
                                else {
                                    if ((p.prev = hiTail) == null)
                                        hi = p;
                                    else
                                        hiTail.next = p;
                                    hiTail = p;
                                    ++hc;
                                }
                            }
                            //由于经过了split，新table当前位置的treeNode数可能少于构建为树的阈值，对于这样的node将被重新处理为链表结构
                            ln = (lc <= UNTREEIFY_THRESHOLD) ? untreeify(lo) :
                                    (hc != 0) ? new TreeBin<K,V>(lo) : t;
                            hn = (hc <= UNTREEIFY_THRESHOLD) ? untreeify(hi) :
                                    (lc != 0) ? new TreeBin<K,V>(hi) : t;
                            setTabAt(nextTab, i, ln);
                            setTabAt(nextTab, i + n, hn);
                            setTabAt(tab, i, fwd);
                            advance = true;
                        }
                    }
                }
            }
        }
    }
}
