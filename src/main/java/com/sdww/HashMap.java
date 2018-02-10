package com.sdww;

public class HashMap {

    /**
     * 返回大于等于cap的最小2的整数次幂
     */
    static final int tableSizeFor(int cap) {
        int n = cap - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }

    /**
     * hashmap中采用该方式计算hash值是为了让hashcode尽可能均匀分布
     * 由于计算槽位时使用的方法为 hashcode & (cap - 1)，因此当槽位很少时，cap值很小，计算槽位时总是取hashcode的低位，造成大量hashcode冲突
     * 采用该方案可以使高位hashcode的改变可以传递至低位来，优化槽位很少情况时hash冲突的情况
     * @param key
     * @return
     */
    static final int hash(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }

    /**
     * Implements Map.put and related methods
     *
     * @param hash hash for key
     * @param key the key
     * @param value the value to put
     * @param onlyIfAbsent if true, don't change existing value
     * @param evict if false, the table is in creation mode.
     * @return previous value, or null if none
     */
    final V putVal(int hash, K key, V value, boolean onlyIfAbsent,
                   boolean evict) {
        Node<K,V>[] tab; Node<K,V> p; int n, i;
        //HashMap的构造函数只会设置loadFactor与threshold的值，真正的初始化过程在使用时才会发生
        if ((tab = table) == null || (n = tab.length) == 0)
            n = (tab = resize()).length;
        //经过计算，该hashcode所对应的槽位为空，放入新建的Node即可
        if ((p = tab[i = (n - 1) & hash]) == null)
            tab[i] = newNode(hash, key, value, null);
        //hashcode所对应的槽位不为空，极有可能为hash冲突
        else {
            Node<K,V> e; K k;
            //对应槽位上的节点key与将要设置的key值一致，使用新值覆盖原有的值
            if (p.hash == hash &&
                    ((k = p.key) == key || (key != null && key.equals(k))))
                e = p;
            //槽位节点为treeNode,将新值放入tree中
            else if (p instanceof TreeNode)
                e = ((TreeNode<K,V>)p).putTreeVal(this, tab, hash, key, value);
            //槽位节点仍为链表结构，放到其对应的位置
            else {
                for (int binCount = 0; ; ++binCount) {
                    //放到链表的结尾
                    if ((e = p.next) == null) {
                        p.next = newNode(hash, key, value, null);
                        if (binCount >= TREEIFY_THRESHOLD - 1) // -1 for 1st
                            treeifyBin(tab, hash);
                        break;
                    }
                    //链表中某个节点的key与将要设置的key值相等，覆盖value
                    if (e.hash == hash &&
                            ((k = e.key) == key || (key != null && key.equals(k))))
                        break;
                    p = e;
                }
            }
            //覆盖的具体逻辑
            if (e != null) { // existing mapping for key
                V oldValue = e.value;
                if (!onlyIfAbsent || oldValue == null)
                    e.value = value;
                afterNodeAccess(e);
                return oldValue;
            }
        }
        ++modCount;
        //判断map中当前元素数是否超过阈值，如是，则对hashmap进行扩容
        if (++size > threshold)
            resize();
        afterNodeInsertion(evict);
        return null;
    }

    /**
     * 扩容的具体函数
     * Initializes or doubles table size.  If null, allocates in
     * accord with initial capacity target held in field threshold.
     * Otherwise, because we are using power-of-two expansion, the
     * elements from each bin must either stay at same index, or move
     * with a power of two offset in the new table.
     *
     * @return the table
     */
    final Node<K,V>[] resize() {
        Node<K,V>[] oldTab = table;
        int oldCap = (oldTab == null) ? 0 : oldTab.length;
        int oldThr = threshold;
        int newCap, newThr = 0;

        /*
        找到newCap与newThr
         */
        //非初始化情况
        if (oldCap > 0) {
            if (oldCap >= MAXIMUM_CAPACITY) {
                threshold = Integer.MAX_VALUE;
                return oldTab;
            }
            else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
                    oldCap >= DEFAULT_INITIAL_CAPACITY)
                newThr = oldThr << 1; // double threshold
        }
        //初始化时仅指明了容量initialCapacity
        else if (oldThr > 0) // initial capacity was placed in threshold
            newCap = oldThr;
        //默认初始化，即new HashMap();
        else {               // zero initial threshold signifies using defaults
            newCap = DEFAULT_INITIAL_CAPACITY;
            newThr = (int)(DEFAULT_LOAD_FACTOR * DEFAULT_INITIAL_CAPACITY);
        }
        //如果仍未找到新的阈值
        if (newThr == 0) {
            float ft = (float)newCap * loadFactor;
            newThr = (newCap < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ?
                    (int)ft : Integer.MAX_VALUE);
        }
        threshold = newThr;

        //构建新table
        @SuppressWarnings({"rawtypes","unchecked"})
        Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];
        table = newTab;
        //旧table不为空，涉及到老Node的迁移
        if (oldTab != null) {
            for (int j = 0; j < oldCap; ++j) {
                Node<K,V> e;
                if ((e = oldTab[j]) != null) {
                    //将节点从老table上摘下来
                    oldTab[j] = null;
                    //节点无链表，也无tree的情况，直接迁移到新table的对应位置即可
                    if (e.next == null)
                        newTab[e.hash & (newCap - 1)] = e;
                    //指定位置的节点为treeNode的情况
                    else if (e instanceof TreeNode)
                        ((TreeNode<K,V>)e).split(this, newTab, j, oldCap);
                    //制定位置的节点为链表的情况，该链表将分裂为低位/高位两个链表，划分依据为新增判断位的值是否为0
                    //如果新增判断位(最高位)为0，则放入链表lo中；反之放入hiTail中
                    else { // preserve order
                        Node<K,V> loHead = null, loTail = null;
                        Node<K,V> hiHead = null, hiTail = null;
                        Node<K,V> next;
                        do {
                            next = e.next;
                            if ((e.hash & oldCap) == 0) {
                                if (loTail == null)
                                    loHead = e;
                                else
                                    loTail.next = e;
                                loTail = e;
                            }
                            else {
                                if (hiTail == null)
                                    hiHead = e;
                                else
                                    hiTail.next = e;
                                hiTail = e;
                            }
                        } while ((e = next) != null);
                        if (loTail != null) {
                            loTail.next = null;
                            newTab[j] = loHead;
                        }
                        if (hiTail != null) {
                            hiTail.next = null;
                            newTab[j + oldCap] = hiHead;
                        }
                    }
                }
            }
        }
        return newTab;
    }
}
