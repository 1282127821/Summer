package com.swingfrog.summer.db.repository;

import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.swingfrog.summer.db.DaoRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public abstract class AsyncCacheRepositoryDao<T, K> extends CacheRepositoryDao<T, K> {

    private static final Logger log = LoggerFactory.getLogger(AsyncCacheRepositoryDao.class);
    private final ConcurrentLinkedQueue<Change<T, K>> waitChange = Queues.newConcurrentLinkedQueue();
    private final ConcurrentMap<K, Save<T, K>> waitSave = Maps.newConcurrentMap();
    private final ConcurrentMap<K, Long> waitAdd = Maps.newConcurrentMap();
    private final ConcurrentMap<K, Long> waitRemove = Maps.newConcurrentMap();
    private final AtomicLong changeVersion = new AtomicLong();
    long delayTime;

    protected abstract long delayTime();

    @Override
    void init() {
        super.init();
        delayTime = delayTime();
        AsyncCacheRepositoryMgr.get().getScheduledExecutor().scheduleWithFixedDelay(
                () -> delay(false),
                delayTime,
                delayTime,
                TimeUnit.MILLISECONDS);
        AsyncCacheRepositoryMgr.get().addHook(() -> delay(true));
        if (!isNeverExpire() && delayTime >= expireTime) {
            throw new DaoRuntimeException(String.format("async cache repository delayTime[%s] must be less than expireTime[%s]", delayTime, expireTime));
        }
    }

    private synchronized void delay(boolean force) {
        try {
            while (!waitChange.isEmpty()) {
                Change<T, K> change = waitChange.poll();
                if (change.add) {
                    delayAdd(change.obj, change.pk, change.version);
                } else {
                    delayRemove(change.pk, change.version);
                }
            }
            delaySave(force);
        } catch (Throwable e) {
            log.error("AsyncCacheRepository delay failure.");
            log.error(e.getMessage(), e);
        }
    }

    private void delayAdd(T obj, K primaryKey, long version) {
        Long currentVersion = waitAdd.get(primaryKey);
        if (currentVersion == null)
            return;
        if (currentVersion != version)
            return;
        super.addByPrimaryKeyNotAddCache(obj, primaryKey);
    }

    private void delayRemove(K primaryKey, long version) {
        Long currentVersion = waitRemove.get(primaryKey);
        if (currentVersion == null)
            return;
        if (currentVersion != version)
            return;
        super.removeByPrimaryKeyNotRemoveCache(primaryKey);
    }

    private void delaySave(boolean force) {
        long time = System.currentTimeMillis();

        List<Save<T, K>> saves = waitSave.values().stream()
                .filter(value -> force || time - value.saveTime >= delayTime)
                .collect(Collectors.toList());

        if (!saves.isEmpty()) {
            saves.stream().map(value -> value.obj).forEach(super::save);
        }

        saves.stream().filter(value -> force || time - value.saveTime >= delayTime).forEach(value -> waitSave.remove(value.pk));
        log.info("async cache repository table[{}] delay save nowSaveCount[{}] waitSaveCount[{}]", getTableMeta().getName(), saves.size(), waitSave.size());
    }

    @SuppressWarnings("unchecked")
    @Override
    public T add(T obj) {
        Objects.requireNonNull(obj);
        super.autoIncrementPrimaryKey(obj);
        K primaryKey = (K) TableValueBuilder.getPrimaryKeyValue(getTableMeta(), obj);
        T old = super.addCacheIfAbsent(primaryKey, obj);
        if (old != null)
            return old;
        long version = changeVersion.incrementAndGet();
        waitAdd.put(primaryKey, version);
        waitChange.add(new Change<>(obj, primaryKey, version));
        return obj;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean remove(T obj) {
        Objects.requireNonNull(obj);
        return removeByPrimaryKey((K) TableValueBuilder.getPrimaryKeyValue(getTableMeta(), obj));
    }

    @Override
    public boolean removeByPrimaryKey(K primaryKey) {
        Objects.requireNonNull(primaryKey);
        super.removeCacheByPrimaryKey(primaryKey);
        long version = changeVersion.incrementAndGet();
        waitRemove.put(primaryKey, version);
        waitChange.add(new Change<>(primaryKey, version));
        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean save(T obj) {
        Objects.requireNonNull(obj);
        K pk = (K) TableValueBuilder.getPrimaryKeyValue(getTableMeta(), obj);
        T newObj = get(pk);
        if (obj != newObj) {
            log.warn("async cache repository table[{}] primary key[{}] expire, can't save", getTableMeta().getName(), pk);
            return false;
        }
        waitSave.computeIfAbsent(pk, k -> new Save<>(obj, pk, System.currentTimeMillis()));
        return true;
    }

    @Override
    public void save(Collection<T> objs) {
        Objects.requireNonNull(objs);
        objs.forEach(this::save);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void forceSave(T obj) {
        Objects.requireNonNull(obj);
        K pk = (K) TableValueBuilder.getPrimaryKeyValue(getTableMeta(), obj);
        forceAddCache(pk, obj);
        waitSave.computeIfAbsent(pk, k -> new Save<>(obj, pk, System.currentTimeMillis()));
    }

    @Override
    public T getOrCreate(K primaryKey, Supplier<T> supplier) {
        Objects.requireNonNull(primaryKey);
        Objects.requireNonNull(supplier);
        T entity = get(primaryKey);
        if (entity == null) {
            entity = supplier.get();
            entity = add(entity);
        }
        return entity;
    }

    private static class Change<T, K> {
        T obj;
        K pk;
        boolean add;
        long version;
        Change(T obj, K pk, long version) {
            this.obj = obj;
            this.pk = pk;
            this.add = true;
            this.version = version;
        }
        Change(K pk, long version) {
            this.pk = pk;
            this.add = false;
            this.version = version;
        }
    }

    private static class Save<T, K> {
        T obj;
        K pk;
        long saveTime;
        Save(T obj, K pk, long saveTime) {
            this.obj = obj;
            this.pk = pk;
            this.saveTime = saveTime;
        }
    }

    public T syncAdd(T obj) {
        return super.add(obj);
    }

    public boolean syncRemove(T obj) {
        return super.remove(obj);
    }

    public boolean syncRemoveByPrimaryKey(K primaryKey) {
        return super.removeByPrimaryKey(primaryKey);
    }

    public boolean syncSave(T obj) {
        return super.save(obj);
    }

    public void syncSave(Collection<T> objs) {
        super.save(objs);
    }

    public T syncGetOrCreate(K primaryKey, Supplier<T> supplier) {
        return super.getOrCreate(primaryKey, supplier);
    }
}
