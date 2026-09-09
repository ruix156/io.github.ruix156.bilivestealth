package io.github.ruix156.bilivestealth;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模块设置: 存储于 B 站应用自身的 SharedPreferences,
 * 由直播间内的悬浮面板实时修改并立即生效。
 */
public class Settings {
  public static final String PREFS_NAME = "bilivestealth";

  public static final String MODE_BLACK = "black";
  public static final String MODE_WHITE = "white";

  // 名单标识 (用于定向删除)
  public static final String LIST_BLACK = "black";
  public static final String LIST_WHITE = "white";
  public static final String LIST_FIRST = "first";

  private final SharedPreferences prefs;
  private final Object lock = new Object();

  // 总开关
  public volatile boolean master = true;
  // 隐身进场: 进场上报替换 room_id, 不触发进场特效/欢迎消息
  public volatile boolean stealthEnter = true;
  // 匿名心跳: 心跳请求移除 access_key, 不计入在线人数/亲密度/高能榜
  public volatile boolean anonHeartbeat = true;
  // 游客弹幕连接: 弹幕 websocket 匿名化 (会导致 B 站对昵称打码 ***)
  public volatile boolean guestDanmaku = false;
  // 本地过滤进场消息 (不展示他人的进场提示/特效, 实验性)
  public volatile boolean filterEntryMsg = false;
  // 名单模式: black=全部隐身, 黑名单房间除外; white=仅白名单房间隐身
  public volatile String listMode = MODE_BLACK;
  // 进场上报伪装的房间号
  public volatile String fakeRoomId = "510";

  private volatile Set<Long> blacklist = new HashSet<>();
  private volatile Set<Long> whitelist = new HashSet<>();
  // 指定直播间: 每场直播的首次入场不隐身, 之后隐身
  private volatile Set<Long> firstVisibleRooms = new HashSet<>();
  // room -> 主播 uid (从响应中嗅探)
  private final Map<Long, Long> anchorMap = new HashMap<>();
  // uid -> 昵称 (从弹幕/互动消息中学习, 用于名单按主播昵称显示)
  private final Map<Long, String> uidNames = new HashMap<>();
  // room -> 主播昵称 (房间信息中直接学习, 优先级高于 uidNames)
  private final Map<Long, String> roomNames = new HashMap<>();
  // room -> 已完成首进的场次 key (一般为开播时间 live_time)
  private final Map<Long, String> firstEntrySessions = new HashMap<>();
  // room -> 最近观测到的场次 key
  private final Map<Long, String> roomLiveTimes = new HashMap<>();
  // 本进程内已完成首进的房间 (场次 key 未知时的兜底: 场次 ≈ App 进程周期)
  private final Set<Long> sessionEntered = new HashSet<>();

  private volatile long currentRoom = 0L;

  public Settings(Context ctx) {
    prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  public void load() {
    master = prefs.getBoolean("master", true);
    stealthEnter = prefs.getBoolean("stealthEnter", true);
    anonHeartbeat = prefs.getBoolean("anonHeartbeat", true);
    guestDanmaku = prefs.getBoolean("guestDanmaku", false);
    filterEntryMsg = prefs.getBoolean("filterEntryMsg", false);
    listMode = prefs.getString("listMode", MODE_BLACK);
    fakeRoomId = prefs.getString("fakeRoomId", "510");
    if (fakeRoomId == null || fakeRoomId.isEmpty()) fakeRoomId = "510";
    synchronized (lock) {
      blacklist = toLongSet(prefs.getStringSet("blacklist", null));
      whitelist = toLongSet(prefs.getStringSet("whitelist", null));
      firstVisibleRooms = toLongSet(prefs.getStringSet("firstVisibleRooms", null));
      anchorMap.putAll(toLongLongMap(prefs.getString("anchorMap", null)));
      uidNames.putAll(toStringMap(prefs.getString("uidNames", null)));
      roomNames.putAll(toStringMap(prefs.getString("roomNames", null)));
      firstEntrySessions.putAll(toStringMap(prefs.getString("firstEntrySessions", null)));
      roomLiveTimes.putAll(toStringMap(prefs.getString("roomLiveTimes", null)));
    }
  }

  /**
   * 当前房间是否应用隐身规则。
   * roomId <= 0 (未知) 时: 黑名单模式默认隐身, 白名单模式默认不隐身。
   */
  public boolean shouldStealth(long roomId) {
    if (!master) return false;
    boolean white = MODE_WHITE.equals(listMode);
    boolean inList;
    synchronized (lock) {
      Set<Long> list = white ? whitelist : blacklist;
      inList = roomId > 0 && list.contains(roomId);
    }
    if (white) return inList;
    return !inList;
  }

  // ===== 指定直播间: 每场直播首次入场不隐身 =====

  /** 该房间本场直播的首次入场是否保持可见。 */
  public boolean shouldFirstEntryBeVisible(long roomId) {
    if (roomId <= 0) return false;
    synchronized (lock) {
      return firstVisibleRooms.contains(roomId) && !sessionEntered.contains(roomId);
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

  /** 手动重置指定房间的首进状态 (下次入场重新视为首次)。 */
  public void resetFirstEntry(long roomId) {
    if (roomId <= 0) return;
    synchronized (lock) {
      firstEntrySessions.remove(roomId);
      sessionEntered.remove(roomId);
    }
    persistMap("firstEntrySessions", firstEntrySessions);
  }

  public boolean inFirstVisible(long roomId) {
    synchronized (lock) {
      return roomId > 0 && firstVisibleRooms.contains(roomId);
    }
  }

  public void addFirstVisible(long roomId) {
    if (roomId <= 0) return;
    boolean changed;
    synchronized (lock) {
      changed = firstVisibleRooms.add(roomId);
    }
    if (changed) {
      prefs.edit().putStringSet("firstVisibleRooms", toStrSet(firstVisibleRooms)).apply();
    }
  }

  // ===== 主播 uid / 昵称嗅探 (用于名单按主播昵称显示) =====

  public void recordAnchor(long roomId, long uid) {
    if (roomId <= 0 || uid <= 0) return;
    boolean changed;
    synchronized (lock) {
      Long old = anchorMap.get(roomId);
      changed = old == null || old != uid;
      if (changed) anchorMap.put(roomId, uid);
    }
    if (changed) persistMap("anchorMap", anchorMap);
  }

  /** 记录 uid 对应的昵称。 */
  public void recordUname(long uid, String uname) {
    if (uid <= 0 || uname == null) return;
    String name = sanitizeName(uname);
    if (name.isEmpty()) return;
    boolean changed;
    synchronized (lock) {
      String old = uidNames.get(uid);
      changed = !name.equals(old);
      if (changed) {
        if (uidNames.size() >= 1000 && !uidNames.containsKey(uid)) return;
        uidNames.put(uid, name);
      }
    }
    if (changed) persistMap("uidNames", uidNames);
  }

  /** 记录房间对应的主播昵称 (优先级最高)。 */
  public void recordRoomName(long roomId, String uname) {
    if (roomId <= 0 || uname == null) return;
    String name = sanitizeName(uname);
    if (name.isEmpty()) return;
    boolean changed;
    synchronized (lock) {
      changed = !name.equals(roomNames.get(roomId));
      if (changed) roomNames.put(roomId, name);
    }
    if (changed) persistMap("roomNames", roomNames);
  }

  private static String sanitizeName(String s) {
    String name = s.trim();
    if (name.length() > 64) name = name.substring(0, 64);
    return name.replaceAll("[\\p{Cntrl}]", "");
  }

  /** 名单条目显示名: 优先主播昵称, 未知则显示房间号。 */
  public String displayName(long roomId) {
    synchronized (lock) {
      String name = roomNames.get(roomId);
      if (name != null) return name;
      Long uid = anchorMap.get(roomId);
      if (uid != null) {
        String uname = uidNames.get(uid);
        if (uname != null) return uname;
      }
    }
    return "房间:" + roomId;
  }

  // ===== 名单只读副本 (面板列表展示用) =====

  public List<Long> getBlacklistSorted() {
    synchronized (lock) {
      return sortedCopy(blacklist);
    }
  }

  public List<Long> getWhitelistSorted() {
    synchronized (lock) {
      return sortedCopy(whitelist);
    }
  }

  public List<Long> getFirstVisibleSorted() {
    synchronized (lock) {
      return sortedCopy(firstVisibleRooms);
    }
  }

  private static List<Long> sortedCopy(Set<Long> set) {
    List<Long> out = new ArrayList<>(set);
    Collections.sort(out);
    return out;
  }

  // ===== 基础设置读写 =====

  public void setCurrentRoom(long roomId) {
    currentRoom = roomId;
  }

  public long getCurrentRoom() {
    return currentRoom;
  }

  public void setMaster(boolean v) {
    master = v;
    prefs.edit().putBoolean("master", v).apply();
  }

  public void setStealthEnter(boolean v) {
    stealthEnter = v;
    prefs.edit().putBoolean("stealthEnter", v).apply();
  }

  public void setAnonHeartbeat(boolean v) {
    anonHeartbeat = v;
    prefs.edit().putBoolean("anonHeartbeat", v).apply();
  }

  public void setGuestDanmaku(boolean v) {
    guestDanmaku = v;
    prefs.edit().putBoolean("guestDanmaku", v).apply();
  }

  public void setFilterEntryMsg(boolean v) {
    filterEntryMsg = v;
    prefs.edit().putBoolean("filterEntryMsg", v).apply();
  }

  public void setListMode(String m) {
    listMode = m;
    prefs.edit().putString("listMode", m).apply();
  }

  public void setFakeRoomId(String v) {
    fakeRoomId = v;
    prefs.edit().putString("fakeRoomId", v).apply();
  }

  public void addRoom(long roomId, boolean white) {
    if (roomId <= 0) return;
    Set<Long> after;
    synchronized (lock) {
      Set<Long> old = white ? whitelist : blacklist;
      if (old.contains(roomId)) return;
      Set<Long> next = new HashSet<>(old);
      next.add(roomId);
      if (white) whitelist = next;
      else blacklist = next;
      after = next;
    }
    prefs.edit().putStringSet(white ? "whitelist" : "blacklist", toStrSet(after)).apply();
  }

  /** 从指定名单中删除房间。which: LIST_BLACK / LIST_WHITE / LIST_FIRST。 */
  public void removeFromList(long roomId, String which) {
    if (roomId <= 0) return;
    Set<Long> after;
    synchronized (lock) {
      Set<Long> target = LIST_WHITE.equals(which) ? whitelist
          : LIST_FIRST.equals(which) ? firstVisibleRooms : blacklist;
      if (!target.contains(roomId)) return;
      Set<Long> next = new HashSet<>(target);
      next.remove(roomId);
      if (LIST_WHITE.equals(which)) whitelist = next;
      else if (LIST_FIRST.equals(which)) firstVisibleRooms = next;
      else blacklist = next;
      after = next;
    }
    prefs.edit().putStringSet(persistKey(which), toStrSet(after)).apply();
  }

  /** 从所有名单 (黑名单/白名单/首进可见) 中移除指定房间。 */
  public void removeRoom(long roomId) {
    if (roomId <= 0) return;
    SharedPreferences.Editor ed = prefs.edit();
    boolean changed = false;
    synchronized (lock) {
      if (blacklist.contains(roomId)) {
        Set<Long> next = new HashSet<>(blacklist);
        next.remove(roomId);
        blacklist = next;
        ed.putStringSet("blacklist", toStrSet(next));
        changed = true;
      }
      if (whitelist.contains(roomId)) {
        Set<Long> next = new HashSet<>(whitelist);
        next.remove(roomId);
        whitelist = next;
        ed.putStringSet("whitelist", toStrSet(next));
        changed = true;
      }
      if (firstVisibleRooms.contains(roomId)) {
        Set<Long> next = new HashSet<>(firstVisibleRooms);
        next.remove(roomId);
        firstVisibleRooms = next;
        ed.putStringSet("firstVisibleRooms", toStrSet(next));
        changed = true;
      }
    }
    if (changed) ed.apply();
  }

  // ===== 序列化工具 =====

  private static String persistKey(String which) {
    if (LIST_WHITE.equals(which)) return "whitelist";
    if (LIST_FIRST.equals(which)) return "firstVisibleRooms";
    return "blacklist";
  }

  private void persistMap(String key, Map<Long, ?> m) {
    try {
      JSONObject jo = new JSONObject();
      for (Map.Entry<Long, ?> e : m.entrySet()) {
        jo.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
      }
      prefs.edit().putString(key, jo.toString()).apply();
    } catch (Exception ignored) {
    }
  }

  private static Set<Long> toLongSet(Set<String> src) {
    Set<Long> out = new HashSet<>();
    if (src != null) {
      for (String s : src) {
        try {
          out.add(Long.parseLong(s.trim()));
        } catch (Exception ignored) {
        }
      }
    }
    return out;
  }

  private static Set<String> toStrSet(Set<Long> src) {
    Set<String> out = new HashSet<>();
    for (Long id : src) out.add(String.valueOf(id));
    return out;
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

  private static Map<Long, Long> toLongLongMap(String json) {
    Map<Long, Long> out = new HashMap<>();
    if (json != null) {
      try {
        JSONObject jo = new JSONObject(json);
        Iterator<String> it = jo.keys();
        while (it.hasNext()) {
          String k = it.next();
          try {
            out.put(Long.parseLong(k), jo.getLong(k));
          } catch (Exception ignored) {
          }
        }
      } catch (Exception ignored) {
      }
    }
    return out;
  }
}
