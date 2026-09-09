package io.github.ruix156.bilivestealth;

import org.json.JSONArray;
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
 * 用户配置快照 (纯数据 + JSON 序列化)。
 * 由设置 App (SettingsActivity) 写入模块自身 files 目录, 宿主进程只读。
 * 默认值 = 默认隐身: 总开关开 + 黑名单模式 (空名单 => 全部直播间隐身)。
 */
public class StealthConfig {
  public static final String MODE_BLACK = "black";
  public static final String MODE_WHITE = "white";

  // 总开关
  public boolean master = true;
  // 隐身进场: 进场上报替换 room_id, 不触发进场特效/欢迎消息
  public boolean stealthEnter = true;
  // 匿名心跳: 心跳请求移除 access_key, 不计入在线人数/亲密度/高能榜
  public boolean anonHeartbeat = true;
  // 游客弹幕连接: 弹幕 websocket 匿名化 (会导致 B 站对昵称打码 ***)
  public boolean guestDanmaku = false;
  // 本地过滤进场消息 (不展示他人的进场提示/特效, 实验性)
  public boolean filterEntryMsg = false;
  // 名单模式: black=全部隐身, 黑名单房间除外; white=仅白名单房间隐身
  public String listMode = MODE_BLACK;
  // 进场上报伪装的房间号
  public String fakeRoomId = "510";

  public Set<Long> blacklist = new HashSet<>();
  public Set<Long> whitelist = new HashSet<>();
  // 指定直播间: 每场直播的首次入场不隐身, 之后隐身
  public Set<Long> firstVisibleRooms = new HashSet<>();
  // roomId -> 主播昵称 (设置 App 添加名单时从公开接口获取, 仅供显示)
  public Map<Long, String> roomNames = new HashMap<>();

  public StealthConfig copy() {
    StealthConfig c = new StealthConfig();
    c.master = master;
    c.stealthEnter = stealthEnter;
    c.anonHeartbeat = anonHeartbeat;
    c.guestDanmaku = guestDanmaku;
    c.filterEntryMsg = filterEntryMsg;
    c.listMode = listMode;
    c.fakeRoomId = fakeRoomId;
    c.blacklist = new HashSet<>(blacklist);
    c.whitelist = new HashSet<>(whitelist);
    c.firstVisibleRooms = new HashSet<>(firstVisibleRooms);
    c.roomNames = new HashMap<>(roomNames);
    return c;
  }

  /** 当前房间是否应用隐身规则。roomId <= 0 (未知) 时: 黑名单模式默认隐身, 白名单模式默认不隐身。 */
  public boolean shouldStealth(long roomId) {
    if (!master) return false;
    boolean white = MODE_WHITE.equals(listMode);
    Set<Long> list = white ? whitelist : blacklist;
    boolean inList = roomId > 0 && list.contains(roomId);
    return white == inList;
  }

  public String displayName(long roomId) {
    String name = roomNames.get(roomId);
    return (name == null || name.isEmpty()) ? "房间:" + roomId : name;
  }

  public List<Long> sortedList(Set<Long> set) {
    List<Long> out = new ArrayList<>(set);
    Collections.sort(out);
    return out;
  }

  // ===== JSON 序列化 =====

  public JSONObject toJson() {
    JSONObject jo = new JSONObject();
    try {
      jo.put("master", master);
      jo.put("stealthEnter", stealthEnter);
      jo.put("anonHeartbeat", anonHeartbeat);
      jo.put("guestDanmaku", guestDanmaku);
      jo.put("filterEntryMsg", filterEntryMsg);
      jo.put("listMode", listMode);
      jo.put("fakeRoomId", fakeRoomId);
      jo.put("blacklist", new JSONArray(toStrList(blacklist)));
      jo.put("whitelist", new JSONArray(toStrList(whitelist)));
      jo.put("firstVisibleRooms", new JSONArray(toStrList(firstVisibleRooms)));
      JSONObject names = new JSONObject();
      for (Map.Entry<Long, String> e : roomNames.entrySet()) {
        names.put(String.valueOf(e.getKey()), e.getValue());
      }
      jo.put("roomNames", names);
    } catch (Exception ignored) {
    }
    return jo;
  }

  public String toJsonString() {
    return toJson().toString();
  }

  /** 从 JSON 反序列化; 输入非法时返回 null (调用方沿用旧配置/默认值)。 */
  public static StealthConfig fromJson(String json) {
    if (json == null || json.isEmpty()) return null;
    try {
      JSONObject jo = new JSONObject(json);
      StealthConfig c = new StealthConfig();
      c.master = jo.optBoolean("master", true);
      c.stealthEnter = jo.optBoolean("stealthEnter", true);
      c.anonHeartbeat = jo.optBoolean("anonHeartbeat", true);
      c.guestDanmaku = jo.optBoolean("guestDanmaku", false);
      c.filterEntryMsg = jo.optBoolean("filterEntryMsg", false);
      c.listMode = MODE_WHITE.equals(jo.optString("listMode")) ? MODE_WHITE : MODE_BLACK;
      c.fakeRoomId = jo.optString("fakeRoomId", "510");
      if (c.fakeRoomId == null || c.fakeRoomId.isEmpty()) c.fakeRoomId = "510";
      c.blacklist = toLongSet(optStrings(jo, "blacklist"));
      c.whitelist = toLongSet(optStrings(jo, "whitelist"));
      c.firstVisibleRooms = toLongSet(optStrings(jo, "firstVisibleRooms"));
      JSONObject names = jo.optJSONObject("roomNames");
      if (names != null) {
        Iterator<String> it = names.keys();
        while (it.hasNext()) {
          String k = it.next();
          try {
            long roomId = Long.parseLong(k);
            String name = names.optString(k, "");
            if (roomId > 0 && !name.isEmpty()) c.roomNames.put(roomId, name);
          } catch (Exception ignored) {
          }
        }
      }
      return c;
    } catch (Exception e) {
      return null;
    }
  }

  private static List<String> optStrings(JSONObject jo, String key) {
    JSONArray arr = jo.optJSONArray(key);
    List<String> out = new ArrayList<>();
    if (arr != null) {
      for (int i = 0; i < arr.length(); i++) out.add(arr.optString(i));
    }
    return out;
  }

  private static Set<Long> toLongSet(List<String> src) {
    Set<Long> out = new HashSet<>();
    for (String s : src) {
      try {
        long v = Long.parseLong(s.trim());
        if (v > 0) out.add(v);
      } catch (Exception ignored) {
      }
    }
    return out;
  }

  private static List<String> toStrList(Set<Long> src) {
    List<String> out = new ArrayList<>();
    for (Long v : src) out.add(String.valueOf(v));
    return out;
  }
}
