package ai.chat2db.community.storage.small;

import ai.chat2db.community.domain.api.converter.LocalStorageConverter;
import ai.chat2db.community.domain.api.service.storage.IWorkspaceLocalStorage;
import ai.chat2db.community.storage.IdUtil;
import ai.chat2db.community.tools.util.ConfigUtils;
import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

@Slf4j
public class SmallDataStorage<T> implements IWorkspaceLocalStorage<T> {

    protected static final String DB_STORAGE_PATH = ConfigUtils.getEnvBasePath() + File.separator + "storage";

    protected Map<Long, T> dataMap = new ConcurrentSkipListMap<>();

    protected String filePath;

    protected SmallDataStorage(String name, Class<T> clazz) {
        this(new File(DB_STORAGE_PATH + File.separator + name + File.separator + name + ".json"), clazz);
    }

    protected SmallDataStorage(File storageFile, Class<T> clazz) {
        this.filePath = storageFile.getAbsolutePath();
        if (!FileUtil.exist(filePath)) {
            FileUtil.writeUtf8String("", filePath);
        } else {
            FileUtil.readLines(filePath, "UTF-8").forEach(line -> {
                if (StringUtils.isNotBlank(line)) {
                    try {
                        T t = JSON.parseObject(line.trim(), clazz);
                        Long id = LocalStorageConverter.getId(t);
                        dataMap.put(id, t);
                    } catch (Exception e) {
                        log.error("SmallDataStorage error", e);
                    }
                }
            });
        }
    }

    public static <T> SmallDataStorage<T> create(String name, Class<T> clazz) {
        return new SmallDataStorage<>(name, clazz);
    }

    @Override
    public List<T> getDataList() {
        return Lists.newArrayList(dataMap.values());
    }


    @Override
    public T getById(Long id) {
        if (id == null) {
            return null;
        }
        return dataMap.get(id);
    }

    @Override
    public synchronized Long save(T data) {
        if (data == null) {
            return null;
        }
        try {
            Long id = LocalStorageConverter.ensureId(data, this::generateId);
            if (dataMap.get(id) != null) {
                dataMap.put(id, data);
                saveDataList();
            } else {
                dataMap.put(id, data);
                FileUtil.appendUtf8String(JSON.toJSONString(data) + "\n", filePath);
            }
            return id;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public synchronized void update(T data) {
        if (data == null) {
            return;
        }
        try {
            Long id = LocalStorageConverter.getId(data);
            if (id == null) {
                return;
            }
            T before = dataMap.get(id);
            before = getAfterSave(before, data);
            dataMap.put(id, before);
            saveDataList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    @Override
    public synchronized void delete(Long id) {
        dataMap.remove(id);
        saveDataList();
    }

    protected synchronized void saveDataList() {
        List<T> dataList = getDataList();
        // Build the full content in memory, write it to a temp file in the same
        // directory, then atomically rename over the target. The previous
        // truncate-then-append loop left the storage file partially written on a
        // crash/disk-full, permanently losing all records after the truncation
        // point on reload. With a temp + rename, the storage file is either the
        // previous or the new content, never a partial write.
        Path target = Paths.get(filePath);
        Path temp = Paths.get(filePath + ".tmp");
        try {
            StringBuilder sb = new StringBuilder();
            if (dataList != null) {
                for (T data : dataList) {
                    sb.append(JSON.toJSONString(data)).append('\n');
                }
            }
            Files.write(temp, sb.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                // Same-directory temp is normally on the same filesystem, but if the
                // platform/filesystem does not support an atomic move, fall back to a
                // best-effort replace (still a single replace, not a truncate+append).
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignore) {
                // best-effort cleanup
            }
            throw new RuntimeException(e);
        }
    }

    public Long generateId() {
        return IdUtil.generateId();
    }

}
