package io.github.ruix156.bilivestealth.hooks;

import static io.github.ruix156.bilivestealth.MainHook.appName;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.ruix156.bilivestealth.MainHook;
import io.github.ruix156.bilivestealth.Settings;

import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 弹幕相关 hook:
 * 1. websocket 认证包 uid 处理 (默认保持真实登录态, 修复昵称被打码为 *** 的问题)
 * 2. 本地过滤进场消息/特效 (可选, 实验性)
 * 3. 从解析结果中嗅探房间的主播 uid 与开播时间 (场次), 用于名单显示与 "首进可见" 功能
 */
public class DanmakuHook {
  private static final boolean DEBUG = false;
  private static final String DROP_CMD = "__bili_stealth_drop__";

  // 需要本地过滤的进场相关消息 cmd
  private static final Set<String> FILTER_CMDS = new HashSet<>(Arrays.asList(
      "INTERACT_WORD", "INTERACT_WORD_V2", "WELCOME", "WELCOME_GUARD", "ENTRY_EFFECT", "LOG_IN_NOTICE"));

  private final MainHook mainHook;
  private final PackageReadyParam param;
  private final Settings settings;

  public DanmakuHook(MainHook mainHook, PackageReadyParam param, Settings settings) {
    this.mainHook = mainHook;
    this.param = param;
    this.settings = settings;
  }

  public void hook() throws Throwable {
    authPacket();
    parseHook();
  }

  // 弹幕服务器 websocket 认证包
  // 默认保持真实 uid (登录态连接), 避免 B 站将弹幕发送者昵称打码为 ***
  // 仅当开启 "游客弹幕连接" 时才将 uid 置 0 (匿名)
  private void authPacket() throws Throwable {
    Class<?> clazz = Class.forName("com.alibaba.fastjson.JSONObject", false, param.getClassLoader());
    Method method = clazz.getDeclaredMethod("put", String.class, Object.class);
    mainHook.hook(method).intercept(chain -> {
      try {
        // 嗅探主播 uid: 组装含 room_id + uid 且形如房间信息的对象时记录映射
        Object key = chain.getArg(0);
        Object thiz = chain.getThisObject();
        if (thiz instanceof Map) {
          Map<?, ?> m = (Map<?, ?>) thiz;
          if ("uid".equals(key) && m.containsKey("room_id") && looksLikeRoomInfo(m)) {
            settings.recordAnchor(parseLong(m.get("room_id")), parseLong(chain.getArg(1)));
          } else if ("room_id".equals(key) && m.containsKey("uid") && looksLikeRoomInfo(m)) {
            settings.recordAnchor(parseLong(chain.getArg(1)), parseLong(m.get("uid")));
          }
        }
      } catch (Throwable ignored) {
      }
      if (!settings.guestDanmaku) return chain.proceed();
      if ("uid".equals(chain.getArg(0)) && ((Map<String, ?>) chain.getThisObject()).containsKey("group")) {
        Object[] args = chain.getArgs().toArray();
        args[1] = 0;
        debugLog("fastjson uid -> 0 (guest danmaku)");
        return chain.proceed(args);
      }
      return chain.proceed();
    });
  }

  // 本地过滤进场消息 + 房间信息嗅探:
  // 拦截 fastjson 对弹幕 websocket 文本/接口响应的解析结果
  private void parseHook() throws Throwable {
    Class<?> clazz = Class.forName("com.alibaba.fastjson.JSON", false, param.getClassLoader());
    for (String name : new String[]{"parseObject", "parse"}) {
      try {
        Method method = clazz.getDeclaredMethod(name, String.class);
        mainHook.hook(method).intercept(chain -> {
          Object result = chain.proceed();
          try {
            if (result instanceof Map) {
              Map<?, ?> m = (Map<?, ?>) result;
              if (settings.filterEntryMsg) {
                filterEntryCmd(m);
              }
              scanRoomInfo(m);
              Object data = m.get("data");
              if (data instanceof Map) {
                scanRoomInfo((Map<?, ?>) data);
              }
            }
          } catch (Throwable t) {
            mainHook.log(Log.WARN, appName, param.getPackageName() + " parse hook failed", t);
          }
          return result;
        });
      } catch (NoSuchMethodException ignored) {
      }
    }
  }

  /** 将进场类消息的 cmd 改写为无效值, 使 UI 不展示。 */
  private void filterEntryCmd(Map<?, ?> m) {
    Object cmd = m.get("cmd");
    if (cmd instanceof String && FILTER_CMDS.contains(cmd)) {
      debugLog("entry message filtered: " + cmd);
      // noinspection unchecked
      ((Map<String, Object>) m).put("cmd", DROP_CMD);
    }
  }

  /**
   * 从扁平 Map 中嗅探房间信息:
   * - room_id + live_time -> 记录开播时间 (场次 key, 用于 "首进可见" 的场次判定)
   * - room_id + uid + 房间信息特征字段 -> 记录主播 uid (用于名单按主播显示)
   */
  private void scanRoomInfo(Map<?, ?> m) {
    if (!m.containsKey("room_id")) return;
    long roomId = parseLong(m.get("room_id"));
    if (roomId <= 0) return;

    Object liveTime = m.get("live_time");
    if (liveTime != null) {
      settings.recordLiveTime(roomId, String.valueOf(liveTime));
    }

    if (looksLikeRoomInfo(m)) {
      long uid = parseLong(m.get("uid"));
      if (uid > 0) {
        settings.recordAnchor(roomId, uid);
      }
    }
  }

  /** 是否形如房间信息 (区别于 INTERACT_WORD 等含 uid+room_id 的互动消息)。 */
  private static boolean looksLikeRoomInfo(Map<?, ?> m) {
    return m.containsKey("title") || m.containsKey("live_status") || m.containsKey("keyframe")
        || m.containsKey("cover") || m.containsKey("parent_area_name");
  }

  private static long parseLong(Object v) {
    if (v == null) return 0L;
    if (v instanceof Number) return ((Number) v).longValue();
    try {
      return Long.parseLong(v.toString().trim());
    } catch (Exception e) {
      return 0L;
    }
  }

  // debug日志
  public void debugLog(String log) {
    if (DEBUG) {
      mainHook.log(Log.INFO, appName, param.getPackageName() + " " + log);
    }
  }
}
