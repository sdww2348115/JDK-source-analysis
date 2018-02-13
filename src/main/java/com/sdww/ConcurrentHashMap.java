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
}
