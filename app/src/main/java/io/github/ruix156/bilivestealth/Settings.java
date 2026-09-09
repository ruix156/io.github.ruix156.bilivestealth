package io.github.ruix156.bilivestealth;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

/**
 * 宿主端运行时门面。
 * 用户配置 (开关/名单/模式) 由设置 App 写入模块自身配置文件, 经 ConfigStore 热读取;
 * 运行时数据 (首进场次判定) 存于宿主自身 SharedPreferences, 仅宿主进程读写。
 */
public class Settings {
  /** 宿主自身 prefs: dexkit 类名缓存 + 首进场次数据。 */
  public static final String PREFS_NAME = "bilivestealth";

  private final SharedPreferences runtime;
  private final Object lock = new Object();
  private final ConfigStore configStore;

  // room -> 已完成首进的场次 key (一般为开播时间 live_time)
  private final Map<Long, String> firstEntrySessions = new HashMap<>();
  // room -> 最近观测到的场次 key
  private final Map<Long, String> roomLiveTimes = new HashMap<>();
  // 本进程内已完成首进的房间 (场次 key 未知时的兜底: 场次 ≈ App 进程周期)
  private final Set<Long> sessionEntered = new HashSet<>();

  public Settings(Context ctx) {
    runtime = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    configStore = new ConfigStore(ctx);
  }

  public ConfigStore config() {
    return configStore;
  }

  public void load() {
    synchronized (lock) {
      firstEntrySessions.putAll(toStringMap(runtime.getString("firstEntrySessions", null)));
      roomLiveTimes.putAll(toStringMap(runtime.getString("roomLiveTimes", null)));
    }
  }

  /** 当前房间是否应用隐身规则 (配置热读取)。 */
  public boolean shouldStealth(long roomId) {
    configStore.maybeReload();
    return configStore.get().shouldStealth(roomId);
  }

  /** 配置快照 (调用前不强制检查重载, 适合高频路径)。 */
  public StealthConfig configSnapshot() {
    return configStore.get();
  }

  // ===== 指定直播间: 每场直播首次入场不隐身 =====

  /** 该房间本场直播的首次入场是否保持可见。 */
  public boolean shouldFirstEntryBeVisible(long roomId) {
    configStore.maybeReload();
    if (roomId <= 0) return false;
    if (!configStore.get().firstVisibleRooms.contains(roomId)) return false;
    synchronized (lock) {
      return !sessionEntered.contains(roomId);
    }
  }

  /** 标记该房间本场已完成首次入场, 后续入场隐身。 */
  public void markFirstEntryDone(long roomId) {
    if (roomId <= 0) return;
    String sessionKey;
    synchronized (lock) {
      sessionEntered.add(roomId);
      sessionKey = roomLiveTimes.get(roomId);
      if (sessionKey != null) firstEntrySessions.put(roomId, sessionKey);
    }
    if (sessionKey != null) persistMap("firstEntrySessions", firstEntrySessions);
  }

  /** 从响应中观测到房间的开播时间 (场次 key): 场次变化时重置首进状态。 */
  public void recordLiveTime(long roomId, String liveTime) {
    if (roomId <= 0 || liveTime == null || liveTime.isEmpty()) return;
    boolean changed;
    synchronized (lock) {
      String old = roomLiveTimes.get(roomId);
      changed = !liveTime.equals(old);
      if (changed) {
        roomLiveTimes.put(roomId, liveTime);
        String done = firstEntrySessions.get(roomId);
        if (liveTime.equals(done)) {
          sessionEntered.add(roomId);
        } else {
          sessionEntered.remove(roomId);
        }
      }
    }
    if (changed) persistMap("roomLiveTimes", roomLiveTimes);
  }

  // ===== 序列化工具 =====

  private void persistMap(String key, Map<Long, String> m) {
    try {
      JSONObject jo = new JSONObject();
      for (Map.Entry<Long, String> e : m.entrySet()) {
        jo.put(String.valueOf(e.getKey()), e.getValue());
      }
      runtime.edit().putString(key, jo.toString()).apply();
    } catch (Exception ignored) {
    }
  }

  private static Map<Long, String> toStringMap(String json) {
    Map<Long, String> out = new HashMap<>();
    if (json != null) {
      try {
        JSONObject jo = new JSONObject(json);
        Iterator<String> it = jo.keys();
        while (it.hasNext()) {
          String k = it.next();
          try {
            out.put(Long.parseLong(k), jo.getString(k));
          } catch (Exception ignored) {
          }
        }
      } catch (Exception ignored) {
      }
    }
    return out;
  }
}
