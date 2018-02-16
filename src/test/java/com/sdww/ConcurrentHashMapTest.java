package com.sdww;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentHashMapTest {

    public static void main(String[] args) {
        ConcurrentHashMap<SameKeyNode, Integer> map = new ConcurrentHashMap<>();
        for(int i = 0; i < 20; i++) {
            map.put(new SameKeyNode(), i);
        }

    }

    public static class SameKeyNode {
        public static Integer KEY = UUID.randomUUID().hashCode();
        private static AtomicInteger ID = new AtomicInteger(0);
        private Integer id = ID.getAndIncrement();

        @Override
        public int hashCode() {
            return KEY;
        }
    }
}
